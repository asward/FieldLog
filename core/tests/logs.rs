//! V2 logs: the state machine, working-tree drafting, commit on `end log`,
//! re-entry, conflicts and sync behaviour.

use std::path::Path;
use std::sync::Arc;

use fieldlog_core::{
    commit_shared, default_voice_phrases, Author, Block, CoreError, EntryKind, GitCredentials,
    LogCommand, LogEvent, LogMode, LogSession, Vault,
};

const JPEG: &[u8] = &[0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3, 4];
const JPEG2: &[u8] = &[0xFF, 0xD8, 0xFF, 0xE0, 5, 6, 7, 8];

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

fn start(v: &Arc<Vault>) -> Arc<LogSession> {
    LogSession::start(v.clone(), "deck-rebuild".into(), default_voice_phrases()).unwrap()
}

fn say(l: &LogSession, text: &str) -> Vec<LogEvent> {
    l.handle_utterance(text.into()).unwrap()
}

fn git_log(dir: &Path) -> Vec<String> {
    let repo = git2::Repository::open(dir).unwrap();
    let mut w = repo.revwalk().unwrap();
    w.push_head().unwrap();
    w.map(|o| {
        repo.find_commit(o.unwrap())
            .unwrap()
            .message()
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

fn mode(l: &LogSession) -> LogMode {
    l.state().mode
}

#[test]
fn full_log_walkthrough() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    let commits_before = git_log(d.path()).len();
    let l = start(&v);
    assert_eq!(mode(&l), LogMode::Standby);

    // Dictation in standby is ignored.
    assert!(matches!(
        &say(&l, "just talking")[..],
        [LogEvent::Ignored { .. }]
    ));

    say(&l, "note");
    assert_eq!(mode(&l), LogMode::Note);
    say(&l, "pulled the old ledger board");

    // Snap from a note nests: the photo goes inline in the note.
    assert!(matches!(&say(&l, "snap")[..], [LogEvent::PhotoRequested]));
    assert_eq!(mode(&l), LogMode::Note, "mode waits for the photo");
    l.add_photo(JPEG.to_vec()).unwrap();
    assert_eq!(mode(&l), LogMode::Annotate);
    say(&l, "rot at the bolt");
    say(&l, "end annotations");
    assert_eq!(mode(&l), LogMode::Markup);
    say(&l, "circle the dark area.");
    say(&l, "end markup");
    assert_eq!(mode(&l), LogMode::Note, "back to the note");
    say(&l, "rot goes about two feet in, end note");
    assert_eq!(mode(&l), LogMode::Standby);

    // One-shot tags.
    say(&l, "tag pump, valve");
    // Tag mode.
    say(&l, "tag");
    assert_eq!(mode(&l), LogMode::Tag);
    say(&l, "deck framing");
    say(&l, "end tags");

    // Snap from standby: its own entry; then a second snap closes it.
    l.add_photo(JPEG2.to_vec()).unwrap();
    say(&l, "joist end soft to a screwdriver");
    let st = l.state();
    assert_eq!(st.entries.len(), 2);
    assert_eq!(st.entries[1].kind, EntryKind::Snap);

    // Nothing committed yet, but the file is in the working tree.
    assert_eq!(git_log(d.path()).len(), commits_before);
    assert!(d.path().join(&st.path).exists());
    assert!(v.open_log().is_some());

    let ev = say(&l, "end log");
    let Some(LogEvent::LogEnded { commit: Some(info) }) = ev.last() else {
        panic!("expected commit, got {ev:?}")
    };
    assert!(v.open_log().is_none());
    assert!(
        workdir_clean(d.path()),
        "log and photos committed, draft gone"
    );
    let msg = &git_log(d.path())[0];
    assert!(
        msg.starts_with("deck-rebuild: Pulled the old ledger board."),
        "{msg}"
    );
    assert!(msg.contains("FieldLog-Log: "));

    let view = v.read_log(info.entry.path.clone()).unwrap();
    assert_eq!(view.tags, vec!["pump", "valve", "deck-framing"]);
    assert!(view.ended.is_some());
    assert_eq!(view.entries.len(), 2);
    let note = &view.entries[0];
    assert_eq!(note.kind, EntryKind::Note);
    assert_eq!(note.blocks.len(), 3);
    let Block::Photo {
        annotation, markup, ..
    } = &note.blocks[1]
    else {
        panic!()
    };
    assert_eq!(annotation, "Rot at the bolt.");
    assert_eq!(markup, &vec!["circle the dark area".to_string()]);
    assert!(
        matches!(&note.blocks[2], Block::Text { text } if text == "Rot goes about two feet in."),
        "{:?}",
        note.blocks
    );
    let text = std::fs::read_to_string(d.path().join(&info.entry.path)).unwrap();
    assert!(text.contains("\nended: "));
    assert!(text.contains(" Note\n"));
    assert!(text.contains(" Snap\n"));
    assert!(text.contains("> Rot at the bolt."));
    assert!(text.contains("```fieldlog-markup\ncircle the dark area\n```"));

    // The finished object refuses further changes.
    assert!(l.is_closed());
    assert!(l.handle_utterance("note".into()).is_err());

    let logs = v.list_logs(Some("deck-rebuild".into()), 0);
    assert_eq!(logs.len(), 1);
    assert_eq!(logs[0].photo_count, 2);
    assert_eq!(logs[0].preview_photos.len(), 2);
    assert_eq!(logs[0].entry_count, 2);
}

#[test]
fn not_allowed_scratch_cancel_undo() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    let l = start(&v);
    let ev = say(&l, "end note");
    assert!(matches!(
        &ev[..],
        [LogEvent::NotAllowed {
            command: LogCommand::EndNote,
            ..
        }]
    ));
    assert!(matches!(
        &l.apply(LogCommand::EndMarkup).unwrap()[..],
        [LogEvent::NotAllowed { .. }]
    ));

    say(&l, "note");
    say(&l, "first thing");
    say(&l, "second thing");
    let ev = say(&l, "scratch that");
    assert!(matches!(&ev[..], [LogEvent::Scratched { removed }] if removed == "Second thing."));
    say(&l, "end note");

    say(&l, "note");
    say(&l, "throw this away");
    say(&l, "cancel");
    assert_eq!(mode(&l), LogMode::Standby);
    assert_eq!(l.state().entries.len(), 1);

    // Empty note is dropped on end note.
    say(&l, "note");
    say(&l, "end note");
    assert_eq!(l.state().entries.len(), 1);

    // Cancel a snap from standby removes the snap entry.
    l.add_photo(JPEG.to_vec()).unwrap();
    say(&l, "cancel");
    assert_eq!(mode(&l), LogMode::Standby);
    assert_eq!(l.state().entries.len(), 1);

    // Tag mode: scratch removes the last tag added there; cancel drops them all.
    say(&l, "tag keep");
    say(&l, "tag");
    say(&l, "one");
    say(&l, "two");
    say(&l, "scratch that");
    assert_eq!(l.state().tags, vec!["keep", "one"]);
    say(&l, "cancel");
    assert_eq!(l.state().tags, vec!["keep"]);

    assert!(matches!(&say(&l, "undo")[..], [LogEvent::Undone { .. }]));
    assert!(l.state().entries.is_empty());
    assert!(matches!(&say(&l, "undo")[..], [LogEvent::NothingToUndo]));
}

#[test]
fn snap_burst_and_cancel_inside_note() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    let l = start(&v);
    l.add_photo(JPEG.to_vec()).unwrap();
    say(&l, "first");
    // "snap" while annotating closes this snap; the next photo is a new entry.
    say(&l, "snap");
    l.add_photo(JPEG2.to_vec()).unwrap();
    let st = l.state();
    assert_eq!(st.entries.len(), 2);
    assert_eq!(st.mode, LogMode::Annotate);
    assert_eq!(st.return_mode, LogMode::Standby);

    say(&l, "end annotation");
    say(&l, "end markup");
    say(&l, "note");
    say(&l, "inside");
    l.add_photo(JPEG.to_vec()).unwrap();
    say(&l, "cancel");
    assert_eq!(
        mode(&l),
        LogMode::Note,
        "cancel a nested snap returns to the note"
    );
    let st = l.state();
    assert_eq!(st.entries[2].blocks.len(), 1, "photo removed from the note");
}

#[test]
fn resume_after_restart_and_one_open_log_per_vault() {
    let d = tempfile::tempdir().unwrap();
    {
        let v = new_vault(d.path());
        let l = start(&v);
        say(&l, "note");
        say(&l, "half a thought");
        assert!(matches!(
            LogSession::start(v.clone(), "deck-rebuild".into(), default_voice_phrases()),
            Err(CoreError::LogOpen { .. })
        ));
        assert!(matches!(
            v.sync(GitCredentials::default(), None),
            Err(CoreError::LogOpen { .. })
        ));
    }
    let v = Vault::open(s(d.path()), author()).unwrap();
    let open = v.open_log().expect("open log survives restart");
    assert_eq!(open.project, "deck-rebuild");
    let l = LogSession::resume(v.clone(), default_voice_phrases()).unwrap();
    assert_eq!(mode(&l), LogMode::Note);
    say(&l, "and the rest of it, end log");
    let msg = &git_log(d.path())[0];
    assert!(
        msg.starts_with("deck-rebuild: Half a thought. And the rest of it."),
        "{msg}"
    );
}

#[test]
fn empty_log_commits_nothing_and_discard_cleans_up() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    let n = git_log(d.path()).len();
    let l = start(&v);
    let path = l.state().path;
    assert!(d.path().join(&path).exists());
    assert!(l.end_log().unwrap().is_none());
    assert!(!d.path().join(&path).exists());
    assert!(v.open_log().is_none());
    assert_eq!(git_log(d.path()).len(), n);

    let l = start(&v);
    say(&l, "note");
    say(&l, "never mind");
    l.discard().unwrap();
    assert!(v.open_log().is_none());
    assert!(workdir_clean(d.path()));
}

#[test]
fn edit_reentry_appends_and_commits_again() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    let l = start(&v);
    say(&l, "note");
    say(&l, "original note, end log");
    let path = v.list_logs(None, 0)[0].path.clone();

    // Unchanged edit: no commit.
    let n = git_log(d.path()).len();
    let e = LogSession::edit(v.clone(), path.clone(), default_voice_phrases()).unwrap();
    assert!(e.state().editing);
    assert!(e.end_log().unwrap().is_none());
    assert_eq!(git_log(d.path()).len(), n);
    assert!(workdir_clean(d.path()));

    let e = LogSession::edit(v.clone(), path.clone(), default_voice_phrases()).unwrap();
    assert_eq!(e.state().entries.len(), 1);
    say(&e, "note");
    say(&e, "added later, end log");
    let msg = &git_log(d.path())[0];
    assert!(
        msg.starts_with("deck-rebuild: edit Original note."),
        "{msg}"
    );
    assert!(msg.contains("FieldLog-Edit: "));
    let view = v.read_log(path.clone()).unwrap();
    assert_eq!(view.entries.len(), 2);

    // Touch edits.
    let e = LogSession::edit(v.clone(), path.clone(), default_voice_phrases()).unwrap();
    e.edit_text(0, 0, "Corrected note.".into()).unwrap();
    e.delete_entry(1).unwrap();
    e.end_log().unwrap().unwrap();
    let view = v.read_log(path.clone()).unwrap();
    assert_eq!(view.entries.len(), 1);
    assert!(
        matches!(&view.entries[0].blocks[0], Block::Text { text } if text == "Corrected note.")
    );

    // Discarding an edit restores the committed file.
    let e = LogSession::edit(v.clone(), path.clone(), default_voice_phrases()).unwrap();
    e.edit_text(0, 0, "Scribble".into()).unwrap();
    e.discard().unwrap();
    assert!(workdir_clean(d.path()));

    // Deleting every entry and ending removes the log.
    let e = LogSession::edit(v.clone(), path.clone(), default_voice_phrases()).unwrap();
    e.delete_entry(0).unwrap();
    e.end_log().unwrap().unwrap();
    assert!(v.list_logs(None, 0).is_empty());
    assert!(git_log(d.path())[0].contains("FieldLog-Revert: "));
}

#[test]
fn revert_log_removes_it_in_a_new_commit() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    let l = start(&v);
    say(&l, "note");
    say(&l, "short lived, end log");
    let path = v.list_logs(None, 0)[0].path.clone();
    v.revert_log(path.clone()).unwrap();
    assert!(!d.path().join(&path).exists());
    assert!(v.list_logs(None, 0).is_empty());
    assert!(workdir_clean(d.path()));
    let msgs = git_log(d.path());
    assert!(msgs[0].starts_with("deck-rebuild: remove Short lived."));
    assert!(msgs[1].contains("FieldLog-Log: "), "history keeps the log");
}

#[test]
fn shared_items_are_one_note_logs() {
    let d = tempfile::tempdir().unwrap();
    let v = new_vault(d.path());
    let info = commit_shared(
        v.clone(),
        "deck-rebuild".into(),
        Some("https://example.com/hangers".into()),
        vec![JPEG.to_vec()],
        vec!["Receipts".into()],
    )
    .unwrap();
    let view = v.read_log(info.entry.path).unwrap();
    assert_eq!(view.source.as_deref(), Some("share"));
    assert_eq!(view.tags, vec!["receipts"]);
    assert_eq!(view.entries.len(), 1);
    assert_eq!(view.entries[0].blocks.len(), 2);
    assert!(workdir_clean(d.path()));
}

#[test]
fn same_log_edited_on_two_devices_keeps_both() {
    let d = tempfile::tempdir().unwrap();
    let bare = d.path().join("remote.git");
    git2::Repository::init_bare(&bare).unwrap();
    let url = s(&bare);
    let creds = GitCredentials::default();

    let a = Vault::clone_remote(
        url.clone(),
        s(&d.path().join("a")),
        "Home".into(),
        creds.clone(),
        author(),
    )
    .unwrap();
    a.set_device_name("Pixel 8".into());
    a.create_project("Deck".into()).unwrap();
    let l = LogSession::start(a.clone(), "deck".into(), default_voice_phrases()).unwrap();
    say(&l, "note");
    say(&l, "shared start, end log");
    a.sync(creds.clone(), None).unwrap();

    let b_dir = d.path().join("b");
    let b = Vault::clone_remote(url, s(&b_dir), "x".into(), creds.clone(), author()).unwrap();
    b.set_device_name("Tablet".into());
    let path = b.list_logs(None, 0)[0].path.clone();

    for (vault, text) in [(&a, "from a"), (&b, "from b")] {
        let e = LogSession::edit(vault.clone(), path.clone(), default_voice_phrases()).unwrap();
        say(&e, "note");
        say(&e, &format!("{text}, end log"));
    }
    assert!(a.sync(creds.clone(), None).unwrap().pushed);
    let r = b.sync(creds.clone(), None).unwrap();
    assert!(r.merged && r.pushed);
    assert_eq!(r.conflict_copies.len(), 1);
    let copy = &r.conflict_copies[0];
    assert!(copy.contains(".conflict-tablet-"), "{copy}");

    let logs = b.list_logs(None, 0);
    assert_eq!(logs.len(), 2);
    let original = b.read_log(path.clone()).unwrap();
    let mine = b.read_log(copy.clone()).unwrap();
    assert!(!original.conflict && mine.conflict);
    let text_of = |v: &fieldlog_core::LogView| {
        v.entries
            .iter()
            .flat_map(|e| e.blocks.iter())
            .filter_map(|b| match b {
                Block::Text { text } => Some(text.clone()),
                _ => None,
            })
            .collect::<Vec<_>>()
            .join(" ")
    };
    assert!(
        text_of(&original).contains("From a."),
        "remote version at the path"
    );
    assert!(
        text_of(&mine).contains("From b."),
        "local version kept as the copy"
    );
    assert!(workdir_clean(&b_dir));
    // Conflict copies don't double-count in the project list.
    assert_eq!(b.projects().unwrap()[0].entry_count, 1);
}
