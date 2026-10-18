# Vault format (v2)

A vault is a plain git repository. Everything is readable without FieldLog: Markdown files, relative image links, one TOML config.

```
fieldlog.toml                         vault settings (committed)
.gitignore                            .fieldlog/ (and assets/ in local and S3 modes)
assets/ab/abcdef….jpg                 photos, named by sha256 of their bytes
deck-rebuild/README.md                project: "# Deck Rebuild"
deck-rebuild/2026/10/2026-10-18-103512-a1b2.md   one log
.fieldlog/                            device-local, never committed
  open-log.json                       the open log's state machine (resume after a crash)
  pending-uploads.txt                 photos not yet in S3/R2
```

## `fieldlog.toml`

```toml
name = "Home"
image_storage = "local"   # "local" (default), "s3" or "git"

[s3]                      # required when image_storage = "s3"
endpoint = "https://<account>.r2.cloudflarestorage.com"
region = "auto"
bucket = "my-notes"
prefix = "fieldlog/home"  # optional
path_style = true         # true for R2, MinIO and most self-hosted stores
```

No secrets live in the vault. SSH keys, tokens and S3 keys stay on the device.

## Projects

A project is a top-level directory with a `README.md` whose first `# ` heading is its title. The directory name is the slug.

## Logs

One file per log, from New log to `end log`:

````markdown
---
id: 20261018T103512-a1b2
project: deck-rebuild
started: 2026-10-18T10:35:12-07:00
ended: 2026-10-18T11:02:40-07:00
tags:
- rot
- ledger
source: log
photos:
- 3a7bd3e2…4f1b
- 9c04…e1
---

## 10:35:12 Note

Pulled the old ledger board.

![](../../../assets/3a/3a7bd3e2…4f1b.jpg)

Rot goes about two feet in.

## 10:41:03 Snap

![](../../../assets/9c/9c04…e1.jpg)

> Joist end under the door, soft to a screwdriver.

```fieldlog-markup
circle: dark area at joist end
arrow: water path from door sill
```
````

- **Entries** sit under `## HH:MM:SS Note` or `## HH:MM:SS Snap` headings, in the log's UTC offset. Edits made outside FieldLog must keep these headings; text before the first heading reads as a note. If a log crosses midnight, a time earlier than the one before it is the next day.
- **Photos** are an image line, followed optionally by the annotation as a `>` quote and the markup suggestions (one per line) in a `fieldlog-markup` fence. A photo inside a note works the same way. A quote or fence that doesn't follow a photo is ordinary text.
- `started` is when the log began; `ended` is when `end log` was said (for an edited log, the later of the previous end and its last entry). V1 files call `started` `created`; they still read.
- `source` is `log` or `share`.
- `photos` lists every photo's sha256 in body order.
- Unknown front-matter keys are preserved.
- **V1 entry files** (no headings) read as a log with one note.

## Photos

Named by the sha256 of their bytes (after the app strips EXIF and downscales), extension from the file's magic bytes. Identical photos are stored once. The same name is the S3/R2 key: `<prefix>/assets/ab/<sha>.<ext>`.

- `local` (default for new vaults): `assets/` is git-ignored and every photo is queued in `.fieldlog/pending-uploads.txt`. Other devices and the git host show missing images until S3/R2 is set up.
- `s3`: `assets/` is git-ignored and acts as a local cache. Photos upload before every push, so a pushed log never points at a missing photo. Other devices download on demand and verify the hash.
- `git`: photos are committed with the log that first uses them. Meant for self-hosted remotes.

Switching `git` → `local`/`s3` untracks `assets/` (history keeps the photos); `local` → `s3` uploads the queue on the next sync; `local`/`s3` → `git` commits the photos on the device.

## Commits

- `end log`: one commit, `deck-rebuild: Pulled the old ledger board.` with a `FieldLog-Log: <id>` trailer. A log with no entries and no tags isn't committed.
- Reopening and ending again: `deck-rebuild: edit …` with `FieldLog-Edit: <id>`. No commit if nothing changed.
- Removing a log (or deleting all its entries in an edit): `deck-rebuild: remove …` with `FieldLog-Revert: <id>`. History is never rewritten.
- V1 entry commits carry `FieldLog-Entry:` / `FieldLog-Undo:` trailers.

## Sync

Fetch, then fast-forward or a merge commit, then push. It doesn't run while a log is open (`.fieldlog/open-log.json` exists); `end log` triggers it. Logs are separate files, so devices writing at the same time merge cleanly. If the same log was edited on two devices, both are kept: the remote version at the original path and this device's as `<name>.conflict-<device>-<time>.md`. Conflicts on other files stop the sync with an error.
