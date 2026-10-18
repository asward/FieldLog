# Vault format

A vault is a plain git repository. Everything is readable without FieldLog: Markdown files, relative image links, one TOML config.

```
fieldlog.toml                         vault settings (committed)
.gitignore                            .fieldlog/ (and assets/ in S3 mode)
assets/ab/abcdef….jpg                 images, named by sha256 of their bytes
deck-rebuild/README.md                project: "# Deck Rebuild"
deck-rebuild/2026/10/2026-10-17-204512-a1b2.md   one entry
.fieldlog/                            device-local, never committed
  drafts/<project>.json               in-progress session draft
  pending-uploads.txt                 images not yet in S3
```

## `fieldlog.toml`

```toml
name = "Home"
image_storage = "git"     # or "s3"

[s3]                      # required when image_storage = "s3"
endpoint = "https://s3.us-west-2.amazonaws.com"
region = "us-west-2"
bucket = "my-notes"
prefix = "fieldlog/home"  # optional
path_style = false        # true for MinIO and most self-hosted stores
```

No secrets live in the vault. SSH keys, tokens and S3 keys stay on the device.

## Projects

A project is a top-level directory with a `README.md` whose first `# ` heading is its title. The directory name is the slug.

## Entries

```markdown
---
id: 20261017T204512-a1b2
project: deck-rebuild
created: 2026-10-17T20:45:12-07:00
tags:
- rot
source: session
photos:
- 3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b
---

Pulled the old ledger board.

![](../../../assets/3a/3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b.jpg)

Rot goes about two feet in.
```

- `created` is when the entry was *started* (first sentence or photo), with the device's UTC offset.
- `source` is `session` or `share`.
- `photos` lists every image in body order. The body links each image relatively, at the point in the narration where it was taken.
- Unknown front-matter keys are preserved.

## Images

Named by the sha256 of their bytes, extension from the file's magic bytes. Identical photos are stored once.

- `git` mode: committed with the entry that first uses them.
- `s3` mode: `assets/` is git-ignored and acts as a local cache. Images upload to `<prefix>/assets/ab/<sha>.<ext>` before every push, so a pushed entry never points at a missing image. Other devices download on demand and verify the hash.

## Commits

One commit per entry: `deck-rebuild: Pulled the old ledger board.` with a `FieldLog-Entry: <id>` trailer. "Undo" adds a revert commit with `FieldLog-Undo: <id>`; history is never rewritten, so undo is safe after a push.

Sync is fetch, then fast-forward or a merge commit, then push. Entries are separate files, so devices writing at the same time merge cleanly.
