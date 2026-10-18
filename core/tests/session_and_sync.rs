use std::path::Path;
use std::sync::Arc;

use fieldlog_core::{
    commit_shared, Author, Block, Command, CoreError, GitCredentials, ImageStorage, S3Config,
    Session, SessionEvent, SessionSettings, Vault,
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

fn session(v: &Arc<Vault>, prefix: Option<&str>) -> Arc<Session> {
    Session::new(
        v.clone(),
        "deck-rebuild".into(),
        SessionSettings {
            command_prefix: prefix.map(String::from),
            silence_timeout_secs: 30,
        },
    )
    .unwrap()
}

fn git_log(dir: &Path) -> Vec<String> {
    let repo = git2::Repository::open(dir).unwrap();
    let mut w = repo.revwalk().unwrap();
    w.push_head().unwrap();
    w.map(|o| {
        repo.find_commit(o.unwrap())
            .unwrap()
            .summary()
            .unwrap()
            .unwrap()
            .to_string()
    })
    .collect()
}

fn workdir_clean(dir: &Path) -> bool {
    let repo = git2::Repository::open(dir).unwrap();
    let st = repo.statuses(None).unwrap();
    st.iter().all(|e| e.status() == git2::Status::IGNORED)
}

#[test]
fn narrate_snap_and_next_commits_one_entry_with_inline_photo() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    let sess = session(&v, None);

    sess.handle_utterance("pulled the old ledger board".into())
        .unwrap();
    let ev = sess.handle_utterance("snap".into()).unwrap();
    assert!(matches!(ev[..], [SessionEvent::PhotoRequested]));
    sess.add_photo(JPEG.to_vec()).unwrap();
    sess.handle_utterance("rot goes about two feet in".into())
        .unwrap();
    sess.handle_utterance("tag rot".into()).unwrap();
    let ev = sess.handle_utterance("next".into()).unwrap();
    let SessionEvent::Committed { info } = &ev[0] else {
        panic!("expected commit, got {ev:?}")
    };

    let entry = v.read_entry(info.entry.path.clone()).unwrap();
    assert_eq!(entry.tags, vec!["rot"]);
    assert_eq!(entry.source.as_deref(), Some("session"));
    assert_eq!(entry.photos.len(), 1);
    let sha = &entry.photos[0];
    assert_eq!(
        entry.body,
        format!(
            "Pulled the old ledger board.\n\n![](../../../assets/{}/{sha}.jpg)\n\nRot goes about two feet in.",
            &sha[..2]
        )
    );
    assert!(d.path().join(&info.entry.path).exists());
    assert!(Path::new(&v.photo_path(sha.clone(), "jpg".into())).exists());
    assert!(git_log(d.path())[0].starts_with("deck-rebuild: Pulled the old ledger board."));
    assert!(
        workdir_clean(d.path()),
        "photo and entry should both be committed"
    );

    // Draft is empty again; "next" with nothing is a no-op.
    assert!(sess.state().blocks.is_empty());
    assert!(matches!(
        sess.commit().unwrap(),
        SessionEvent::NothingToCommit
    ));
}

#[test]
fn scratch_that_removes_last_sentence_only() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    let sess = session(&v, None);
    sess.handle_utterance("first thing".into()).unwrap();
    sess.handle_utterance("second thing".into()).unwrap();
    let ev = sess.handle_utterance("scratch that".into()).unwrap();
    assert!(matches!(&ev[0], SessionEvent::Scratched { removed } if removed == "Second thing."));
    assert_eq!(
        sess.state().blocks,
        vec![Block::Text {
            text: "First thing.".into()
        }]
    );
    sess.apply(Command::ScratchThat).unwrap();
    assert!(sess.state().blocks.is_empty());
    assert!(sess.state().started.is_none());
    assert!(matches!(
        sess.apply(Command::ScratchThat).unwrap(),
        SessionEvent::NothingToScratch
    ));
}

#[test]
fn undo_reverts_last_entry_then_the_one_before() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    let sess = session(&v, None);
    sess.add_text("one".into()).unwrap();
    let SessionEvent::Committed { info: a } = sess.commit().unwrap() else {
        panic!()
    };
    sess.add_text("two".into()).unwrap();
    let SessionEvent::Committed { info: b } = sess.commit().unwrap() else {
        panic!()
    };

    let SessionEvent::Undone { entry } = sess.handle_utterance("undo".into()).unwrap().remove(0)
    else {
        panic!()
    };
    assert_eq!(entry.id, b.entry.id);
    assert!(!d.path().join(&b.entry.path).exists());
    assert!(d.path().join(&a.entry.path).exists());

    let SessionEvent::Undone { entry } = sess.apply(Command::Undo).unwrap() else {
        panic!()
    };
    assert_eq!(entry.id, a.entry.id);
    assert!(matches!(
        sess.apply(Command::Undo).unwrap(),
        SessionEvent::NothingToUndo
    ));
    assert!(workdir_clean(d.path()));
    assert_eq!(v.list_entries(None, 0).len(), 0);
}

#[test]
fn prefix_mode_ignores_bare_next() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    let sess = session(&v, Some("log"));
    sess.handle_utterance("next".into()).unwrap();
    assert_eq!(sess.state().blocks.len(), 1);
    let ev = sess
        .handle_utterance("then the stringer, log next".into())
        .unwrap();
    assert!(matches!(
        ev[..],
        [
            SessionEvent::TextAdded { .. },
            SessionEvent::Committed { .. }
        ]
    ));
    let e = &v.list_entries(None, 0)[0];
    assert_eq!(e.summary, "Next. Then the stringer.");
}

#[test]
fn draft_survives_restart() {
    let d = tempfile::tempdir().unwrap();
    {
        let v = new_vault(d.path());
        let sess = session(&v, None);
        sess.handle_utterance("half a thought".into()).unwrap();
        sess.handle_utterance("tag wip".into()).unwrap();
    }
    let v = Vault::open(s(d.path()), author()).unwrap();
    let sess = session(&v, None);
    let st = sess.state();
    assert_eq!(st.blocks.len(), 1);
    assert_eq!(st.tags, vec!["wip"]);
    assert!(st.started.is_some());
    assert!(workdir_clean(d.path()), "drafts must be git-ignored");
}

#[test]
fn silence_commits_unless_disabled() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    let sess = session(&v, None);
    sess.add_text("quiet now".into()).unwrap();
    assert!(matches!(
        sess.on_silence().unwrap(),
        SessionEvent::Committed { .. }
    ));
    sess.set_settings(SessionSettings {
        command_prefix: None,
        silence_timeout_secs: 0,
    });
    sess.add_text("again".into()).unwrap();
    assert!(matches!(
        sess.on_silence().unwrap(),
        SessionEvent::NothingToCommit
    ));
}

#[test]
fn projects_listing_and_search() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    v.create_project("Garden".into()).unwrap();
    let sess = session(&v, None);
    sess.add_text("Sistered the left joist with a 2x8".into())
        .unwrap();
    sess.apply(Command::Tag {
        tag: "framing".into(),
    })
    .unwrap();
    sess.commit().unwrap();
    sess.add_text("Ordered joist hangers".into()).unwrap();
    sess.commit().unwrap();
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

    let hits = v.search("joist".into(), None, None, 0);
    assert_eq!(hits.len(), 2);
    let hits = v.search("joist sistered".into(), None, None, 0);
    assert_eq!(hits.len(), 1);
    assert!(hits[0].snippet.contains("Sistered"));
    let hits = v.search("joist".into(), None, Some("framing".into()), 0);
    assert_eq!(hits.len(), 1);
    let hits = v.search("#framing".into(), None, None, 0);
    assert_eq!(hits.len(), 1);
    let hits = v.search("tomatoes".into(), Some("deck-rebuild".into()), None, 0);
    assert!(hits.is_empty());
    let hits = v.search("example.com".into(), None, None, 0);
    assert_eq!(hits[0].entry.project, "garden");
    assert_eq!(v.list_entries(Some("garden".into()), 0).len(), 1);
}

#[test]
fn share_with_images() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    let info = commit_shared(
        v.clone(),
        "deck-rebuild".into(),
        None,
        vec![JPEG.to_vec(), b"\x89PNG\r\n\x1a\n...".to_vec()],
        vec!["receipts".into()],
    )
    .unwrap();
    assert_eq!(info.entry.photo_count, 2);
    let e = v.read_entry(info.entry.path).unwrap();
    assert_eq!(e.source.as_deref(), Some("share"));
    assert!(e.body.contains(".png)"));
    assert!(workdir_clean(d.path()));
}

#[test]
fn two_devices_sync_through_a_bare_remote() {
    let d = tempfile::tempdir().unwrap();
    let bare = d.path().join("remote.git");
    git2::Repository::init_bare(&bare).unwrap();
    let url = s(&bare);
    let creds = GitCredentials::default();

    // Device A creates the vault and pushes.
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
    let r = a.sync(creds.clone(), None).unwrap();
    assert!(r.pushed);
    assert_eq!(a.unpushed_commits(), 0);

    // Device B clones the existing vault.
    let b_dir = d.path().join("b");
    let b = Vault::clone_remote(
        url.clone(),
        s(&b_dir),
        "ignored".into(),
        creds.clone(),
        author(),
    )
    .unwrap();
    assert_eq!(b.config().name, "Home");
    assert_eq!(b.projects().unwrap().len(), 1);

    // Both write offline, then sync: B fast-forwards? No, both diverge -> merge.
    let sa = Session::new(a.clone(), "deck".into(), SessionSettings::default()).unwrap();
    sa.add_text("from a".into()).unwrap();
    sa.commit().unwrap();
    let sb = Session::new(b.clone(), "deck".into(), SessionSettings::default()).unwrap();
    sb.add_text("from b".into()).unwrap();
    sb.commit().unwrap();
    assert_eq!(a.unpushed_commits(), 1);

    assert!(a.sync(creds.clone(), None).unwrap().pushed);
    let rb = b.sync(creds.clone(), None).unwrap();
    assert!(rb.merged);
    assert!(rb.pushed);
    assert_eq!(rb.pulled_commits, 1);

    let ra = a.sync(creds.clone(), None).unwrap();
    assert!(!ra.merged);
    assert!(ra.pulled_commits >= 1);
    assert!(!ra.pushed);
    assert_eq!(a.list_entries(None, 0).len(), 2);
    assert_eq!(b.list_entries(None, 0).len(), 2);
    assert!(workdir_clean(&a_dir) && workdir_clean(&b_dir));
}

#[test]
fn s3_mode_keeps_images_out_of_git_and_queues_uploads() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    // An image committed before switching modes gets queued too.
    commit_shared(
        v.clone(),
        "deck-rebuild".into(),
        None,
        vec![JPEG.to_vec()],
        vec![],
    )
    .unwrap();
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
    assert_eq!(v.pending_upload_count(), 1);

    let sess = session(&v, None);
    sess.add_photo(b"\x89PNG\r\n\x1a\nother".to_vec()).unwrap();
    sess.add_text("caption".into()).unwrap();
    sess.commit().unwrap();
    assert_eq!(v.pending_upload_count(), 2);
    assert!(workdir_clean(d.path()), "assets must be ignored in S3 mode");

    let repo = git2::Repository::open(d.path()).unwrap();
    let tree = repo.head().unwrap().peel_to_tree().unwrap();
    assert!(tree.get_name("assets").is_none());

    // Reopening reads the committed config.
    let v2 = Vault::open(s(d.path()), author()).unwrap();
    assert_eq!(v2.config().image_storage, ImageStorage::S3);
}

#[test]
fn errors() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    assert!(matches!(
        Session::new(v.clone(), "nope".into(), SessionSettings::default()),
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
        Vault::create(s(d.path()), "again".into(), author()),
        Err(CoreError::Invalid { .. })
    ));
    assert!(matches!(
        v.sync(GitCredentials::default(), None),
        Err(CoreError::NotFound { .. })
    ));
}
