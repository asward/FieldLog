//! SSH deploy-key generation, so the app can set up a remote without the
//! user creating a key elsewhere.

use crate::error::{CoreError, Result};

#[derive(Debug, Clone, uniffi::Record)]
pub struct SshKeyPair {
    /// OpenSSH private key (unencrypted; the app stores it encrypted).
    pub private_openssh: String,
    /// `ssh-ed25519 AAAA… comment`, to paste as a deploy key.
    pub public_openssh: String,
}

#[uniffi::export]
pub fn generate_ssh_key(comment: String) -> Result<SshKeyPair> {
    use ssh_key::{rand_core::OsRng, Algorithm, LineEnding, PrivateKey};
    let fmt = |e: ssh_key::Error| CoreError::Invalid { msg: e.to_string() };
    let mut key = PrivateKey::random(&mut OsRng, Algorithm::Ed25519).map_err(fmt)?;
    key.set_comment(comment);
    Ok(SshKeyPair {
        private_openssh: key.to_openssh(LineEnding::LF).map_err(fmt)?.to_string(),
        public_openssh: key.public_key().to_openssh().map_err(fmt)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generates_openssh_ed25519() {
        let k = generate_ssh_key("fieldlog@phone".into()).unwrap();
        assert!(k
            .private_openssh
            .starts_with("-----BEGIN OPENSSH PRIVATE KEY-----"));
        assert!(k.public_openssh.starts_with("ssh-ed25519 AAAA"));
        assert!(k.public_openssh.ends_with(" fieldlog@phone"));
        // libgit2 accepts it as an in-memory credential.
        git2::Cred::ssh_key_from_memory("git", None, &k.private_openssh, None).unwrap();
    }
}
