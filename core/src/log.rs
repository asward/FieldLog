//! Log bodies (v2). One file per log; each entry sits under a heading
//! `## HH:MM:SS Note` or `## HH:MM:SS Snap`, in the log's UTC offset.
//!
//! A photo is an image line, optionally followed by its annotation as a quote
//! and its markup suggestions in a `fieldlog-markup` fence:
//!
//! ````markdown
//! ## 10:41:03 Snap
//!
//! ![](../../../assets/9c/9c04….jpg)
//!
//! > Joist end under the door, soft to a screwdriver.
//!
//! ```fieldlog-markup
//! circle: dark area at joist end
//! ```
//! ````
//!
//! V1 files have no headings; they read as a log with one note.

use chrono::{DateTime, Duration, FixedOffset, Local, NaiveTime, TimeZone, Timelike};
use serde::{Deserialize, Serialize};

use crate::format::{self, Block, FrontMatter, MARKUP_FENCE};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum EntryKind {
    Note,
    Snap,
}

impl EntryKind {
    fn label(self) -> &'static str {
        match self {
            EntryKind::Note => "Note",
            EntryKind::Snap => "Snap",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct LogEntry {
    pub kind: EntryKind,
    /// RFC 3339.
    pub time: String,
    pub blocks: Vec<Block>,
}

impl LogEntry {
    pub fn is_empty(&self) -> bool {
        self.blocks.iter().all(|b| match b {
            Block::Text { text } => text.trim().is_empty(),
            Block::Photo { .. } => false,
        })
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PhotoRef {
    pub sha256: String,
    pub ext: String,
}

/// Every photo in body order.
pub fn photos(entries: &[LogEntry]) -> Vec<PhotoRef> {
    entries
        .iter()
        .flat_map(|e| e.blocks.iter())
        .filter_map(|b| match b {
            Block::Photo { sha256, ext, .. } => Some(PhotoRef {
                sha256: sha256.clone(),
                ext: ext.clone(),
            }),
            _ => None,
        })
        .collect()
}

/// Text for list views: the first run of note text, else the first
/// annotation.
pub fn summary(entries: &[LogEntry], max_chars: usize) -> String {
    let mut run: Vec<&str> = Vec::new();
    'outer: for e in entries {
        for b in &e.blocks {
            match b {
                Block::Text { text } if !text.trim().is_empty() => run.push(text.trim()),
                Block::Photo { .. } if !run.is_empty() => break 'outer,
                _ => {}
            }
        }
        if !run.is_empty() {
            break;
        }
    }
    let text = if run.is_empty() {
        entries
            .iter()
            .flat_map(|e| e.blocks.iter())
            .find_map(|b| match b {
                Block::Photo { annotation, .. } if !annotation.trim().is_empty() => {
                    Some(annotation.clone())
                }
                _ => None,
            })
            .unwrap_or_default()
    } else {
        run.join(" ")
    };
    let flat = text.split_whitespace().collect::<Vec<_>>().join(" ");
    if flat.chars().count() <= max_chars {
        flat
    } else {
        let mut s: String = flat.chars().take(max_chars).collect();
        s.push('…');
        s
    }
}

/// Now, to the second (log times are shown and stored to the second).
pub fn now_secs() -> DateTime<FixedOffset> {
    let n = Local::now().fixed_offset();
    n.with_nanosecond(0).unwrap_or(n)
}

pub fn parse_time(s: &str) -> Option<DateTime<FixedOffset>> {
    DateTime::parse_from_rfc3339(s).ok()
}

pub fn render_body(fm: &FrontMatter, entries: &[LogEntry]) -> String {
    let offset = *fm.started.offset();
    entries
        .iter()
        .map(|e| {
            let t = parse_time(&e.time)
                .map(|t| t.with_timezone(&offset))
                .unwrap_or(fm.started);
            let body = format::blocks_to_body(&e.blocks);
            let head = format!("## {} {}", t.format("%H:%M:%S"), e.kind.label());
            if body.is_empty() {
                head
            } else {
                format!("{head}\n\n{body}")
            }
        })
        .collect::<Vec<_>>()
        .join("\n\n")
}

/// `## HH:MM:SS Note` / `Snap`.
pub fn is_entry_heading(line: &str) -> bool {
    heading(line).is_some()
}

fn heading(line: &str) -> Option<(NaiveTime, EntryKind)> {
    let rest = line.strip_prefix("## ")?.trim();
    let (time, kind) = rest.split_once(' ')?;
    let kind = match kind.trim() {
        "Note" => EntryKind::Note,
        "Snap" => EntryKind::Snap,
        _ => return None,
    };
    let t = NaiveTime::parse_from_str(time, "%H:%M:%S").ok()?;
    Some((t, kind))
}

/// Parse a log body. Entries without a heading (v1 files, or text before
/// the first heading) become a note at the log's start time.
pub fn parse_body(fm: &FrontMatter, body: &str) -> Vec<LogEntry> {
    let offset = *fm.started.offset();
    let mut out: Vec<LogEntry> = Vec::new();
    let mut cur: Option<(EntryKind, DateTime<FixedOffset>)> = None;
    let mut buf: Vec<&str> = Vec::new();
    let mut last = fm.started.with_nanosecond(0).unwrap_or(fm.started);
    let mut in_fence = false;

    let flush =
        |out: &mut Vec<LogEntry>, cur: Option<(EntryKind, DateTime<FixedOffset>)>, buf: &[&str]| {
            let text = buf.join("\n");
            match cur {
                Some((kind, time)) => out.push(LogEntry {
                    kind,
                    time: time.to_rfc3339(),
                    blocks: parse_blocks(&text),
                }),
                None if !text.trim().is_empty() => out.push(LogEntry {
                    kind: EntryKind::Note,
                    time: fm.started.to_rfc3339(),
                    blocks: parse_blocks(&text),
                }),
                None => {}
            }
        };

    for line in body.lines() {
        if line.trim_start().starts_with("```") {
            in_fence = !in_fence;
        }
        if !in_fence {
            if let Some((t, kind)) = heading(line) {
                flush(&mut out, cur, &buf);
                buf.clear();
                // Same calendar day as the previous entry, or later if the
                // log crossed midnight.
                let day = last.date_naive();
                let mut time = offset
                    .from_local_datetime(&day.and_time(t))
                    .single()
                    .unwrap_or(last);
                while time < last {
                    time += Duration::days(1);
                }
                last = time;
                cur = Some((kind, time));
                continue;
            }
        }
        buf.push(line);
    }
    flush(&mut out, cur, &buf);
    out
}

/// Split Markdown into paragraphs at blank lines, keeping fenced blocks whole.
fn paragraphs(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur: Vec<&str> = Vec::new();
    let mut in_fence = false;
    for line in text.lines() {
        if line.trim_start().starts_with("```") {
            in_fence = !in_fence;
        }
        if !in_fence && line.trim().is_empty() {
            if !cur.is_empty() {
                out.push(cur.join("\n"));
                cur.clear();
            }
        } else {
            cur.push(line);
        }
    }
    if !cur.is_empty() {
        out.push(cur.join("\n"));
    }
    out
}

/// `![](../../../assets/ab/abcd….jpg)` → (sha, ext). External images return None.
fn asset_image(para: &str) -> Option<(String, String)> {
    let p = para.trim();
    if p.contains('\n') {
        return None;
    }
    let link = p
        .strip_prefix("![")?
        .split_once("](")?
        .1
        .strip_suffix(')')?;
    let is_asset = link.starts_with("assets/") || link.contains("/assets/");
    if !is_asset {
        return None;
    }
    let name = link.rsplit('/').next()?;
    let (sha, ext) = name.split_once('.')?;
    if sha.len() < 8 || !sha.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    Some((sha.to_string(), ext.to_string()))
}

fn quote_text(para: &str) -> Option<String> {
    let mut out = Vec::new();
    for l in para.lines() {
        let l = l.trim_start();
        let rest = l.strip_prefix('>')?;
        out.push(rest.strip_prefix(' ').unwrap_or(rest).to_string());
    }
    Some(out.join("\n"))
}

fn markup_lines(para: &str) -> Option<Vec<String>> {
    let mut lines = para.lines();
    let first = lines.next()?.trim();
    if first.strip_prefix("```")?.trim() != MARKUP_FENCE {
        return None;
    }
    let rest: Vec<&str> = lines.collect();
    let (last, inner) = rest.split_last()?;
    if last.trim() != "```" {
        return None;
    }
    Some(
        inner
            .iter()
            .map(|l| l.trim().to_string())
            .filter(|l| !l.is_empty())
            .collect(),
    )
}

#[derive(PartialEq)]
enum Prev {
    Nothing,
    Text,
    Photo,
    Annotation,
    Markup,
}

/// Parse an entry's Markdown into blocks. Consecutive paragraphs of text
/// become one text block (joined by a blank line) so rendering round-trips.
pub fn parse_blocks(text: &str) -> Vec<Block> {
    let mut out: Vec<Block> = Vec::new();
    let mut prev = Prev::Nothing;
    for para in paragraphs(text) {
        if let Some((sha, ext)) = asset_image(&para) {
            out.push(Block::photo(sha, ext));
            prev = Prev::Photo;
            continue;
        }
        if prev == Prev::Photo {
            if let Some(q) = quote_text(&para) {
                if let Some(Block::Photo { annotation, .. }) = out.last_mut() {
                    *annotation = q;
                }
                prev = Prev::Annotation;
                continue;
            }
        }
        if prev == Prev::Photo || prev == Prev::Annotation {
            if let Some(lines) = markup_lines(&para) {
                if let Some(Block::Photo { markup, .. }) = out.last_mut() {
                    *markup = lines;
                }
                prev = Prev::Markup;
                continue;
            }
        }
        match (prev, out.last_mut()) {
            (Prev::Text, Some(Block::Text { text })) => {
                text.push_str("\n\n");
                text.push_str(&para);
            }
            _ => out.push(Block::Text { text: para }),
        }
        prev = Prev::Text;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    const SHA1: &str = "3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b";
    const SHA2: &str = "9c04aaaabbbbccccddddeeeeffff000011112222333344445555666677778888";

    fn fm() -> FrontMatter {
        FrontMatter {
            id: "20261018T103512-a1b2".into(),
            project: "deck".into(),
            started: DateTime::parse_from_rfc3339("2026-10-18T10:35:12-07:00").unwrap(),
            ended: None,
            tags: vec![],
            source: Some("log".into()),
            photos: vec![],
            extra: BTreeMap::new(),
        }
    }

    fn t(s: &str) -> Block {
        Block::Text { text: s.into() }
    }

    fn sample() -> Vec<LogEntry> {
        vec![
            LogEntry {
                kind: EntryKind::Note,
                time: "2026-10-18T10:35:12-07:00".into(),
                blocks: vec![
                    t("Pulled the old ledger board."),
                    Block::Photo {
                        sha256: SHA1.into(),
                        ext: "jpg".into(),
                        annotation: "Rot at the bolt.".into(),
                        markup: vec![],
                    },
                    t("Rot goes about two feet in."),
                ],
            },
            LogEntry {
                kind: EntryKind::Snap,
                time: "2026-10-18T10:41:03-07:00".into(),
                blocks: vec![Block::Photo {
                    sha256: SHA2.into(),
                    ext: "jpg".into(),
                    annotation: "Joist end under the door, soft to a screwdriver.".into(),
                    markup: vec![
                        "circle: dark area at joist end".into(),
                        "arrow: water path from door sill".into(),
                    ],
                }],
            },
        ]
    }

    #[test]
    fn render_matches_spec_layout() {
        let body = render_body(&fm(), &sample());
        let expected = format!(
            "## 10:35:12 Note\n\nPulled the old ledger board.\n\n![](../../../assets/3a/{SHA1}.jpg)\n\n\
             > Rot at the bolt.\n\nRot goes about two feet in.\n\n## 10:41:03 Snap\n\n\
             ![](../../../assets/9c/{SHA2}.jpg)\n\n> Joist end under the door, soft to a screwdriver.\n\n\
             ```fieldlog-markup\ncircle: dark area at joist end\narrow: water path from door sill\n```"
        );
        assert_eq!(body, expected);
    }

    #[test]
    fn roundtrip() {
        let entries = sample();
        let body = render_body(&fm(), &entries);
        let parsed = parse_body(&fm(), &body);
        assert_eq!(parsed, entries);
        assert_eq!(render_body(&fm(), &parsed), body);
    }

    #[test]
    fn multi_paragraph_text_roundtrips() {
        let body = "## 10:35:12 Note\n\nFirst para.\n\nSecond para.";
        let parsed = parse_body(&fm(), body);
        assert_eq!(parsed[0].blocks, vec![t("First para.\n\nSecond para.")]);
        assert_eq!(render_body(&fm(), &parsed), body);
    }

    #[test]
    fn v1_body_is_one_note() {
        let body = format!("Pulled the board.\n\n![](../../../assets/3a/{SHA1}.jpg)\n\nRot.");
        let parsed = parse_body(&fm(), &body);
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].kind, EntryKind::Note);
        assert_eq!(parsed[0].time, fm().started.to_rfc3339());
        assert_eq!(
            parsed[0].blocks,
            vec![
                t("Pulled the board."),
                Block::photo(SHA1.into(), "jpg".into()),
                t("Rot.")
            ]
        );
    }

    #[test]
    fn crossing_midnight_moves_to_next_day() {
        let mut f = fm();
        f.started = DateTime::parse_from_rfc3339("2026-10-18T23:58:00-07:00").unwrap();
        let parsed = parse_body(
            &f,
            "## 23:59:00 Note\n\nLate.\n\n## 00:01:30 Note\n\nEarly.",
        );
        assert_eq!(parsed[0].time, "2026-10-18T23:59:00-07:00");
        assert_eq!(parsed[1].time, "2026-10-19T00:01:30-07:00");
    }

    #[test]
    fn quotes_and_fences_away_from_photos_are_text() {
        let body = "## 10:35:12 Note\n\n> a quote\n\n```fieldlog-markup\nx\n```\n\n![](https://example.com/a.png)";
        let parsed = parse_body(&fm(), body);
        assert_eq!(parsed[0].blocks.len(), 1);
        assert!(
            matches!(&parsed[0].blocks[0], Block::Text { text } if text.starts_with("> a quote"))
        );
    }

    #[test]
    fn heading_inside_fence_is_not_an_entry() {
        let body = "## 10:35:12 Note\n\n```\n## 10:36:00 Note\n```";
        assert_eq!(parse_body(&fm(), body).len(), 1);
    }

    #[test]
    fn summaries() {
        assert_eq!(summary(&sample(), 12), "Pulled the o…");
        let snap_only = vec![sample().remove(1)];
        assert_eq!(
            summary(&snap_only, 100),
            "Joist end under the door, soft to a screwdriver."
        );
        assert_eq!(photos(&sample()).len(), 2);
    }
}
