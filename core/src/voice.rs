//! Voice-command parsing for session mode.
//!
//! A recognised utterance is either dictation or a command. To keep words
//! like "next" from firing while narrating real work, a command only fires
//! when it is the *whole* utterance. With a command prefix configured (e.g.
//! "log"), bare command words are always dictation and "log next" fires,
//! including at the end of a longer utterance: "the joist is split log snap".

use crate::format::slugify;

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
