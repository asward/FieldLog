//! Session mode: a sticky project, a draft that fills from narration and
//! photos, and entries that commit themselves on "next", a tap, or silence.
//! The draft is saved to `.fieldlog/drafts/` after every change, so nothing
//! is lost if the app is killed mid-entry.

use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};

use chrono::Local;
use serde::{Deserialize, Serialize};

use crate::error::{CoreError, Result};
use crate::format::{self, Block, LOCAL_DIR};
use crate::vault::{CommitInfo, EntrySummary, Vault};
use crate::voice::{self, Command, VoiceItem};

#[derive(Debug, Clone, uniffi::Record)]
pub struct SessionSettings {
    /// Spoken prefix required before commands, e.g. "log". `None` = bare
    /// commands, recognised only as a whole utterance.
    pub command_prefix: Option<String>,
    /// Commit the draft after this many seconds without speech. 0 = off.
    /// The platform owns the timer and calls `on_silence`.
    pub silence_timeout_secs: u32,
}

impl Default for SessionSettings {
    fn default() -> Self {
        SessionSettings {
            command_prefix: None,
            silence_timeout_secs: 30,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct Draft {
    started: Option<String>,
    blocks: Vec<Block>,
    tags: Vec<String>,
    source: Option<String>,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum SessionEvent {
    TextAdded {
        text: String,
    },
    /// "snap": the platform should capture a photo and call `add_photo`.
    PhotoRequested,
    PhotoAdded {
        block: Block,
    },
    Committed {
        info: CommitInfo,
    },
    NothingToCommit,
    Scratched {
        removed: String,
    },
    NothingToScratch,
    Undone {
        entry: EntrySummary,
    },
    NothingToUndo,
    Tagged {
        tag: String,
    },
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SessionState {
    pub project: String,
    pub blocks: Vec<Block>,
    pub tags: Vec<String>,
    /// RFC 3339 time the current draft started, if it has content.
    pub started: Option<String>,
    pub last_commit: Option<EntrySummary>,
    pub settings: SessionSettings,
}

struct Inner {
    draft: Draft,
    last_commit: Option<EntrySummary>,
    settings: SessionSettings,
}

#[derive(uniffi::Object)]
pub struct Session {
    vault: Arc<Vault>,
    project: String,
    inner: Mutex<Inner>,
}

/// Remove the final sentence of `text`. Returns (remaining, removed).
fn split_last_sentence(text: &str) -> (String, String) {
    let t = text.trim_end();
    let body = t.trim_end_matches(['.', '!', '?', '…']);
    let cut = body
        .char_indices()
        .filter(|(_, c)| matches!(c, '.' | '!' | '?' | '…'))
        .map(|(i, c)| i + c.len_utf8())
        .rfind(|&i| body[i..].starts_with(char::is_whitespace));
    match cut {
        Some(i) => (t[..i].trim_end().to_string(), t[i..].trim().to_string()),
        None => (String::new(), t.to_string()),
    }
}

impl Session {
    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn draft_path(vault: &Vault, project: &str) -> PathBuf {
        vault
            .root_path()
            .join(LOCAL_DIR)
            .join("drafts")
            .join(format!("{project}.json"))
    }

    fn save(&self, inner: &Inner) -> Result<()> {
        let p = Self::draft_path(&self.vault, &self.project);
        if inner.draft.blocks.is_empty() && inner.draft.tags.is_empty() {
            let _ = fs::remove_file(&p);
            return Ok(());
        }
        fs::create_dir_all(p.parent().unwrap())?;
        let tmp = p.with_extension("tmp");
        fs::write(&tmp, serde_json::to_vec(&inner.draft)?)?;
        fs::rename(tmp, p)?;
        Ok(())
    }

    fn touch_started(d: &mut Draft) {
        if d.started.is_none() {
            d.started = Some(Local::now().fixed_offset().to_rfc3339());
        }
    }

    fn add_text_locked(&self, inner: &mut Inner, text: &str) -> Result<SessionEvent> {
        let t = format::tidy_sentence(text);
        if t.is_empty() {
            return Ok(SessionEvent::NothingToScratch);
        }
        Self::touch_started(&mut inner.draft);
        inner.draft.blocks.push(Block::Text { text: t.clone() });
        self.save(inner)?;
        Ok(SessionEvent::TextAdded { text: t })
    }

    fn commit_locked(&self, inner: &mut Inner) -> Result<SessionEvent> {
        let has_content = inner.draft.blocks.iter().any(|b| match b {
            Block::Text { text } => !text.trim().is_empty(),
            Block::Photo { .. } => true,
        });
        if !has_content {
            return Ok(SessionEvent::NothingToCommit);
        }
        let d = inner.draft.clone();
        let info = self.vault.commit_entry(
            self.project.clone(),
            d.blocks,
            d.tags,
            Some(d.source.unwrap_or_else(|| "session".into())),
            d.started,
        )?;
        inner.draft = Draft::default();
        inner.last_commit = Some(info.entry.clone());
        self.save(inner)?;
        Ok(SessionEvent::Committed { info })
    }

    fn apply_locked(&self, inner: &mut Inner, command: Command) -> Result<SessionEvent> {
        match command {
            Command::Snap => Ok(SessionEvent::PhotoRequested),
            Command::Next => self.commit_locked(inner),
            Command::ScratchThat => {
                let idx = inner
                    .draft
                    .blocks
                    .iter()
                    .rposition(|b| matches!(b, Block::Text { .. }));
                let Some(i) = idx else {
                    return Ok(SessionEvent::NothingToScratch);
                };
                let Block::Text { text } = inner.draft.blocks[i].clone() else {
                    unreachable!()
                };
                let (rest, removed) = split_last_sentence(&text);
                if rest.is_empty() {
                    inner.draft.blocks.remove(i);
                } else {
                    inner.draft.blocks[i] = Block::Text { text: rest };
                }
                if inner.draft.blocks.is_empty() {
                    inner.draft.started = None;
                }
                self.save(inner)?;
                Ok(SessionEvent::Scratched { removed })
            }
            Command::Undo => match self.vault.undo_last_entry() {
                Ok(entry) => {
                    if inner.last_commit.as_ref().map(|e| &e.id) == Some(&entry.id) {
                        inner.last_commit = None;
                    }
                    Ok(SessionEvent::Undone { entry })
                }
                Err(CoreError::NothingToUndo) => Ok(SessionEvent::NothingToUndo),
                Err(e) => Err(e),
            },
            Command::Tag { tag } => {
                let tag = format::slugify(&tag);
                if !tag.is_empty() && !inner.draft.tags.contains(&tag) {
                    inner.draft.tags.push(tag.clone());
                    self.save(inner)?;
                }
                Ok(SessionEvent::Tagged { tag })
            }
        }
    }
}

#[uniffi::export]
impl Session {
    /// Start (or resume) a session on `project`. A draft left over from a
    /// previous run is restored.
    #[uniffi::constructor]
    pub fn new(vault: Arc<Vault>, project: String, settings: SessionSettings) -> Result<Arc<Self>> {
        if !vault.root_path().join(&project).is_dir() {
            return Err(CoreError::NotFound {
                msg: format!("project {project}"),
            });
        }
        let draft = fs::read(Self::draft_path(&vault, &project))
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        Ok(Arc::new(Session {
            vault,
            project,
            inner: Mutex::new(Inner {
                draft,
                last_commit: None,
                settings,
            }),
        }))
    }

    pub fn project(&self) -> String {
        self.project.clone()
    }

    pub fn set_settings(&self, settings: SessionSettings) {
        self.lock().settings = settings;
    }

    pub fn state(&self) -> SessionState {
        let i = self.lock();
        SessionState {
            project: self.project.clone(),
            blocks: i.draft.blocks.clone(),
            tags: i.draft.tags.clone(),
            started: i.draft.started.clone(),
            last_commit: i.last_commit.clone(),
            settings: i.settings.clone(),
        }
    }

    /// Feed one recognised utterance. Dictation is appended; commands run.
    pub fn handle_utterance(&self, utterance: String) -> Result<Vec<SessionEvent>> {
        let mut inner = self.lock();
        let items = voice::parse_utterance(utterance, inner.settings.command_prefix.clone());
        let mut events = Vec::new();
        for item in items {
            events.push(match item {
                VoiceItem::Dictation { text } => self.add_text_locked(&mut inner, &text)?,
                VoiceItem::Command { command } => self.apply_locked(&mut inner, command)?,
            });
        }
        Ok(events)
    }

    /// Touch backup for any voice command.
    pub fn apply(&self, command: Command) -> Result<SessionEvent> {
        let mut inner = self.lock();
        self.apply_locked(&mut inner, command)
    }

    /// Typed text (keyboard fallback). Not parsed for commands.
    pub fn add_text(&self, text: String) -> Result<SessionEvent> {
        let mut inner = self.lock();
        self.add_text_locked(&mut inner, &text)
    }

    /// Place a photo at the current point in the narration.
    pub fn add_photo(&self, bytes: Vec<u8>) -> Result<SessionEvent> {
        let block = self.vault.add_photo(bytes)?;
        let mut inner = self.lock();
        Self::touch_started(&mut inner.draft);
        inner.draft.blocks.push(block.clone());
        self.save(&inner)?;
        Ok(SessionEvent::PhotoAdded { block })
    }

    /// "next" / tap: commit the draft as an entry.
    pub fn commit(&self) -> Result<SessionEvent> {
        let mut inner = self.lock();
        self.commit_locked(&mut inner)
    }

    /// The platform's silence timer fired.
    pub fn on_silence(&self) -> Result<SessionEvent> {
        let mut inner = self.lock();
        if inner.settings.silence_timeout_secs == 0 {
            return Ok(SessionEvent::NothingToCommit);
        }
        self.commit_locked(&mut inner)
    }

    /// Throw away the current draft.
    pub fn discard(&self) -> Result<()> {
        let mut inner = self.lock();
        inner.draft = Draft::default();
        self.save(&inner)
    }
}

/// Commit a single shared item (text, link or images) straight to a project.
/// Used by the share sheet; never touches the session draft.
#[uniffi::export]
pub fn commit_shared(
    vault: Arc<Vault>,
    project: String,
    text: Option<String>,
    images: Vec<Vec<u8>>,
    tags: Vec<String>,
) -> Result<CommitInfo> {
    let mut blocks = Vec::new();
    if let Some(t) = text.as_deref().map(str::trim).filter(|t| !t.is_empty()) {
        // Keep shared text verbatim (links, quotes); no sentence tidying.
        blocks.push(Block::Text {
            text: t.to_string(),
        });
    }
    for img in images {
        blocks.push(vault.add_photo(img)?);
    }
    vault.commit_entry(project, blocks, tags, Some("share".into()), None)
}

#[cfg(test)]
mod tests {
    use super::split_last_sentence;

    #[test]
    fn last_sentence() {
        assert_eq!(
            split_last_sentence("One. Two? Three."),
            ("One. Two?".into(), "Three.".into())
        );
        assert_eq!(split_last_sentence("Only."), ("".into(), "Only.".into()));
        assert_eq!(
            split_last_sentence("v1.2 is out. Yes"),
            ("v1.2 is out.".into(), "Yes".into())
        );
    }
}
