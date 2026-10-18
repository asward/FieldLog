//! Vault-level behaviour: projects, search, sync between devices, S3 mode
//! and errors. Logs are written through `LogSession`.

use std::path::Path;
use std::sync::Arc;

use fieldlog_core::{
    commit_shared, default_voice_phrases, Author, CoreError, GitCredentials, ImageStorage,
    LogSession, S3Config, Vault,
};

const JPEG: &[u8] = &[0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3, 4];

fn author() -> Author {
    Author {
        name: "Tester".into(),
        email: "t@example.com".into(),
    }
}

fn s(p: &Path) -> String {
    p.to_string_lossy().into_owned()
}

fn new_vault(dir: &Path) -> Arc<Vault> {
    let v = Vault::create(s(dir), "Home".into(), author()).unwrap();
    v.create_project("Deck Rebuild".into()).unwrap();
    v
}

/// One log with one note, tagged, committed.
fn log_note(v: &Arc<Vault>, project: &str, text: &str, tags: &[&str]) {
    let l = LogSession::start(v.clone(), project.into(), default_voice_phrases()).unwrap();
    for t in tags {
        l.handle_utterance(format!("tag {t}")).unwrap();
    }
    l.handle_utterance("note".into()).unwrap();
    l.handle_utterance(text.into()).unwrap();
    l.end_log().unwrap().expect("committed");
}

fn workdir_clean(dir: &Path) -> bool {
    let repo = git2::Repository::open(dir).unwrap();
    let st = repo.statuses(None).unwrap();
    st.iter().all(|e| e.status() == git2::Status::IGNORED)
}

#[test]
fn projects_listing_and_search() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    v.create_project("Garden".into()).unwrap();
    log_note(
        &v,
        "deck-rebuild",
        "Sistered the left joist with a 2x8",
        &["framing"],
    );
    log_note(&v, "deck-rebuild", "Ordered joist hangers", &[]);
    commit_shared(
        v.clone(),
        "garden".into(),
        Some("https://example.com/tomatoes".into()),
        vec![],
        vec![],
    )
    .unwrap();

    let projects = v.projects().unwrap();
    assert_eq!(projects.len(), 2);
    let deck = projects.iter().find(|p| p.slug == "deck-rebuild").unwrap();
    assert_eq!(deck.title, "Deck Rebuild");
    assert_eq!(deck.entry_count, 2);

    assert_eq!(v.search("joist".into(), None, None, 0).len(), 2);
    let hits = v.search("joist sistered".into(), None, None, 0);
    assert_eq!(hits.len(), 1);
    assert!(hits[0].snippet.contains("Sistered"));
    assert_eq!(
        v.search("joist".into(), None, Some("framing".into()), 0)
            .len(),
        1
    );
    assert_eq!(v.search("#framing".into(), None, None, 0).len(), 1);
    assert!(v
        .search("tomatoes".into(), Some("deck-rebuild".into()), None, 0)
        .is_empty());
    let hits = v.search("example.com".into(), None, None, 0);
    assert_eq!(hits[0].entry.project, "garden");
    assert_eq!(v.list_logs(Some("garden".into()), 0).len(), 1);
    assert!(workdir_clean(d.path()));
}

#[test]
fn two_devices_sync_through_a_bare_remote() {
    let d = tempfile::tempdir().unwrap();
    let bare = d.path().join("remote.git");
    git2::Repository::init_bare(&bare).unwrap();
    let url = s(&bare);
    let creds = GitCredentials::default();

    let a_dir = d.path().join("a");
    let a = Vault::clone_remote(
        url.clone(),
        s(&a_dir),
        "Home".into(),
        creds.clone(),
        author(),
    )
    .unwrap();
    a.create_project("Deck".into()).unwrap();
    assert!(a.sync(creds.clone(), None).unwrap().pushed);
    assert_eq!(a.unpushed_commits(), 0);

    let b_dir = d.path().join("b");
    let b = Vault::clone_remote(url, s(&b_dir), "ignored".into(), creds.clone(), author()).unwrap();
    assert_eq!(b.config().name, "Home");
    assert_eq!(b.projects().unwrap().len(), 1);

    // Both log offline, then sync: separate files merge cleanly.
    log_note(&a, "deck", "from a", &[]);
    log_note(&b, "deck", "from b", &[]);
    assert_eq!(a.unpushed_commits(), 1);

    assert!(a.sync(creds.clone(), None).unwrap().pushed);
    let rb = b.sync(creds.clone(), None).unwrap();
    assert!(rb.merged && rb.pushed);
    assert_eq!(rb.pulled_commits, 1);
    assert!(rb.conflict_copies.is_empty());

    let ra = a.sync(creds.clone(), None).unwrap();
    assert!(!ra.merged && !ra.pushed);
    assert!(ra.pulled_commits >= 1);
    assert_eq!(a.list_logs(None, 0).len(), 2);
    assert_eq!(b.list_logs(None, 0).len(), 2);
    assert!(workdir_clean(&a_dir) && workdir_clean(&b_dir));
}

#[test]
fn s3_mode_keeps_images_out_of_git_and_queues_uploads() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    let mut cfg = v.config();
    cfg.image_storage = ImageStorage::Git;
    v.set_config(cfg).unwrap();
    commit_shared(
        v.clone(),
        "deck-rebuild".into(),
        None,
        vec![JPEG.to_vec()],
        vec![],
    )
    .unwrap();
    assert_eq!(v.pending_upload_count(), 0, "git mode commits photos");

    let mut cfg = v.config();
    cfg.image_storage = ImageStorage::S3;
    cfg.s3 = Some(S3Config {
        endpoint: "https://s3.us-west-2.amazonaws.com".into(),
        region: "us-west-2".into(),
        bucket: "b".into(),
        prefix: "".into(),
        path_style: false,
    });
    v.set_config(cfg).unwrap();
    assert_eq!(v.pending_upload_count(), 1, "committed photo queued");

    let l = LogSession::start(v.clone(), "deck-rebuild".into(), default_voice_phrases()).unwrap();
    l.add_photo(b"\x89PNG\r\n\x1a\nother".to_vec()).unwrap();
    l.handle_utterance("caption".into()).unwrap();
    l.end_log().unwrap().unwrap();
    assert_eq!(v.pending_upload_count(), 2);
    assert!(workdir_clean(d.path()), "assets must be ignored in S3 mode");

    let repo = git2::Repository::open(d.path()).unwrap();
    let tree = repo.head().unwrap().peel_to_tree().unwrap();
    assert!(tree.get_name("assets").is_none());

    let v2 = Vault::open(s(d.path()), author()).unwrap();
    assert_eq!(v2.config().image_storage, ImageStorage::S3);
}

#[test]
fn errors() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    assert!(matches!(
        LogSession::start(v.clone(), "nope".into(), default_voice_phrases()),
        Err(CoreError::NotFound { .. })
    ));
    assert!(matches!(
        v.create_project("!!!".into()),
        Err(CoreError::Invalid { .. })
    ));
    assert!(matches!(
        v.read_entry("../etc/passwd".into()),
        Err(CoreError::Invalid { .. })
    ));
    assert!(matches!(
        v.read_log("../etc/passwd".into()),
        Err(CoreError::Invalid { .. })
    ));
    assert!(matches!(
        Vault::create(s(d.path()), "again".into(), author()),
        Err(CoreError::Invalid { .. })
    ));
    assert!(matches!(
        v.sync(GitCredentials::default(), None),
        Err(CoreError::NotFound { .. })
    ));
}
