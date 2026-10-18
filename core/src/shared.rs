//! Share-sheet items: text, links and images committed straight to a
//! project as a one-note log.

use std::sync::Arc;

use crate::error::Result;
use crate::format::Block;
use crate::vault::{CommitInfo, Vault};

/// Commit a single shared item (text, link or images) straight to a project
/// as a one-note log. Used by the share sheet; never touches an open log.
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
    if blocks.is_empty() {
        return Err(crate::error::invalid("nothing to share"));
    }
    let entry = crate::log::LogEntry {
        kind: crate::log::EntryKind::Note,
        time: crate::log::now_secs().to_rfc3339(),
        blocks,
    };
    vault.commit_new_log(&project, vec![entry], tags, "share")
}
