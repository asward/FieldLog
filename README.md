# FieldLog

Frictionless note taking for personal projects: talk, snap photos, keep working. Entries land as Markdown in a git repo you own.

- `core/` — shared Rust core: vaults (git via libgit2 + libssh2), entry format, session engine, voice commands, search, content-addressed images, S3 client. Exposed through UniFFI so a future iOS app reuses all of it.
- `android/` — Android app (Kotlin, Jetpack Compose, CameraX).
- `docs/` — [spec](docs/SPEC.md) and [vault format](docs/FORMAT.md).

## Using it

1. **Set up a vault.** Local only, or connect a git remote. For SSH, tap *Generate key* and add the public key as a deploy key with write access, then *Connect*. An empty repo becomes a new vault. Add more vaults from the vault menu.
2. **Create a project** and mark it as the session project (the circle on the right). It stays sticky until you pick another.
3. **Start a session** from the app, the home-screen widget, the Quick Settings tile, or a launcher shortcut (long-press the app icon). All of them work over the lock screen.
4. **Just talk.** Each pause adds a sentence. Entries commit themselves on "next", a tap on ⏭, or after the silence timeout.

| Say | Touch backup | Does |
| --- | --- | --- |
| "snap" | camera button, tap the preview, volume up | photo, placed inline where you are in the narration |
| "next" | ⏭ | commit this entry, start a new one |
| "scratch that" | ⌫ | remove the last sentence |
| "undo" | ↶ | revert the last committed entry |
| "tag framing" | # | tag the current entry |
| | volume down | mute / unmute the mic |

Commands only fire when said on their own, so "next I cut the joist" is just dictation. If they still misfire, set a **command prefix** (e.g. "log") in settings and say "log next".

**Share sheet:** share text, links or images from any app to FieldLog; one tap saves them to the session project.

**Sync** runs in the background after each entry and hourly (fetch, merge, push). Photos are committed to git by default; switch a vault to S3 (AWS, R2, B2, MinIO) in its settings to keep them out of the repo.

**Privacy:** raw audio is never recorded or kept; the on-device recognizer is used when available. Photos are downscaled to 2048 px and stripped of EXIF (including location). Keys and tokens are encrypted with the Android Keystore and never written to the vault.

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

Gradle runs `scripts/build-core-android.sh`, which cross-compiles the core and generates the Kotlin bindings into `android/app/build/rust`. CI builds a debug APK for every PR (artifact `fieldlog-debug-apk`).

## Known limits (v1)

- Sessions run while the session screen is open (it keeps the screen on and works over the lock screen). There's no background listening service yet.
- Android's recognizer plays a short tone each time it restarts listening on some devices.
- Undo only reverts entries; edits made outside FieldLog are left alone.
