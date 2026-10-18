//! Upload queued images to a local fake S3 and fetch them back on a second
//! device. The fake checks that requests are SigV4-signed and stores bodies.

use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::thread;

use fieldlog_core::{
    commit_shared, Author, GitCredentials, ImageStorage, S3Config, S3Credentials, Vault,
};

type Store = Arc<Mutex<HashMap<String, Vec<u8>>>>;

fn fake_s3() -> (String, Store) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let store: Store = Arc::default();
    let st = store.clone();
    thread::spawn(move || {
        for conn in listener.incoming() {
            let Ok(mut conn) = conn else { continue };
            let st = st.clone();
            thread::spawn(move || loop {
                let mut r = BufReader::new(conn.try_clone().unwrap());
                let mut line = String::new();
                if r.read_line(&mut line).unwrap_or(0) == 0 {
                    return;
                }
                let mut parts = line.split_whitespace();
                let method = parts.next().unwrap_or("").to_string();
                let path = parts.next().unwrap_or("").to_string();
                let mut len = 0usize;
                let mut signed = false;
                loop {
                    let mut h = String::new();
                    r.read_line(&mut h).unwrap();
                    let h = h.trim_end();
                    if h.is_empty() {
                        break;
                    }
                    let lower = h.to_ascii_lowercase();
                    if let Some(v) = lower.strip_prefix("content-length:") {
                        len = v.trim().parse().unwrap();
                    }
                    if lower.starts_with("authorization: aws4-hmac-sha256 credential=ak/") {
                        signed = true;
                    }
                }
                let mut body = vec![0; len];
                r.read_exact(&mut body).unwrap();
                let (status, out): (&str, Vec<u8>) = if !signed {
                    ("403 Forbidden", vec![])
                } else {
                    let mut s = st.lock().unwrap();
                    match method.as_str() {
                        "PUT" => {
                            s.insert(path, body);
                            ("200 OK", vec![])
                        }
                        "GET" => match s.get(&path) {
                            Some(b) => ("200 OK", b.clone()),
                            None => ("404 Not Found", vec![]),
                        },
                        "HEAD" => {
                            if s.contains_key(&path) {
                                ("200 OK", vec![])
                            } else {
                                ("404 Not Found", vec![])
                            }
                        }
                        _ => ("405 Method Not Allowed", vec![]),
                    }
                };
                let len = if method == "HEAD" { 0 } else { out.len() };
                write!(conn, "HTTP/1.1 {status}\r\nContent-Length: {len}\r\n\r\n").unwrap();
                if method != "HEAD" {
                    conn.write_all(&out).unwrap();
                }
            });
        }
    });
    (format!("http://{addr}"), store)
}

fn author() -> Author {
    Author {
        name: "T".into(),
        email: "t@example.com".into(),
    }
}

fn s(p: &Path) -> String {
    p.to_string_lossy().into_owned()
}

#[test]
fn images_go_to_s3_and_come_back() {
    let (endpoint, store) = fake_s3();
    let d = tempfile::tempdir().unwrap();
    let bare = d.path().join("remote.git");
    git2::Repository::init_bare(&bare).unwrap();
    let git = GitCredentials::default();
    let s3c = S3Credentials {
        access_key_id: "ak".into(),
        secret_access_key: "sk".into(),
        session_token: None,
    };

    let a = Vault::clone_remote(
        s(&bare),
        s(&d.path().join("a")),
        "Home".into(),
        git.clone(),
        author(),
    )
    .unwrap();
    let mut cfg = a.config();
    cfg.image_storage = ImageStorage::S3;
    cfg.s3 = Some(S3Config {
        endpoint,
        region: "us-east-1".into(),
        bucket: "notes".into(),
        prefix: "fl".into(),
        path_style: true,
    });
    a.set_config(cfg).unwrap();
    a.create_project("Deck".into()).unwrap();
    let jpeg = vec![0xFF, 0xD8, 0xFF, 0xE0, 9, 9, 9];
    let info = commit_shared(a.clone(), "deck".into(), None, vec![jpeg.clone()], vec![]).unwrap();
    assert_eq!(a.pending_upload_count(), 1);

    let report = a.sync(git.clone(), Some(s3c.clone())).unwrap();
    assert_eq!(report.uploaded_assets, 1);
    assert!(report.pushed);
    assert_eq!(a.pending_upload_count(), 0);
    let sha = a.read_entry(info.entry.path.clone()).unwrap().photos[0].clone();
    let key = format!("/notes/fl/assets/{}/{sha}.jpg", &sha[..2]);
    assert_eq!(store.lock().unwrap().get(&key), Some(&jpeg));

    // Second device: image is not in git, fetched on demand.
    let b =
        Vault::clone_remote(s(&bare), s(&d.path().join("b")), "x".into(), git, author()).unwrap();
    let local = b.photo_path(sha.clone(), "jpg".into());
    assert!(!Path::new(&local).exists());
    assert!(b.fetch_photo(sha.clone(), "jpg".into(), None).is_err());
    let got = b.fetch_photo(sha, "jpg".into(), Some(s3c)).unwrap();
    assert_eq!(std::fs::read(got).unwrap(), jpeg);
}
