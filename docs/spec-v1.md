# FieldLog — Spec V1

Frictionless note taking and recording during personal projects.

## Architecture

- **libgit2 is the git library.** It runs on Android (via the native core) and iOS (Working Copy is built on it). JGit is Java-only and would have to be replaced for iOS.
- **Shared Rust core** using libgit2's Rust bindings (git2-rs), compiled for both platforms. The S3 client, image hashing, front-matter parsing and search are shared too; only the UI is per platform.
- SSH support needs libgit2 built with libssh2 (build configuration, not a blocker).

## Session mode

- The project is sticky. There is no save button and no per-entry project choice.
- Entries commit themselves on **"next"**, a tap, or a silence timeout.
- Photos land inline at the point in the narration where they were taken.

### Voice commands (each has a touch backup)

| Say | Does |
| --- | --- |
| "snap" | takes a photo |
| "next" | starts a new entry |
| "scratch that" | removes the last sentence |
| "undo" | reverts the last commit |
| "tag x" | adds tag `x` |

### Entry points

Home-screen widget, Quick Settings tile, app shortcuts per project, volume keys as shutter and mic, access over the lock screen.

## Decisions

- libgit2 inside a shared Rust core, so a future iOS app reuses git, storage, file format and search.
- Raw audio is never kept.
- Multiple vaults (separate repos) are supported.
- The share sheet accepts text and links as well as images.

## Watch items

- **Voice-command misfires** while narrating real work. If words like "next" trigger by accident, add a prefix like "log, next." *(Open question.)*
- **Spike 0 first:** prove the Rust core plus libgit2 and SSH cross-compile and run on Android before anything else.
