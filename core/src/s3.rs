//! Minimal S3 client (PUT / GET / HEAD) with AWS Signature V4.
//! Works with AWS S3 and S3-compatible stores (R2, B2, MinIO) via `endpoint`
//! and `path_style`.

use std::collections::BTreeMap;
use std::io::Read;

use chrono::{DateTime, Utc};
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::error::{CoreError, Result};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct S3Config {
    /// e.g. `https://s3.us-east-1.amazonaws.com` or `https://<acct>.r2.cloudflarestorage.com`
    pub endpoint: String,
    pub region: String,
    pub bucket: String,
    /// Key prefix inside the bucket, e.g. `fieldlog/home`. May be empty.
    #[serde(default)]
    pub prefix: String,
    #[serde(default)]
    pub path_style: bool,
}

/// Secrets live on the device, never in the vault.
#[derive(Debug, Clone, uniffi::Record)]
pub struct S3Credentials {
    pub access_key_id: String,
    pub secret_access_key: String,
    pub session_token: Option<String>,
}

fn s3_err(msg: impl Into<String>) -> CoreError {
    CoreError::S3 { msg: msg.into() }
}

fn hmac(key: &[u8], data: &[u8]) -> Vec<u8> {
    let mut m = Hmac::<Sha256>::new_from_slice(key).expect("hmac accepts any key length");
    m.update(data);
    m.finalize().into_bytes().to_vec()
}

fn sha256_hex(data: &[u8]) -> String {
    hex::encode(Sha256::digest(data))
}

/// RFC 3986 encoding as SigV4 wants it; `/` kept when encoding a path.
fn uri_encode(s: &str, keep_slash: bool) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        let keep = b.is_ascii_alphanumeric()
            || matches!(b, b'-' | b'_' | b'.' | b'~')
            || (keep_slash && b == b'/');
        if keep {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// Build the `Authorization` header value for a request.
/// `headers` must contain `host`, `x-amz-date` and `x-amz-content-sha256`;
/// all of `headers` are signed. Keys must be lowercase.
#[allow(clippy::too_many_arguments)]
pub(crate) fn authorization(
    method: &str,
    canonical_path: &str,
    canonical_query: &str,
    headers: &BTreeMap<String, String>,
    payload_hash: &str,
    region: &str,
    creds: &S3Credentials,
    now: DateTime<Utc>,
) -> String {
    let amz_date = now.format("%Y%m%dT%H%M%SZ").to_string();
    let date = now.format("%Y%m%d").to_string();
    let canonical_headers: String = headers
        .iter()
        .map(|(k, v)| format!("{k}:{}\n", v.trim()))
        .collect();
    let signed_headers = headers.keys().cloned().collect::<Vec<_>>().join(";");
    let canonical_request = format!(
        "{method}\n{canonical_path}\n{canonical_query}\n{canonical_headers}\n{signed_headers}\n{payload_hash}"
    );
    let scope = format!("{date}/{region}/s3/aws4_request");
    let string_to_sign = format!(
        "AWS4-HMAC-SHA256\n{amz_date}\n{scope}\n{}",
        sha256_hex(canonical_request.as_bytes())
    );
    let k_date = hmac(
        format!("AWS4{}", creds.secret_access_key).as_bytes(),
        date.as_bytes(),
    );
    let k_region = hmac(&k_date, region.as_bytes());
    let k_service = hmac(&k_region, b"s3");
    let k_signing = hmac(&k_service, b"aws4_request");
    let signature = hex::encode(hmac(&k_signing, string_to_sign.as_bytes()));
    format!(
        "AWS4-HMAC-SHA256 Credential={}/{scope}, SignedHeaders={signed_headers}, Signature={signature}",
        creds.access_key_id
    )
}

pub struct S3Client {
    cfg: S3Config,
    creds: S3Credentials,
    agent: ureq::Agent,
}

impl S3Client {
    pub fn new(cfg: S3Config, creds: S3Credentials) -> Self {
        let agent = ureq::Agent::config_builder()
            .http_status_as_error(false)
            .build()
            .new_agent();
        S3Client { cfg, creds, agent }
    }

    fn full_key(&self, key: &str) -> String {
        let p = self.cfg.prefix.trim_matches('/');
        if p.is_empty() {
            key.to_string()
        } else {
            format!("{p}/{key}")
        }
    }

    /// (url, host, canonical path)
    fn locate(&self, key: &str) -> Result<(String, String, String)> {
        let ep = self.cfg.endpoint.trim_end_matches('/');
        let (scheme, host) = ep
            .split_once("://")
            .ok_or_else(|| s3_err("endpoint must start with https:// or http://"))?;
        let key_path = uri_encode(&self.full_key(key), true);
        if self.cfg.path_style {
            let path = format!("/{}/{key_path}", uri_encode(&self.cfg.bucket, false));
            Ok((format!("{scheme}://{host}{path}"), host.to_string(), path))
        } else {
            let vhost = format!("{}.{host}", self.cfg.bucket);
            let path = format!("/{key_path}");
            Ok((format!("{scheme}://{vhost}{path}"), vhost, path))
        }
    }

    fn signed_headers(
        &self,
        method: &str,
        host: &str,
        path: &str,
        payload_hash: &str,
        extra: &[(&str, String)],
    ) -> BTreeMap<String, String> {
        let now = Utc::now();
        let mut h = BTreeMap::new();
        h.insert("host".to_string(), host.to_string());
        h.insert("x-amz-content-sha256".into(), payload_hash.to_string());
        h.insert(
            "x-amz-date".into(),
            now.format("%Y%m%dT%H%M%SZ").to_string(),
        );
        if let Some(t) = &self.creds.session_token {
            h.insert("x-amz-security-token".into(), t.clone());
        }
        for (k, v) in extra {
            h.insert(k.to_string(), v.clone());
        }
        let auth = authorization(
            method,
            path,
            "",
            &h,
            payload_hash,
            &self.cfg.region,
            &self.creds,
            now,
        );
        h.insert("authorization".into(), auth);
        h.remove("host");
        h
    }

    pub fn put(&self, key: &str, body: &[u8], content_type: &str) -> Result<()> {
        let (url, host, path) = self.locate(key)?;
        let hash = sha256_hex(body);
        let headers = self.signed_headers(
            "PUT",
            &host,
            &path,
            &hash,
            &[("content-type", content_type.to_string())],
        );
        let mut req = self.agent.put(&url);
        for (k, v) in &headers {
            req = req.header(k, v);
        }
        let resp = req.send(body).map_err(|e| s3_err(e.to_string()))?;
        let status = resp.status().as_u16();
        if (200..300).contains(&status) {
            Ok(())
        } else {
            Err(s3_err(format!("PUT {key}: HTTP {status}")))
        }
    }

    /// `Ok(None)` when the object doesn't exist.
    pub fn get(&self, key: &str) -> Result<Option<Vec<u8>>> {
        let (url, host, path) = self.locate(key)?;
        let headers = self.signed_headers("GET", &host, &path, &sha256_hex(b""), &[]);
        let mut req = self.agent.get(&url);
        for (k, v) in &headers {
            req = req.header(k, v);
        }
        let mut resp = req.call().map_err(|e| s3_err(e.to_string()))?;
        let status = resp.status().as_u16();
        match status {
            200 => {
                let mut buf = Vec::new();
                resp.body_mut()
                    .as_reader()
                    .read_to_end(&mut buf)
                    .map_err(|e| s3_err(e.to_string()))?;
                Ok(Some(buf))
            }
            404 => Ok(None),
            _ => Err(s3_err(format!("GET {key}: HTTP {status}"))),
        }
    }

    pub fn exists(&self, key: &str) -> Result<bool> {
        let (url, host, path) = self.locate(key)?;
        let headers = self.signed_headers("HEAD", &host, &path, &sha256_hex(b""), &[]);
        let mut req = self.agent.head(&url);
        for (k, v) in &headers {
            req = req.header(k, v);
        }
        let resp = req.call().map_err(|e| s3_err(e.to_string()))?;
        match resp.status().as_u16() {
            200 => Ok(true),
            404 => Ok(false),
            s => Err(s3_err(format!("HEAD {key}: HTTP {s}"))),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    /// Example from the AWS docs: "Example: GET Object" (header-based auth).
    #[test]
    fn aws_documented_get_object_signature() {
        let creds = S3Credentials {
            access_key_id: "AKIAIOSFODNN7EXAMPLE".into(),
            secret_access_key: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY".into(),
            session_token: None,
        };
        let empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        let mut h = BTreeMap::new();
        h.insert("host".to_string(), "examplebucket.s3.amazonaws.com".into());
        h.insert("range".into(), "bytes=0-9".into());
        h.insert("x-amz-content-sha256".into(), empty.into());
        h.insert("x-amz-date".into(), "20130524T000000Z".into());
        let now = Utc.with_ymd_and_hms(2013, 5, 24, 0, 0, 0).unwrap();
        let auth = authorization("GET", "/test.txt", "", &h, empty, "us-east-1", &creds, now);
        assert_eq!(
            auth,
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, \
             SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, \
             Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
        );
    }

    #[test]
    fn urls() {
        let creds = S3Credentials {
            access_key_id: "a".into(),
            secret_access_key: "b".into(),
            session_token: None,
        };
        let mut cfg = S3Config {
            endpoint: "https://s3.us-west-2.amazonaws.com/".into(),
            region: "us-west-2".into(),
            bucket: "notes".into(),
            prefix: "/fieldlog/home/".into(),
            path_style: false,
        };
        let c = S3Client::new(cfg.clone(), creds.clone());
        let (url, host, path) = c.locate("assets/ab/ab c.jpg").unwrap();
        assert_eq!(host, "notes.s3.us-west-2.amazonaws.com");
        assert_eq!(path, "/fieldlog/home/assets/ab/ab%20c.jpg");
        assert_eq!(url, format!("https://{host}{path}"));

        cfg.path_style = true;
        cfg.prefix = String::new();
        let c = S3Client::new(cfg, creds);
        let (url, _, _) = c.locate("k.jpg").unwrap();
        assert_eq!(url, "https://s3.us-west-2.amazonaws.com/notes/k.jpg");
    }
}
