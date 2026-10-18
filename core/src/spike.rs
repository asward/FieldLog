use std::path::Path;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SpikeError {
    #[error("git: {msg}")]
    Git { msg: String },
    #[error("io: {msg}")]
    Io { msg: String },
}

impl From<git2::Error> for SpikeError {
    fn from(e: git2::Error) -> Self {
        SpikeError::Git {
            msg: e.message().to_string(),
        }
    }
}

impl From<std::io::Error> for SpikeError {
    fn from(e: std::io::Error) -> Self {
        SpikeError::Io { msg: e.to_string() }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LibgitInfo {
    pub core_version: String,
    pub libgit2_version: String,
    pub ssh: bool,
    pub https: bool,
}

/// Version and compiled-in transport support of the bundled libgit2.
#[uniffi::export]
pub fn libgit_info() -> LibgitInfo {
    let v = git2::Version::get();
    let (maj, min, rev) = v.libgit2_version();
    LibgitInfo {
        core_version: env!("CARGO_PKG_VERSION").to_string(),
        libgit2_version: format!("{maj}.{min}.{rev}"),
        ssh: v.ssh(),
        https: v.https(),
    }
}

/// Init a repo in `dir`, write a file, commit it, and read it back from HEAD.
/// Returns the commit id.
#[uniffi::export]
pub fn spike_local_commit(dir: String) -> Result<String, SpikeError> {
    let path = Path::new(&dir);
    std::fs::create_dir_all(path)?;
    let repo = git2::Repository::init(path)?;
    std::fs::write(path.join("hello.md"), "# hello from fieldlog\n")?;
    let mut index = repo.index()?;
    index.add_path(Path::new("hello.md"))?;
    index.write()?;
    let tree = repo.find_tree(index.write_tree()?)?;
    let sig = git2::Signature::now("FieldLog Spike", "spike@fieldlog.invalid")?;
    let parents: Vec<git2::Commit> = match repo.head() {
        Ok(h) => vec![h.peel_to_commit()?],
        Err(_) => vec![],
    };
    let parent_refs: Vec<&git2::Commit> = parents.iter().collect();
    let oid = repo.commit(
        Some("HEAD"),
        &sig,
        &sig,
        "spike commit",
        &tree,
        &parent_refs,
    )?;

    let head_tree = repo.head()?.peel_to_tree()?;
    let entry = head_tree.get_name("hello.md").ok_or(SpikeError::Git {
        msg: "missing file in HEAD".into(),
    })?;
    let blob = repo.find_blob(entry.id())?;
    if !blob.content().starts_with(b"# hello") {
        return Err(SpikeError::Git {
            msg: "content mismatch".into(),
        });
    }
    Ok(oid.to_string())
}

/// List the refs of a remote over SSH using an in-memory private key.
/// Proves libssh2 + key auth work on the device.
#[uniffi::export]
pub fn spike_ls_remote(
    url: String,
    ssh_private_key_pem: String,
    passphrase: Option<String>,
) -> Result<Vec<String>, SpikeError> {
    let mut remote = git2::Remote::create_detached(url.as_str())?;
    let mut cb = git2::RemoteCallbacks::new();
    cb.credentials(move |_url, user, _allowed| {
        git2::Cred::ssh_key_from_memory(
            user.unwrap_or("git"),
            None,
            &ssh_private_key_pem,
            passphrase.as_deref(),
        )
    });
    // Spike only: accept any host key. The real core pins known hosts.
    cb.certificate_check(|_, _| Ok(git2::CertificateCheckStatus::CertificateOk));
    remote.connect_auth(git2::Direction::Fetch, Some(cb), None)?;
    let refs = remote
        .list()?
        .iter()
        .map(|h| format!("{} {}", h.oid(), h.name()))
        .collect();
    remote.disconnect()?;
    Ok(refs)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn info_reports_transports() {
        let i = libgit_info();
        assert!(i.ssh, "libgit2 built without ssh");
        assert!(i.https, "libgit2 built without https");
    }

    #[test]
    fn local_commit_roundtrip() {
        let d = tempfile::tempdir().unwrap();
        let oid = spike_local_commit(d.path().join("v").to_string_lossy().into()).unwrap();
        assert_eq!(oid.len(), 40);
    }
}
