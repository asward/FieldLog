//! Entry file format: Markdown with YAML front matter. See docs/FORMAT.md.

use std::collections::BTreeMap;

use chrono::{DateTime, Datelike, FixedOffset, Timelike};
use serde::{Deserialize, Serialize};

use crate::error::{CoreError, Result};

/// Directory (at the vault root) holding content-addressed images.
pub const ASSETS_DIR: &str = "assets";
/// Local, never-committed state (drafts, pending uploads).
pub const LOCAL_DIR: &str = ".fieldlog";
/// Vault configuration file at the vault root.
pub const CONFIG_FILE: &str = "fieldlog.toml";
/// Project description file inside each project directory.
pub const PROJECT_FILE: &str = "README.md";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FrontMatter {
    pub id: String,
    pub project: String,
    pub created: DateTime<FixedOffset>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub tags: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source: Option<String>,
    /// sha256 of every photo referenced in the body, in order.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub photos: Vec<String>,
    /// Keys we don't know about are preserved on rewrite.
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_norway::Value>,
}

/// Split a Markdown file into front matter and body.
pub fn parse(text: &str) -> Result<(FrontMatter, String)> {
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    let normalized;
    let text = if text.contains("\r\n") {
        normalized = text.replace("\r\n", "\n");
        normalized.as_str()
    } else {
        text
    };
    let rest = text
        .strip_prefix("---\n")
        .ok_or_else(|| CoreError::Format {
            msg: "missing front matter".into(),
        })?;
    let (yaml, body) = if let Some(i) = rest.find("\n---\n") {
        (&rest[..i + 1], &rest[i + 5..])
    } else if let Some(stripped) = rest.strip_suffix("\n---") {
        (stripped, "")
    } else if let Some(stripped) = rest.strip_prefix("---\n") {
        ("", stripped)
    } else {
        return Err(CoreError::Format {
            msg: "unterminated front matter".into(),
        });
    };
    let fm: FrontMatter = serde_norway::from_str(yaml)?;
    Ok((fm, body.trim_start_matches('\n').trim_end().to_string()))
}

pub fn render(fm: &FrontMatter, body: &str) -> Result<String> {
    let yaml = serde_norway::to_string(fm)?;
    let body = body.trim();
    if body.is_empty() {
        Ok(format!("---\n{yaml}---\n"))
    } else {
        Ok(format!("---\n{yaml}---\n\n{body}\n"))
    }
}

/// `deck/2026/10/2026-10-17-204512-a1b2.md`
pub fn entry_rel_path(project: &str, created: &DateTime<FixedOffset>, suffix: &str) -> String {
    format!(
        "{p}/{y:04}/{m:02}/{y:04}-{m:02}-{d:02}-{hh:02}{mm:02}{ss:02}-{suffix}.md",
        p = project,
        y = created.year(),
        m = created.month(),
        d = created.day(),
        hh = created.hour(),
        mm = created.minute(),
        ss = created.second(),
    )
}

pub fn entry_id(created: &DateTime<FixedOffset>, suffix: &str) -> String {
    format!("{}-{suffix}", created.format("%Y%m%dT%H%M%S"))
}

/// `assets/ab/abcdef….jpg`
pub fn asset_rel_path(sha256: &str, ext: &str) -> String {
    format!("{ASSETS_DIR}/{}/{sha256}.{ext}", &sha256[..2])
}

/// Link from an entry file (three directories deep) to an asset.
pub fn asset_link_from_entry(sha256: &str, ext: &str) -> String {
    format!("../../../{}", asset_rel_path(sha256, ext))
}

/// Lowercase, ASCII-alphanumeric and single hyphens. Empty if nothing usable.
pub fn slugify(s: &str) -> String {
    let mut out = String::new();
    let mut dash = false;
    for c in s.trim().chars().flat_map(char::to_lowercase) {
        if c.is_ascii_alphanumeric() {
            out.push(c);
            dash = false;
        } else if !out.is_empty() && !dash {
            out.push('-');
            dash = true;
        }
    }
    while out.ends_with('-') {
        out.pop();
    }
    out
}

/// Capitalise the first letter and add a full stop if the recogniser gave no
/// terminal punctuation.
pub fn tidy_sentence(s: &str) -> String {
    let s = s.trim();
    if s.is_empty() {
        return String::new();
    }
    let mut chars = s.chars();
    let first = chars.next().unwrap();
    let mut out: String = first.to_uppercase().chain(chars).collect();
    if !out.ends_with(['.', '!', '?', ':', ';', '…', ')', '"']) {
        out.push('.');
    }
    out
}

/// One piece of a draft entry, in narration order.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Enum)]
pub enum Block {
    Text { text: String },
    Photo { sha256: String, ext: String },
}

/// Render draft blocks to a Markdown body. Consecutive sentences form a
/// paragraph; each photo sits on its own line where it was taken.
pub fn blocks_to_body(blocks: &[Block]) -> String {
    let mut paras: Vec<String> = Vec::new();
    let mut cur: Vec<String> = Vec::new();
    for b in blocks {
        match b {
            Block::Text { text } => {
                let t = text.trim();
                if !t.is_empty() {
                    cur.push(t.to_string());
                }
            }
            Block::Photo { sha256, ext } => {
                if !cur.is_empty() {
                    paras.push(cur.join(" "));
                    cur.clear();
                }
                paras.push(format!("![]({})", asset_link_from_entry(sha256, ext)));
            }
        }
    }
    if !cur.is_empty() {
        paras.push(cur.join(" "));
    }
    paras.join("\n\n")
}

/// First line of the body that isn't an image, for list views.
pub fn summary_line(body: &str, max_chars: usize) -> String {
    let line = body
        .lines()
        .map(str::trim)
        .find(|l| !l.is_empty() && !l.starts_with("!["))
        .unwrap_or("");
    if line.chars().count() <= max_chars {
        line.to_string()
    } else {
        let mut s: String = line.chars().take(max_chars).collect();
        s.push('…');
        s
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fm() -> FrontMatter {
        FrontMatter {
            id: "20261017T204512-a1b2".into(),
            project: "deck".into(),
            created: DateTime::parse_from_rfc3339("2026-10-17T20:45:12-07:00").unwrap(),
            tags: vec!["framing".into()],
            source: Some("session".into()),
            photos: vec![],
            extra: BTreeMap::new(),
        }
    }

    #[test]
    fn roundtrip() {
        let text = render(&fm(), "Hello there.").unwrap();
        assert!(text.starts_with("---\nid: 20261017T204512-a1b2\n"));
        let (f, b) = parse(&text).unwrap();
        assert_eq!(f, fm());
        assert_eq!(b, "Hello there.");
    }

    #[test]
    fn preserves_unknown_keys_and_crlf() {
        let text = "---\r\nid: x\r\nproject: p\r\ncreated: 2026-01-02T03:04:05+00:00\r\nmood: good\r\n---\r\n\r\nbody\r\n";
        let (f, b) = parse(text).unwrap();
        assert_eq!(b, "body");
        assert_eq!(f.extra.get("mood").and_then(|v| v.as_str()), Some("good"));
        assert!(render(&f, &b).unwrap().contains("mood: good"));
    }

    #[test]
    fn empty_body() {
        let text = render(&fm(), "").unwrap();
        let (_, b) = parse(&text).unwrap();
        assert_eq!(b, "");
    }

    #[test]
    fn paths() {
        let f = fm();
        assert_eq!(
            entry_rel_path("deck", &f.created, "a1b2"),
            "deck/2026/10/2026-10-17-204512-a1b2.md"
        );
        assert_eq!(entry_id(&f.created, "a1b2"), "20261017T204512-a1b2");
        assert_eq!(asset_rel_path("abcdef", "jpg"), "assets/ab/abcdef.jpg");
    }

    #[test]
    fn slugs() {
        assert_eq!(slugify("  Deck Rebuild!! 2026 "), "deck-rebuild-2026");
        assert_eq!(slugify("---"), "");
        assert_eq!(slugify("Über"), "ber");
    }

    #[test]
    fn sentences() {
        assert_eq!(
            tidy_sentence("the joist is cracked"),
            "The joist is cracked."
        );
        assert_eq!(tidy_sentence("Done?"), "Done?");
        assert_eq!(tidy_sentence("  "), "");
    }

    #[test]
    fn body_with_inline_photo() {
        let blocks = vec![
            Block::Text {
                text: "First.".into(),
            },
            Block::Text {
                text: "Second.".into(),
            },
            Block::Photo {
                sha256: "abcd".into(),
                ext: "jpg".into(),
            },
            Block::Text {
                text: "After.".into(),
            },
        ];
        assert_eq!(
            blocks_to_body(&blocks),
            "First. Second.\n\n![](../../../assets/ab/abcd.jpg)\n\nAfter."
        );
        assert_eq!(summary_line(&blocks_to_body(&blocks), 5), "First…");
    }
}
