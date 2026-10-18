//! Voice-command parsing. The v1 session parser (`parse_utterance`) is kept
//! until the v1 session engine is removed; v2 logs use `match_log_utterance`.
//!
//! V1: voice-command parsing for session mode.
//!
//! A recognised utterance is either dictation or a command. To keep words
//! like "next" from firing while narrating real work, a command only fires
//! when it is the *whole* utterance. With a command prefix configured (e.g.
//! "log"), bare command words are always dictation and "log next" fires,
//! including at the end of a longer utterance: "the joist is split log snap".

use serde::{Deserialize, Serialize};

use crate::format::slugify;
use crate::logsession::LogMode;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum Command {
    Snap,
    Next,
    ScratchThat,
    Undo,
    Tag { tag: String },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum VoiceItem {
    Dictation { text: String },
    Command { command: Command },
}

fn norm(word: &str) -> String {
    word.chars()
        .filter(|c| c.is_alphanumeric())
        .flat_map(char::to_lowercase)
        .collect()
}

/// Match a complete command phrase (already normalised words).
fn match_command(words: &[String]) -> Option<Command> {
    let w: Vec<&str> = words.iter().map(String::as_str).collect();
    match w.as_slice() {
        ["snap"] | ["snap", "it"] | ["take", "photo"] | ["take", "a", "photo"] => {
            Some(Command::Snap)
        }
        ["next"] | ["next", "entry"] | ["new", "entry"] => Some(Command::Next),
        ["scratch", "that"] | ["strike", "that"] => Some(Command::ScratchThat),
        ["undo"] | ["undo", "that"] => Some(Command::Undo),
        ["tag", rest @ ..] if (1..=4).contains(&rest.len()) => {
            let tag = slugify(&rest.join(" "));
            (!tag.is_empty()).then_some(Command::Tag { tag })
        }
        _ => None,
    }
}

/// Parse one recognised utterance.
#[uniffi::export]
pub fn parse_utterance(utterance: String, command_prefix: Option<String>) -> Vec<VoiceItem> {
    let raw: Vec<&str> = utterance.split_whitespace().collect();
    if raw.is_empty() {
        return vec![];
    }
    let words: Vec<String> = raw.iter().map(|w| norm(w)).collect();
    let prefix: Vec<String> = command_prefix
        .as_deref()
        .unwrap_or("")
        .split_whitespace()
        .map(norm)
        .filter(|w| !w.is_empty())
        .collect();

    if prefix.is_empty() {
        return match match_command(&words) {
            Some(command) => vec![VoiceItem::Command { command }],
            None => vec![VoiceItem::Dictation {
                text: utterance.trim().to_string(),
            }],
        };
    }

    // Find the right-most "<prefix> <command>" that runs to the end.
    let n = prefix.len();
    for start in (0..words.len().saturating_sub(n)).rev() {
        if words[start..start + n] != prefix[..] {
            continue;
        }
        if let Some(command) = match_command(&words[start + n..]) {
            let mut out = Vec::new();
            let before = raw[..start]
                .join(" ")
                .trim_end_matches([',', ' '])
                .to_string();
            if !before.is_empty() {
                out.push(VoiceItem::Dictation { text: before });
            }
            out.push(VoiceItem::Command { command });
            return out;
        }
    }
    vec![VoiceItem::Dictation {
        text: utterance.trim().to_string(),
    }]
}

// ---------------------------------------------------------------- v2 logs

/// A command in the log state machine. Every one has a touch backup.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum LogCommand {
    Note,
    EndNote,
    Tag,
    EndTags,
    Snap,
    EndAnnotation,
    EndMarkup,
    ScratchThat,
    Cancel,
    Undo,
    EndLog,
    /// One-shot "tag pump, valve" from standby.
    TagWith {
        tags: Vec<String>,
    },
}

/// Spoken phrases for each command. Stored on the device; editable in
/// settings. Matching ignores case and punctuation, and singular and plural
/// match ("end annotation" = "end annotations").
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct VoicePhrases {
    pub note: Vec<String>,
    pub end_note: Vec<String>,
    pub tag: Vec<String>,
    pub end_tags: Vec<String>,
    pub snap: Vec<String>,
    pub end_annotation: Vec<String>,
    pub end_markup: Vec<String>,
    pub scratch_that: Vec<String>,
    pub cancel: Vec<String>,
    pub undo: Vec<String>,
    pub end_log: Vec<String>,
}

fn v(items: &[&str]) -> Vec<String> {
    items.iter().map(|s| s.to_string()).collect()
}

impl Default for VoicePhrases {
    fn default() -> Self {
        VoicePhrases {
            note: v(&["note", "start note"]),
            end_note: v(&["end note"]),
            tag: v(&["tag", "start tags"]),
            end_tags: v(&["end tags", "end tagging"]),
            snap: v(&["snap", "take photo"]),
            end_annotation: v(&["end annotation"]),
            end_markup: v(&["end markup"]),
            scratch_that: v(&["scratch that", "strike that"]),
            cancel: v(&["cancel"]),
            undo: v(&["undo"]),
            end_log: v(&["end log"]),
        }
    }
}

#[uniffi::export]
pub fn default_voice_phrases() -> VoicePhrases {
    VoicePhrases::default()
}

impl VoicePhrases {
    /// (command, phrases) in match priority order. Longer phrases first so
    /// "start tags" wins over "tag" + words.
    fn table(&self) -> Vec<(LogCommand, &Vec<String>)> {
        vec![
            (LogCommand::EndLog, &self.end_log),
            (LogCommand::EndNote, &self.end_note),
            (LogCommand::EndTags, &self.end_tags),
            (LogCommand::EndAnnotation, &self.end_annotation),
            (LogCommand::EndMarkup, &self.end_markup),
            (LogCommand::ScratchThat, &self.scratch_that),
            (LogCommand::Cancel, &self.cancel),
            (LogCommand::Undo, &self.undo),
            (LogCommand::Snap, &self.snap),
            (LogCommand::Note, &self.note),
            (LogCommand::Tag, &self.tag),
        ]
    }
}

/// Commands accepted in each mode (spec §3). `TagWith` follows `Tag`.
pub fn allowed(mode: LogMode) -> Vec<LogCommand> {
    use LogCommand::*;
    match mode {
        LogMode::Standby => vec![Note, Tag, Snap, Undo, EndLog],
        LogMode::Note => vec![EndNote, Snap, ScratchThat, Cancel, EndLog],
        LogMode::Tag => vec![EndTags, ScratchThat, Cancel, EndLog],
        LogMode::Annotate => vec![EndAnnotation, Snap, ScratchThat, Cancel, EndLog],
        LogMode::Markup => vec![EndMarkup, Snap, ScratchThat, Cancel, EndLog],
    }
}

pub fn is_allowed(mode: LogMode, cmd: &LogCommand) -> bool {
    match cmd {
        LogCommand::TagWith { .. } => mode == LogMode::Standby,
        c => allowed(mode).contains(c),
    }
}

/// Lowercase, alphanumeric only, singular.
fn norm2(word: &str) -> String {
    let w = norm(word);
    if w.len() > 3 && w.ends_with('s') && !w.ends_with("ss") {
        w[..w.len() - 1].to_string()
    } else {
        w
    }
}

fn words_of(s: &str) -> Vec<String> {
    s.split_whitespace()
        .map(norm2)
        .filter(|w| !w.is_empty())
        .collect()
}

/// Split "pump, valve and gasket" into slugs. One tag per comma or "and".
pub fn split_tags(text: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for part in text.split(',') {
        let words: Vec<&str> = part.split_whitespace().collect();
        let mut cur: Vec<&str> = Vec::new();
        let mut push = |cur: &mut Vec<&str>| {
            let t = slugify(&cur.join(" "));
            if !t.is_empty() && !out.contains(&t) {
                out.push(t);
            }
            cur.clear();
        };
        for w in words {
            if norm(w) == "and" {
                push(&mut cur);
            } else {
                cur.push(w);
            }
        }
        push(&mut cur);
    }
    out
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Parsed {
    Text(String),
    Command(LogCommand),
    /// A command phrase said on its own in a mode that doesn't accept it.
    NotAllowed(LogCommand),
}

/// Whole-utterance command, if any (allowed in `mode` or not).
fn whole_command(words: &[String], phrases: &VoicePhrases) -> Option<LogCommand> {
    for (cmd, list) in phrases.table() {
        for p in list {
            let pw = words_of(p);
            if !pw.is_empty() && pw == words {
                return Some(cmd);
            }
        }
    }
    None
}

/// Command in a partial result, for firing before the final result arrives.
/// Only whole-utterance commands allowed in `mode`.
pub fn peek_command(text: &str, mode: LogMode, phrases: &VoicePhrases) -> Option<LogCommand> {
    let words = words_of(text);
    whole_command(&words, phrases).filter(|c| is_allowed(mode, c))
}

/// Parse one recognised utterance in `mode`.
///
/// - A command fires when it is the whole utterance.
/// - `end <x>` commands (and `end log`) also fire at the end of an utterance;
///   the words before them are dictation.
/// - In standby, a tag phrase followed by words tags the log in one go.
pub fn match_log_utterance(utterance: &str, mode: LogMode, phrases: &VoicePhrases) -> Vec<Parsed> {
    let raw: Vec<&str> = utterance.split_whitespace().collect();
    if raw.is_empty() {
        return vec![];
    }
    let words = words_of(utterance);
    if words.is_empty() {
        return vec![];
    }

    if let Some(cmd) = whole_command(&words, phrases) {
        return vec![if is_allowed(mode, &cmd) {
            Parsed::Command(cmd)
        } else {
            Parsed::NotAllowed(cmd)
        }];
    }

    if mode == LogMode::Standby {
        for p in &phrases.tag {
            let pw = words_of(p);
            if pw.is_empty() || words.len() <= pw.len() || words.len() > pw.len() + 8 {
                continue;
            }
            if words[..pw.len()] == pw[..] {
                // Drop the phrase's raw words (same count; punctuation-only
                // tokens normalise away, so count non-empty ones).
                let mut seen = 0;
                let mut idx = 0;
                while idx < raw.len() && seen < pw.len() {
                    if !norm(raw[idx]).is_empty() {
                        seen += 1;
                    }
                    idx += 1;
                }
                let tags = split_tags(&raw[idx..].join(" "));
                if !tags.is_empty() {
                    return vec![Parsed::Command(LogCommand::TagWith { tags })];
                }
            }
        }
    }

    let trailing = [
        (LogCommand::EndLog, &phrases.end_log),
        (LogCommand::EndNote, &phrases.end_note),
        (LogCommand::EndTags, &phrases.end_tags),
        (LogCommand::EndAnnotation, &phrases.end_annotation),
        (LogCommand::EndMarkup, &phrases.end_markup),
    ];
    for (cmd, list) in trailing {
        if !is_allowed(mode, &cmd) {
            continue;
        }
        for p in list {
            let pw = words_of(p);
            if pw.is_empty() || words.len() <= pw.len() {
                continue;
            }
            if words[words.len() - pw.len()..] == pw[..] {
                // Find where the phrase starts in the raw words.
                let mut seen = 0;
                let mut idx = raw.len();
                while idx > 0 && seen < pw.len() {
                    idx -= 1;
                    if !norm(raw[idx]).is_empty() {
                        seen += 1;
                    }
                }
                let before = raw[..idx]
                    .join(" ")
                    .trim_end_matches([',', ';', ':', ' ', '-'])
                    .to_string();
                let mut out = Vec::new();
                if !before.is_empty() {
                    out.push(Parsed::Text(before));
                }
                out.push(Parsed::Command(cmd));
                return out;
            }
        }
    }

    vec![Parsed::Text(utterance.trim().to_string())]
}

#[cfg(test)]
mod log_tests {
    use super::*;

    fn m(s: &str, mode: LogMode) -> Vec<Parsed> {
        match_log_utterance(s, mode, &VoicePhrases::default())
    }
    fn c(cmd: LogCommand) -> Parsed {
        Parsed::Command(cmd)
    }

    #[test]
    fn whole_commands_by_mode() {
        assert_eq!(m("Note.", LogMode::Standby), vec![c(LogCommand::Note)]);
        assert_eq!(m("start note", LogMode::Standby), vec![c(LogCommand::Note)]);
        assert_eq!(m("End note", LogMode::Note), vec![c(LogCommand::EndNote)]);
        assert_eq!(
            m("end notes", LogMode::Note),
            vec![c(LogCommand::EndNote)],
            "plural matches singular"
        );
        assert_eq!(
            m("end annotations", LogMode::Annotate),
            vec![c(LogCommand::EndAnnotation)]
        );
        assert_eq!(m("end tagging", LogMode::Tag), vec![c(LogCommand::EndTags)]);
        assert_eq!(m("snap", LogMode::Markup), vec![c(LogCommand::Snap)]);
        assert_eq!(m("end log", LogMode::Annotate), vec![c(LogCommand::EndLog)]);
    }

    #[test]
    fn not_allowed_is_reported() {
        assert_eq!(
            m("end note", LogMode::Standby),
            vec![Parsed::NotAllowed(LogCommand::EndNote)]
        );
        assert_eq!(
            m("tag", LogMode::Note),
            vec![Parsed::NotAllowed(LogCommand::Tag)]
        );
        assert_eq!(
            m("undo", LogMode::Note),
            vec![Parsed::NotAllowed(LogCommand::Undo)]
        );
    }

    #[test]
    fn trailing_end_commands() {
        assert_eq!(
            m("rot goes two feet in, end note", LogMode::Note),
            vec![
                Parsed::Text("rot goes two feet in".into()),
                c(LogCommand::EndNote)
            ]
        );
        assert_eq!(
            m("that's it for today end log.", LogMode::Note),
            vec![
                Parsed::Text("that's it for today".into()),
                c(LogCommand::EndLog)
            ]
        );
        // Not an end command for this mode: plain dictation.
        assert_eq!(
            m("the label says end markup", LogMode::Note),
            vec![Parsed::Text("the label says end markup".into())]
        );
        // Other commands never fire mid-sentence.
        assert_eq!(
            m("then I had to snap the line", LogMode::Note),
            vec![Parsed::Text("then I had to snap the line".into())]
        );
        assert_eq!(
            m("next note about the joist", LogMode::Standby),
            vec![Parsed::Text("next note about the joist".into())]
        );
    }

    #[test]
    fn one_shot_tags_in_standby() {
        assert_eq!(
            m("tag pump, valve", LogMode::Standby),
            vec![c(LogCommand::TagWith {
                tags: vec!["pump".into(), "valve".into()]
            })]
        );
        assert_eq!(
            m("tag deck framing and joists", LogMode::Standby),
            vec![c(LogCommand::TagWith {
                tags: vec!["deck-framing".into(), "joists".into()]
            })]
        );
        assert_eq!(
            m("tag pump", LogMode::Note),
            vec![Parsed::Text("tag pump".into())]
        );
    }

    #[test]
    fn custom_phrases_and_peek() {
        let p = VoicePhrases {
            end_note: vec!["done note".into(), "over".into()],
            ..Default::default()
        };
        assert_eq!(
            match_log_utterance("over", LogMode::Note, &p),
            vec![c(LogCommand::EndNote)]
        );
        assert_eq!(
            match_log_utterance("rotten through over", LogMode::Note, &p),
            vec![
                Parsed::Text("rotten through".into()),
                c(LogCommand::EndNote)
            ]
        );
        assert_eq!(
            peek_command("Done note", LogMode::Note, &p),
            Some(LogCommand::EndNote)
        );
        assert_eq!(peek_command("done note", LogMode::Standby, &p), None);
        assert_eq!(peek_command("done", LogMode::Note, &p), None);
    }

    #[test]
    fn tag_splitting() {
        assert_eq!(
            split_tags("pump, valve and Gasket Kit"),
            vec!["pump", "valve", "gasket-kit"]
        );
        assert_eq!(split_tags("and"), Vec::<String>::new());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(s: &str, prefix: Option<&str>) -> Vec<VoiceItem> {
        parse_utterance(s.into(), prefix.map(String::from))
    }
    fn cmd(c: Command) -> VoiceItem {
        VoiceItem::Command { command: c }
    }
    fn dict(s: &str) -> VoiceItem {
        VoiceItem::Dictation { text: s.into() }
    }

    #[test]
    fn whole_utterance_commands() {
        assert_eq!(p("Snap", None), vec![cmd(Command::Snap)]);
        assert_eq!(p("next.", None), vec![cmd(Command::Next)]);
        assert_eq!(p("Scratch that", None), vec![cmd(Command::ScratchThat)]);
        assert_eq!(p("undo", None), vec![cmd(Command::Undo)]);
        assert_eq!(
            p("tag Deck Framing", None),
            vec![cmd(Command::Tag {
                tag: "deck-framing".into()
            })]
        );
    }

    #[test]
    fn command_words_inside_dictation_do_not_fire() {
        assert_eq!(
            p("next I cut the joist", None),
            vec![dict("next I cut the joist")]
        );
        assert_eq!(p("tag", None), vec![dict("tag")]);
        assert_eq!(p("tag a b c d e", None), vec![dict("tag a b c d e")]);
    }

    #[test]
    fn prefix_mode() {
        assert_eq!(p("next", Some("log")), vec![dict("next")]);
        assert_eq!(p("Log, next.", Some("log")), vec![cmd(Command::Next)]);
        assert_eq!(
            p("the joist is split, log snap", Some("log")),
            vec![dict("the joist is split"), cmd(Command::Snap)]
        );
        assert_eq!(
            p("log the cut then log tag joists", Some("log")),
            vec![
                dict("log the cut then"),
                cmd(Command::Tag {
                    tag: "joists".into()
                })
            ]
        );
        assert_eq!(p("log", Some("log")), vec![dict("log")]);
    }

    #[test]
    fn empty() {
        assert!(p("   ", None).is_empty());
    }
}
