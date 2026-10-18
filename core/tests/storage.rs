//! Photo storage modes (spec §6).

use std::path::Path;
use std::sync::Arc;

use fieldlog_core::{commit_shared, is_hosted_git_remote, Author, ImageStorage, S3Config, Vault};

const JPEG: &[u8] = &[0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3, 4];
const PNG: &[u8] = b"\x89PNG\r\n\x1a\nother";

fn author() -> Author {
    Author {
        name: "Tester".into(),
        email: "t@example.com".into(),
    }
}

fn new_vault(dir: &Path) -> Arc<Vault> {
    let v = Vault::create(dir.to_string_lossy().into_owned(), "Home".into(), author()).unwrap();
    v.create_project("Deck".into()).unwrap();
    v
}

fn tree_has_assets(dir: &Path) -> bool {
    let repo = git2::Repository::open(dir).unwrap();
    let tree = repo.head().unwrap().peel_to_tree().unwrap();
    let has = tree.get_name("assets").is_some();
    has
}

fn workdir_clean(dir: &Path) -> bool {
    let repo = git2::Repository::open(dir).unwrap();
    let st = repo.statuses(None).unwrap();
    st.iter().all(|e| e.status() == git2::Status::IGNORED)
}

#[test]
fn new_vaults_keep_photos_on_the_phone() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    assert_eq!(v.config().image_storage, ImageStorage::Local);
    commit_shared(
        v.clone(),
        "deck".into(),
        None,
        vec![JPEG.to_vec(), PNG.to_vec()],
        vec![],
    )
    .unwrap();

    assert!(!tree_has_assets(d.path()), "photos are not committed");
    assert!(workdir_clean(d.path()), "assets/ is ignored");
    assert_eq!(
        v.pending_upload_count(),
        2,
        "queued for a later R2/S3 upload"
    );
    let b = v.photo_backup();
    assert_eq!(b.count, 2);
    assert_eq!(b.bytes, (JPEG.len() + PNG.len()) as u64);
    let photos = v.local_photos();
    assert_eq!(photos.len(), 2);
    assert!(photos.iter().all(|p| p.starts_with("assets/")));
    let toml = std::fs::read_to_string(d.path().join("fieldlog.toml")).unwrap();
    assert!(toml.contains("image_storage = \"local\""), "{toml}");
}

#[test]
fn local_to_git_commits_photos_and_back_untracks_them() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    commit_shared(v.clone(), "deck".into(), None, vec![JPEG.to_vec()], vec![]).unwrap();

    let mut cfg = v.config();
    cfg.image_storage = ImageStorage::Git;
    v.set_config(cfg.clone()).unwrap();
    assert!(tree_has_assets(d.path()));
    assert_eq!(v.pending_upload_count(), 0);
    assert_eq!(v.photo_backup().count, 0);
    assert!(workdir_clean(d.path()));

    cfg.image_storage = ImageStorage::Local;
    v.set_config(cfg).unwrap();
    assert!(!tree_has_assets(d.path()));
    assert_eq!(v.pending_upload_count(), 1);
    assert!(workdir_clean(d.path()));
}

#[test]
fn local_to_s3_keeps_the_queue() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    commit_shared(v.clone(), "deck".into(), None, vec![JPEG.to_vec()], vec![]).unwrap();
    let mut cfg = v.config();
    cfg.image_storage = ImageStorage::S3;
    cfg.s3 = Some(S3Config {
        endpoint: "https://acct.r2.cloudflarestorage.com".into(),
        region: "auto".into(),
        bucket: "notes".into(),
        prefix: "".into(),
        path_style: true,
    });
    v.set_config(cfg).unwrap();
    assert_eq!(v.pending_upload_count(), 1);
    assert_eq!(v.photo_backup().count, 1);
}

#[test]
fn hosted_remotes() {
    for url in [
        "git@github.com:me/notes.git",
        "https://github.com/me/notes.git",
        "https://me@gitlab.com/me/notes.git",
        "ssh://git@bitbucket.org/me/notes.git",
        "https://gist.github.com/x",
    ] {
        assert!(is_hosted_git_remote(url.into()), "{url}");
    }
    for url in [
        "git@git.example.com:me/notes.git",
        "https://gitea.home.lan/me/notes.git",
        "/srv/git/notes.git",
        "https://notgithub.com/x",
    ] {
        assert!(!is_hosted_git_remote(url.into()), "{url}");
    }
}
