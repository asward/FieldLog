# FieldLog

Frictionless note taking for personal projects: talk, snap photos, keep working. Entries land as Markdown in a git repo you own.

- `core/` — shared Rust core (git via libgit2, file format, session engine, search, images, S3). Exposed to apps via UniFFI.
- `android/` — Android app (Kotlin, Jetpack Compose).
- `docs/` — spec and file format.

See [docs/SPEC.md](docs/SPEC.md).

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

Gradle runs `scripts/build-core-android.sh`, which cross-compiles the core and generates the Kotlin bindings into `android/app/build/rust`.
