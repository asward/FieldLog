# FieldLog

Frictionless note taking for personal projects: talk, snap photos, keep working. Logs land as Markdown in a git repo you own.

- `core/` — shared Rust core: vaults (git via libgit2 + libssh2), the log file format, the log state machine and voice commands, search, content-addressed images, S3 client. Exposed through UniFFI so a future iOS app reuses all of it.
- `android/` — Android app (Kotlin, Jetpack Compose, CameraX).
- `docs/` — [spec](docs/SPEC.md) (current: [v2](docs/spec-v2.md)) and [vault format](docs/FORMAT.md).

## Words

- **Vault**: one git repo. **Project**: a body of work, e.g. a deck rebuild or a new app idea.
- **Active project**: the one you last tapped; New log, the widget and the tile use it.
- **Log**: everything from New log to "end log". One Markdown file, one commit. You can reopen it later.
- **Entry**: a **note** or a **snap** (photo + annotation + markup) inside a log. Tags belong to the log.
- **Save**: commit, then push.

## Using it

1. **Set up a vault.** Local only, or connect a git remote. For SSH, tap *Generate key* and add the public key as a deploy key with write access, then *Connect*. An empty repo becomes a new vault.
2. **Run the speech check** (Settings → Speech; Home offers it once). It finds the fastest speech setup that works on your phone.
3. **Create a project** and tap it. The project view lists its logs like a blog: newest first, by day, with times, first lines, thumbnails and tags.
4. **New log** (from the project, the widget, the Quick Settings tile or a launcher shortcut — all work over the lock screen). The log screen starts in **standby**.
5. **Talk in modes.** The banner shows the mode in colour and what you can say. Every mode change has its own sound and vibration.

| Mode | Say to enter | What you say becomes | Say to leave |
| --- | --- | --- | --- |
| Standby | (start) | nothing: commands only | — |
| Note | "note" | the note | "end note" |
| Tags | "tag" | one tag per phrase | "end tags" |
| Annotate | "snap" (takes the photo at once) | the photo's annotation | "end annotation" → Markup |
| Markup | — | markup suggestions for later AI | "end markup" |

In any mode: "scratch that" (remove the last thing said), "cancel" (drop the open note or snap), "end log" (save). In standby: "undo" (remove the last entry) and "tag pump, valve" (tag in one go). "end note" also works at the end of a sentence: "…rot goes two feet in, end note". Every command has a button; change the phrases in Settings → Command phrases.

- **Snap from inside a note** puts the photo inline and returns to the note after markup. Saying "snap" while annotating starts the next photo.
- **Nothing is committed until "end log"**, but the log is written to the phone as you go: if the app is killed, Resume picks it up.
- **The screen can lock**; listening continues (there's a notification with *End log*). Voice "snap" while locked just posts *Unlock to snap*.
- **After a quiet spell** (20 min by default) the mic stops; the log stays open.
- **Reopen a log** from its page to add entries or fix text; "end log" commits again. If the same log was edited on two phones, sync keeps both copies.

| Button / key | Does |
| --- | --- |
| volume up, tap the preview | snap |
| volume down, mic icon | mic off / on |
| Type | keyboard input for the current mode |

**Share sheet:** share text, links or images from any app to FieldLog; one tap saves a one-note log to the active project.

**Photos** stay on the phone by default (vault settings → Photo storage: *This phone*), named by their sha256, so setting up S3 / Cloudflare R2 later uploads them with no repo changes. *Git* storage commits photos and is meant for self-hosted remotes. Vault settings shows how many photos aren't backed up and can export them as a zip.

**Sync** runs right after "end log", in the background after changes, and hourly (fetch, merge, push). It waits while a log is open.

**Privacy:** raw audio is never recorded or kept. Photos are downscaled to 2048 px and stripped of EXIF (including location). Keys and tokens are encrypted with the Android Keystore and never written to the vault.

## Building

Core tests (host):

```sh
cd core && cargo test
```

Android (needs Android SDK + NDK, `cargo install cargo-ndk`, and the Rust Android targets):

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
export ANDROID_NDK_HOME=/path/to/ndk
cd android && ./gradlew assembleDebug
```

Gradle runs `scripts/build-core-android.sh`, which cross-compiles the core and generates the Kotlin bindings into `android/app/build/rust`. CI builds a debug APK for every PR (artifact `fieldlog-debug-apk`). The earcons in `res/raw` come from `scripts/make-earcons.py`.

## Known limits

- Continuous listening needs Android 13 and a recogniser that supports segmented sessions; the speech check tells you. Otherwise the v1 restart mode is used (commands still fire from partial results).
- The camera needs the log screen on screen; voice "snap" with the phone locked asks you to unlock.
- Photos in *This phone* storage are lost if the app is uninstalled or its data cleared, until S3/R2 is set up or they're exported.
- Upgrading from v1: re-add the widget and the Quick Settings tile (they were renamed).
