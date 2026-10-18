//! The log state machine (spec §3) and its working-tree drafting (§4).
//!
//! A log runs from New log to `end log`. Nothing is committed before then,
//! but the log file is rewritten in the working tree after every change and
//! the machine's state is kept in `.fieldlog/open-log.json`, so a killed app
//! can resume. One log can be open per vault; background sync waits for it.
//!
//! The machine is pure: utterances and commands in, events out. The platform
//! renders state, captures photos when asked, and plays feedback.

use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};

use serde::{Deserialize, Serialize};

use crate::error::{invalid, CoreError, Result};
use crate::format::{self, Block, FrontMatter, LOCAL_DIR};
use crate::log::{self, EntryKind, LogEntry};
use crate::vault::{CommitInfo, Vault};
use crate::voice::{self, LogCommand, Parsed, VoicePhrases};

pub(crate) const OPEN_LOG_FILE: &str = "open-log.json";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum LogMode {
    /// Listening for commands only.
    Standby,
    /// Dictation becomes a note.
    Note,
    /// Each utterance becomes a tag.
    Tag,
    /// Dictation annotates the photo just taken.
    Annotate,
    /// Dictation becomes markup suggestions for that photo.
    Markup,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum LogEvent {
    ModeChanged {
        mode: LogMode,
    },
    /// "snap": capture a photo and call `add_photo` (or ignore it if the
    /// camera isn't available; the mode doesn't change until a photo lands).
    PhotoRequested,
    PhotoAdded {
        block: Block,
    },
    TextAdded {
        text: String,
    },
    AnnotationAdded {
        text: String,
    },
    MarkupAdded {
        line: String,
    },
    Tagged {
        tags: Vec<String>,
    },
    Scratched {
        removed: String,
    },
    NothingToScratch,
    /// The open note or snap was discarded.
    Cancelled,
    Undone {
        summary: String,
    },
    NothingToUndo,
    /// Dictation in standby; not recorded.
    Ignored {
        text: String,
    },
    /// A command said in a mode that doesn't accept it.
    NotAllowed {
        command: LogCommand,
        allowed: Vec<LogCommand>,
    },
    /// `end log`. `commit` is `None` when there was nothing to save.
    LogEnded {
        commit: Option<CommitInfo>,
    },
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LogState {
    pub project: String,
    /// Log file, relative to the vault root.
    pub path: String,
    pub id: String,
    /// Re-opened a committed log.
    pub editing: bool,
    pub mode: LogMode,
    /// Where ANNOTATE/MARKUP return to: STANDBY or NOTE.
    pub return_mode: LogMode,
    pub started: String,
    pub tags: Vec<String>,
    pub entries: Vec<LogEntry>,
    /// Index of the note or snap being recorded, if any.
    pub current_entry: Option<u32>,
    pub allowed: Vec<LogCommand>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct OpenLogInfo {
    pub project: String,
    pub path: String,
    pub editing: bool,
    pub started: String,
    pub entry_count: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Draft {
    path: String,
    editing: bool,
    /// Front matter as YAML, so unknown keys survive the JSON round trip.
    front_matter: String,
    entries: Vec<LogEntry>,
    mode: LogMode,
    return_mode: LogMode,
    current_note: Option<usize>,
    /// (entry, block) of the photo being annotated.
    current_photo: Option<(usize, usize)>,
    /// Tags added since entering TAG mode (for scratch and cancel).
    tag_session: Vec<String>,
}

struct Inner {
    draft: Draft,
    fm: FrontMatter,
    phrases: VoicePhrases,
    /// Set after `end log` or `discard`; the object is finished.
    closed: bool,
}

#[derive(uniffi::Object)]
pub struct LogSession {
    vault: Arc<Vault>,
    inner: Mutex<Inner>,
}

fn now() -> String {
    log::now_secs().to_rfc3339()
}

fn draft_path(vault: &Vault) -> PathBuf {
    vault.root_path().join(LOCAL_DIR).join(OPEN_LOG_FILE)
}

fn read_draft(vault: &Vault) -> Option<Draft> {
    let bytes = fs::read(draft_path(vault)).ok()?;
    serde_json::from_slice(&bytes).ok()
}

/// The open log in this vault, if any.
pub(crate) fn open_log_info(vault: &Vault) -> Option<OpenLogInfo> {
    let d = read_draft(vault)?;
    let fm: FrontMatter = serde_norway::from_str(&d.front_matter).ok()?;
    Some(OpenLogInfo {
        project: fm.project,
        path: d.path,
        editing: d.editing,
        started: fm.started.to_rfc3339(),
        entry_count: d.entries.len() as u32,
    })
}

impl LogSession {
    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn from_parts(vault: Arc<Vault>, draft: Draft, phrases: VoicePhrases) -> Result<Arc<Self>> {
        let fm: FrontMatter = serde_norway::from_str(&draft.front_matter)?;
        let s = Arc::new(LogSession {
            vault,
            inner: Mutex::new(Inner {
                draft,
                fm,
                phrases,
                closed: false,
            }),
        });
        Ok(s)
    }

    /// Write the draft state and the log file. Called after every change.
    fn save(&self, inner: &mut Inner) -> Result<()> {
        if inner.closed {
            return Err(invalid("this log has ended"));
        }
        inner.fm.tags.dedup();
        inner.fm.photos = log::photos(&inner.draft.entries)
            .into_iter()
            .map(|p| p.sha256)
            .collect();
        inner.draft.front_matter = serde_norway::to_string(&inner.fm)?;
        let root = self.vault.root_path();
        let local = root.join(LOCAL_DIR);
        fs::create_dir_all(&local)?;
        let tmp = local.join("open-log.tmp");
        fs::write(&tmp, serde_json::to_vec(&inner.draft)?)?;
        fs::rename(&tmp, local.join(OPEN_LOG_FILE))?;

        let abs = root.join(&inner.draft.path);
        fs::create_dir_all(abs.parent().unwrap())?;
        let body = log::render_body(&inner.fm, &inner.draft.entries);
        fs::write(&abs, format::render(&inner.fm, &body)?)?;
        Ok(())
    }

    fn clear_draft(&self) {
        let _ = fs::remove_file(draft_path(&self.vault));
    }

    fn set_mode(inner: &mut Inner, mode: LogMode, events: &mut Vec<LogEvent>) {
        if inner.draft.mode != mode {
            inner.draft.mode = mode;
            events.push(LogEvent::ModeChanged { mode });
        }
    }

    /// Close the note being recorded; drop it if nothing was said.
    fn close_note(inner: &mut Inner) {
        if let Some(i) = inner.draft.current_note.take() {
            if inner.draft.entries.get(i).is_some_and(|e| e.is_empty()) {
                inner.draft.entries.remove(i);
            }
        }
    }

    fn add_tags(inner: &mut Inner, tags: Vec<String>, events: &mut Vec<LogEvent>) {
        let mut added = Vec::new();
        for t in tags {
            let t = format::slugify(&t);
            if t.is_empty() {
                continue;
            }
            if !inner.fm.tags.contains(&t) {
                inner.fm.tags.push(t.clone());
                if inner.draft.mode == LogMode::Tag {
                    inner.draft.tag_session.push(t.clone());
                }
            }
            added.push(t);
        }
        if !added.is_empty() {
            events.push(LogEvent::Tagged { tags: added });
        }
    }

    fn dictate(inner: &mut Inner, text: &str, events: &mut Vec<LogEvent>) {
        match inner.draft.mode {
            LogMode::Standby => events.push(LogEvent::Ignored {
                text: text.to_string(),
            }),
            LogMode::Note => {
                let t = format::tidy_sentence(text);
                if t.is_empty() {
                    return;
                }
                let i = match inner.draft.current_note {
                    Some(i) => i,
                    None => {
                        // Defensive: NOTE mode without a note entry.
                        inner.draft.entries.push(LogEntry {
                            kind: EntryKind::Note,
                            time: now(),
                            blocks: vec![],
                        });
                        let i = inner.draft.entries.len() - 1;
                        inner.draft.current_note = Some(i);
                        i
                    }
                };
                inner.draft.entries[i]
                    .blocks
                    .push(Block::Text { text: t.clone() });
                events.push(LogEvent::TextAdded { text: t });
            }
            LogMode::Tag => {
                let tags = voice::split_tags(text);
                Self::add_tags(inner, tags, events);
            }
            LogMode::Annotate => {
                let t = format::tidy_sentence(text);
                if t.is_empty() {
                    return;
                }
                if let Some(Block::Photo { annotation, .. }) = Self::current_photo(inner) {
                    if annotation.trim().is_empty() {
                        *annotation = t.clone();
                    } else {
                        annotation.push(' ');
                        annotation.push_str(&t);
                    }
                    events.push(LogEvent::AnnotationAdded { text: t });
                }
            }
            LogMode::Markup => {
                let line = text.trim().trim_end_matches('.').trim().to_string();
                if line.is_empty() {
                    return;
                }
                if let Some(Block::Photo { markup, .. }) = Self::current_photo(inner) {
                    markup.push(line.clone());
                    events.push(LogEvent::MarkupAdded { line });
                }
            }
        }
    }

    fn current_photo(inner: &mut Inner) -> Option<&mut Block> {
        let (e, b) = inner.draft.current_photo?;
        inner.draft.entries.get_mut(e)?.blocks.get_mut(b)
    }

    fn scratch(inner: &mut Inner, events: &mut Vec<LogEvent>) {
        let removed = match inner.draft.mode {
            LogMode::Note => inner.draft.current_note.and_then(|i| {
                let blocks = &mut inner.draft.entries.get_mut(i)?.blocks;
                let idx = blocks
                    .iter()
                    .rposition(|b| matches!(b, Block::Text { .. }))?;
                match blocks.remove(idx) {
                    Block::Text { text } => Some(text),
                    _ => None,
                }
            }),
            LogMode::Tag => inner.draft.tag_session.pop().inspect(|t| {
                inner.fm.tags.retain(|x| x != t);
            }),
            LogMode::Annotate => match Self::current_photo(inner) {
                Some(Block::Photo { annotation, .. }) if !annotation.trim().is_empty() => {
                    let (rest, removed) = format::split_last_sentence(annotation);
                    *annotation = rest;
                    Some(removed)
                }
                _ => None,
            },
            LogMode::Markup => match Self::current_photo(inner) {
                Some(Block::Photo { markup, .. }) => markup.pop(),
                _ => None,
            },
            LogMode::Standby => None,
        };
        events.push(match removed {
            Some(removed) => LogEvent::Scratched { removed },
            None => LogEvent::NothingToScratch,
        });
    }

    fn cancel(inner: &mut Inner, events: &mut Vec<LogEvent>) {
        match inner.draft.mode {
            LogMode::Note => {
                if let Some(i) = inner.draft.current_note.take() {
                    if i < inner.draft.entries.len() {
                        inner.draft.entries.remove(i);
                    }
                }
                events.push(LogEvent::Cancelled);
                Self::set_mode(inner, LogMode::Standby, events);
            }
            LogMode::Tag => {
                let session = std::mem::take(&mut inner.draft.tag_session);
                inner.fm.tags.retain(|t| !session.contains(t));
                events.push(LogEvent::Cancelled);
                Self::set_mode(inner, LogMode::Standby, events);
            }
            LogMode::Annotate | LogMode::Markup => {
                // Discard the current snap and go back where it came from.
                if let Some((e, b)) = inner.draft.current_photo.take() {
                    let entry_is_snap = inner
                        .draft
                        .entries
                        .get(e)
                        .is_some_and(|x| x.kind == EntryKind::Snap);
                    if entry_is_snap {
                        inner.draft.entries.remove(e);
                        if let Some(n) = inner.draft.current_note {
                            if n > e {
                                inner.draft.current_note = Some(n - 1);
                            }
                        }
                    } else if let Some(entry) = inner.draft.entries.get_mut(e) {
                        if b < entry.blocks.len() {
                            entry.blocks.remove(b);
                        }
                    }
                }
                events.push(LogEvent::Cancelled);
                let back = inner.draft.return_mode;
                Self::set_mode(inner, back, events);
            }
            LogMode::Standby => {}
        }
    }

    fn apply_locked(&self, inner: &mut Inner, cmd: LogCommand) -> Result<Vec<LogEvent>> {
        if inner.closed {
            return Err(invalid("this log has ended"));
        }
        let mode = inner.draft.mode;
        let mut events = Vec::new();
        if !voice::is_allowed(mode, &cmd) {
            events.push(LogEvent::NotAllowed {
                command: cmd,
                allowed: voice::allowed(mode),
            });
            return Ok(events);
        }
        match cmd {
            LogCommand::Note => {
                inner.draft.entries.push(LogEntry {
                    kind: EntryKind::Note,
                    time: now(),
                    blocks: vec![],
                });
                inner.draft.current_note = Some(inner.draft.entries.len() - 1);
                Self::set_mode(inner, LogMode::Note, &mut events);
            }
            LogCommand::EndNote => {
                Self::close_note(inner);
                Self::set_mode(inner, LogMode::Standby, &mut events);
            }
            LogCommand::Tag => {
                inner.draft.tag_session.clear();
                Self::set_mode(inner, LogMode::Tag, &mut events);
            }
            LogCommand::EndTags => {
                inner.draft.tag_session.clear();
                Self::set_mode(inner, LogMode::Standby, &mut events);
            }
            LogCommand::TagWith { tags } => Self::add_tags(inner, tags, &mut events),
            LogCommand::Snap => events.push(LogEvent::PhotoRequested),
            LogCommand::EndAnnotation => Self::set_mode(inner, LogMode::Markup, &mut events),
            LogCommand::EndMarkup => {
                inner.draft.current_photo = None;
                let back = inner.draft.return_mode;
                Self::set_mode(inner, back, &mut events);
            }
            LogCommand::ScratchThat => Self::scratch(inner, &mut events),
            LogCommand::Cancel => Self::cancel(inner, &mut events),
            LogCommand::Undo => match inner.draft.entries.pop() {
                Some(e) => events.push(LogEvent::Undone {
                    summary: log::summary(std::slice::from_ref(&e), 80),
                }),
                None => events.push(LogEvent::NothingToUndo),
            },
            LogCommand::EndLog => {
                let commit = self.end_locked(inner)?;
                events.push(LogEvent::LogEnded { commit });
                return Ok(events);
            }
        }
        self.save(inner)?;
        Ok(events)
    }

    /// Finish the open entry, then commit (or clean up if nothing changed).
    fn end_locked(&self, inner: &mut Inner) -> Result<Option<CommitInfo>> {
        if inner.closed {
            return Err(invalid("this log has ended"));
        }
        Self::close_note(inner);
        inner.draft.current_photo = None;
        inner.draft.mode = LogMode::Standby;
        let path = inner.draft.path.clone();
        let abs = self.vault.root_path().join(&path);

        if inner.draft.entries.is_empty() && inner.fm.tags.is_empty() {
            if inner.draft.editing {
                // Everything was deleted: remove the log.
                let info = self.vault.remove_log_file(&path, &inner.fm)?;
                self.clear_draft();
                inner.closed = true;
                return Ok(Some(info));
            }
            let _ = fs::remove_file(&abs);
            self.clear_draft();
            inner.closed = true;
            return Ok(None);
        }

        let ended = Some(log::now_secs());
        let last_entry = inner
            .draft
            .entries
            .iter()
            .filter_map(|e| log::parse_time(&e.time))
            .max();
        inner.fm.ended = if inner.draft.editing {
            [inner.fm.ended, last_entry].into_iter().flatten().max()
        } else {
            ended
        };
        self.save(inner)?;
        let info = self.vault.commit_log_file(
            &path,
            &inner.fm,
            &inner.draft.entries,
            inner.draft.editing,
        )?;
        self.clear_draft();
        inner.closed = true;
        Ok(info)
    }
}

#[uniffi::export]
impl LogSession {
    /// Start a new log in `project`. Fails with `LogOpen` if this vault
    /// already has an open log (resume or end that one first).
    #[uniffi::constructor]
    pub fn start(vault: Arc<Vault>, project: String, phrases: VoicePhrases) -> Result<Arc<Self>> {
        if !vault.root_path().join(&project).is_dir() {
            return Err(CoreError::NotFound {
                msg: format!("project {project}"),
            });
        }
        if let Some(open) = open_log_info(&vault) {
            return Err(CoreError::LogOpen {
                msg: format!("a log is already open in {}", open.project),
            });
        }
        let started = log::now_secs();
        let (id, path) = vault.new_log_path(&project, &started);
        let fm = FrontMatter {
            id,
            project,
            started,
            ended: None,
            tags: vec![],
            source: Some("log".into()),
            photos: vec![],
            extra: Default::default(),
        };
        let draft = Draft {
            path,
            editing: false,
            front_matter: serde_norway::to_string(&fm)?,
            entries: vec![],
            mode: LogMode::Standby,
            return_mode: LogMode::Standby,
            current_note: None,
            current_photo: None,
            tag_session: vec![],
        };
        let s = Self::from_parts(vault, draft, phrases)?;
        {
            let mut inner = s.lock();
            s.save(&mut inner)?;
        }
        Ok(s)
    }

    /// Resume the open log after the app was closed or killed.
    #[uniffi::constructor]
    pub fn resume(vault: Arc<Vault>, phrases: VoicePhrases) -> Result<Arc<Self>> {
        let draft = read_draft(&vault).ok_or_else(|| CoreError::NotFound {
            msg: "no open log".into(),
        })?;
        Self::from_parts(vault, draft, phrases)
    }

    /// Re-open a committed log for editing. New entries are appended;
    /// `end log` commits again. Resumes instead if it is already open.
    #[uniffi::constructor]
    pub fn edit(vault: Arc<Vault>, path: String, phrases: VoicePhrases) -> Result<Arc<Self>> {
        if let Some(open) = open_log_info(&vault) {
            if open.path == path {
                return Self::resume(vault, phrases);
            }
            return Err(CoreError::LogOpen {
                msg: format!("a log is already open in {}", open.project),
            });
        }
        let (fm, entries) = vault.load_log(&path)?;
        let draft = Draft {
            path,
            editing: true,
            front_matter: serde_norway::to_string(&fm)?,
            entries,
            mode: LogMode::Standby,
            return_mode: LogMode::Standby,
            current_note: None,
            current_photo: None,
            tag_session: vec![],
        };
        let s = Self::from_parts(vault, draft, phrases)?;
        {
            let mut inner = s.lock();
            s.save(&mut inner)?;
        }
        Ok(s)
    }

    /// True after `end log` or `discard`.
    pub fn is_closed(&self) -> bool {
        self.lock().closed
    }

    pub fn set_phrases(&self, phrases: VoicePhrases) {
        self.lock().phrases = phrases;
    }

    pub fn state(&self) -> LogState {
        let i = self.lock();
        let current = match i.draft.mode {
            LogMode::Note => i.draft.current_note,
            LogMode::Annotate | LogMode::Markup => i.draft.current_photo.map(|(e, _)| e),
            _ => None,
        };
        LogState {
            project: i.fm.project.clone(),
            path: i.draft.path.clone(),
            id: i.fm.id.clone(),
            editing: i.draft.editing,
            mode: i.draft.mode,
            return_mode: i.draft.return_mode,
            started: i.fm.started.to_rfc3339(),
            tags: i.fm.tags.clone(),
            entries: i.draft.entries.clone(),
            current_entry: current.map(|c| c as u32),
            allowed: voice::allowed(i.draft.mode),
        }
    }

    /// Feed one recognised utterance.
    pub fn handle_utterance(&self, utterance: String) -> Result<Vec<LogEvent>> {
        let mut inner = self.lock();
        let parsed = voice::match_log_utterance(&utterance, inner.draft.mode, &inner.phrases);
        let mut events = Vec::new();
        let mut dirty = false;
        for p in parsed {
            match p {
                Parsed::Text(t) => {
                    Self::dictate(&mut inner, &t, &mut events);
                    dirty = true;
                }
                Parsed::Command(cmd) => {
                    let ended = cmd == LogCommand::EndLog;
                    events.extend(self.apply_locked(&mut inner, cmd)?);
                    dirty = !ended;
                    if ended {
                        break;
                    }
                }
                Parsed::NotAllowed(cmd) => events.push(LogEvent::NotAllowed {
                    command: cmd,
                    allowed: voice::allowed(inner.draft.mode),
                }),
            }
        }
        if dirty {
            self.save(&mut inner)?;
        }
        Ok(events)
    }

    /// A command in a partial result that may fire before the final result:
    /// a whole-utterance command allowed in the current mode.
    pub fn peek_command(&self, partial: String) -> Option<LogCommand> {
        let inner = self.lock();
        voice::peek_command(&partial, inner.draft.mode, &inner.phrases)
    }

    /// Touch backup for any command.
    pub fn apply(&self, command: LogCommand) -> Result<Vec<LogEvent>> {
        let mut inner = self.lock();
        self.apply_locked(&mut inner, command)
    }

    /// Typed text, handled like dictation in the current mode.
    pub fn add_text(&self, text: String) -> Result<Vec<LogEvent>> {
        let mut inner = self.lock();
        let mut events = Vec::new();
        Self::dictate(&mut inner, &text, &mut events);
        self.save(&mut inner)?;
        Ok(events)
    }

    /// A photo was captured (after `PhotoRequested`, the snap button, or a
    /// volume key). Enters ANNOTATE for it.
    pub fn add_photo(&self, bytes: Vec<u8>) -> Result<Vec<LogEvent>> {
        if self.lock().closed {
            return Err(invalid("this log has ended"));
        }
        let block = self.vault.add_photo(bytes)?;
        let mut inner = self.lock();
        let mut events = Vec::new();
        let mode = inner.draft.mode;
        let into_note = match mode {
            LogMode::Note => true,
            LogMode::Annotate | LogMode::Markup => inner.draft.return_mode == LogMode::Note,
            LogMode::Standby | LogMode::Tag => false,
        };
        if mode == LogMode::Tag {
            inner.draft.tag_session.clear();
        }
        let note = inner
            .draft
            .current_note
            .filter(|&i| into_note && i < inner.draft.entries.len());
        match note {
            Some(n) => {
                let blocks = &mut inner.draft.entries[n].blocks;
                blocks.push(block.clone());
                let b = blocks.len() - 1;
                inner.draft.current_photo = Some((n, b));
                inner.draft.return_mode = LogMode::Note;
            }
            None => {
                inner.draft.entries.push(LogEntry {
                    kind: EntryKind::Snap,
                    time: now(),
                    blocks: vec![block.clone()],
                });
                inner.draft.current_photo = Some((inner.draft.entries.len() - 1, 0));
                inner.draft.return_mode = LogMode::Standby;
                if mode == LogMode::Note {
                    inner.draft.current_note = None;
                }
            }
        }
        events.push(LogEvent::PhotoAdded { block });
        inner.draft.mode = LogMode::Annotate;
        events.push(LogEvent::ModeChanged {
            mode: LogMode::Annotate,
        });
        self.save(&mut inner)?;
        Ok(events)
    }

    /// `end log` by touch. Same as saying it.
    pub fn end_log(&self) -> Result<Option<CommitInfo>> {
        let mut inner = self.lock();
        self.end_locked(&mut inner)
    }

    /// Throw the open log away. A new log's file is deleted; an edited
    /// log's file goes back to its committed version.
    pub fn discard(&self) -> Result<()> {
        let mut inner = self.lock();
        if inner.closed {
            return Ok(());
        }
        inner.closed = true;
        if inner.draft.editing {
            self.vault.restore_file(&inner.draft.path)?;
        } else {
            let _ = fs::remove_file(self.vault.root_path().join(&inner.draft.path));
        }
        self.clear_draft();
        Ok(())
    }

    // ------------------------------------------------------------ touch edits

    /// Replace a text block. Empty text removes it.
    pub fn edit_text(&self, entry: u32, block: u32, text: String) -> Result<()> {
        let mut inner = self.lock();
        let blocks = &mut inner
            .draft
            .entries
            .get_mut(entry as usize)
            .ok_or_else(|| invalid("no such entry"))?
            .blocks;
        let b = block as usize;
        match blocks.get_mut(b) {
            Some(Block::Text { text: t }) => {
                if text.trim().is_empty() {
                    blocks.remove(b);
                } else {
                    *t = text.trim().to_string();
                }
            }
            _ => return Err(invalid("not a text block")),
        }
        self.save(&mut inner)
    }

    /// Set a photo's annotation and markup.
    pub fn edit_photo(
        &self,
        entry: u32,
        block: u32,
        annotation: String,
        markup: Vec<String>,
    ) -> Result<()> {
        let mut inner = self.lock();
        let blk = inner
            .draft
            .entries
            .get_mut(entry as usize)
            .and_then(|e| e.blocks.get_mut(block as usize));
        match blk {
            Some(Block::Photo {
                annotation: a,
                markup: m,
                ..
            }) => {
                *a = annotation.trim().to_string();
                *m = markup
                    .into_iter()
                    .map(|l| l.trim().to_string())
                    .filter(|l| !l.is_empty())
                    .collect();
            }
            _ => return Err(invalid("not a photo")),
        }
        self.save(&mut inner)
    }

    /// Delete one block (text or photo) from an entry.
    pub fn delete_block(&self, entry: u32, block: u32) -> Result<()> {
        let mut inner = self.lock();
        let e = entry as usize;
        let b = block as usize;
        let blocks = &mut inner
            .draft
            .entries
            .get_mut(e)
            .ok_or_else(|| invalid("no such entry"))?
            .blocks;
        if b >= blocks.len() {
            return Err(invalid("no such block"));
        }
        blocks.remove(b);
        if inner
            .draft
            .current_photo
            .is_some_and(|(pe, pb)| pe == e && pb >= b)
        {
            inner.draft.current_photo = None;
        }
        self.save(&mut inner)
    }

    /// Delete a whole entry. Allowed in STANDBY only.
    pub fn delete_entry(&self, entry: u32) -> Result<()> {
        let mut inner = self.lock();
        if inner.draft.mode != LogMode::Standby {
            return Err(invalid("finish the current note or snap first"));
        }
        let e = entry as usize;
        if e >= inner.draft.entries.len() {
            return Err(invalid("no such entry"));
        }
        inner.draft.entries.remove(e);
        self.save(&mut inner)
    }

    /// Remove a tag from the log.
    pub fn remove_tag(&self, tag: String) -> Result<()> {
        let mut inner = self.lock();
        inner.fm.tags.retain(|t| *t != tag);
        self.save(&mut inner)
    }
}
