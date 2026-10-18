//! Full-text search over entries. A linear scan: vaults are personal-sized
//! (thousands of entries), and parsing is cheap relative to the UI round trip.

use crate::format;
use crate::vault::{SearchHit, Vault};

fn snippet(body: &str, term: &str, radius: usize) -> String {
    let text: String = body
        .lines()
        .map(str::trim)
        .filter(|l| {
            !l.is_empty()
                && !l.starts_with("![")
                && !l.starts_with("```")
                && !crate::log::is_entry_heading(l)
        })
        .map(|l| l.strip_prefix('>').map(str::trim_start).unwrap_or(l))
        .collect::<Vec<_>>()
        .join(" ");
    let lower = text.to_lowercase();
    // Byte offsets of `lower` and `text` can differ for some scripts, so work
    // in chars.
    let chars: Vec<char> = text.chars().collect();
    let lchars: Vec<char> = lower.chars().collect();
    let tchars: Vec<char> = term.chars().collect();
    let pos = if tchars.is_empty() {
        None
    } else {
        lchars
            .windows(tchars.len())
            .position(|w| w == tchars.as_slice())
    };
    let Some(pos) = pos.filter(|_| chars.len() == lchars.len()) else {
        return format::summary_line(body, radius * 2);
    };
    let start = pos.saturating_sub(radius);
    let end = (pos + tchars.len() + radius).min(chars.len());
    let mut s: String = chars[start..end].iter().collect();
    if start > 0 {
        s.insert(0, '…');
    }
    if end < chars.len() {
        s.push('…');
    }
    s
}

pub(crate) fn search(
    vault: &Vault,
    query: &str,
    project: Option<&str>,
    tag: Option<&str>,
    limit: u32,
) -> Vec<SearchHit> {
    let terms: Vec<String> = query
        .split_whitespace()
        .map(|t| t.trim_start_matches('#').to_lowercase())
        .filter(|t| !t.is_empty())
        .collect();
    let tag = tag.map(format::slugify);
    let mut hits: Vec<SearchHit> = Vec::new();

    for summary in vault.list_entries(project.map(str::to_string), 0) {
        if let Some(t) = &tag {
            if !summary.tags.iter().any(|x| x == t) {
                continue;
            }
        }
        let Ok(entry) = vault.read_entry(summary.path.clone()) else {
            continue;
        };
        let body_l = entry.body.to_lowercase();
        let meta_l = format!("{} {}", entry.project, entry.tags.join(" ")).to_lowercase();
        let mut score = 0u32;
        let mut all = true;
        for t in &terms {
            let n = body_l.matches(t.as_str()).count() as u32;
            let m = meta_l.matches(t.as_str()).count() as u32;
            if n + m == 0 {
                all = false;
                break;
            }
            score += n + 3 * m;
        }
        if !all {
            continue;
        }
        let snip = terms
            .first()
            .map(|t| snippet(&entry.body, t, 60))
            .unwrap_or_else(|| summary.summary.clone());
        hits.push(SearchHit {
            entry: summary,
            snippet: snip,
            score,
        });
    }
    // Score, then newest first.
    hits.sort_by(|a, b| {
        b.score
            .cmp(&a.score)
            .then_with(|| b.entry.created.cmp(&a.entry.created))
    });
    if terms.is_empty() {
        hits.sort_by(|a, b| b.entry.created.cmp(&a.entry.created));
    }
    if limit > 0 {
        hits.truncate(limit as usize);
    }
    hits
}

#[cfg(test)]
mod tests {
    use super::snippet;

    #[test]
    fn snippets() {
        let body = "Cut the left joist. Sistered it with a 2x8.\n\n![](x.jpg)\n\nGlue next.";
        assert_eq!(snippet(body, "sistered", 5), "…ist. Sistered it w…");
        assert_eq!(
            snippet(body, "glue", 100),
            body.replace("\n\n![](x.jpg)\n\n", " ")
        );
        let log = "## 10:00:00 Note\n\nCut it.\n\n![](x.jpg)\n\n> Soft wood.\n\n```fieldlog-markup\ncircle\n```";
        assert_eq!(snippet(log, "soft", 100), "Cut it. Soft wood. circle");
    }
}
