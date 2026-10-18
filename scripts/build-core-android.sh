#!/usr/bin/env bash
# Build the FieldLog Rust core for Android ABIs and generate Kotlin bindings.
#
# Usage: scripts/build-core-android.sh <out-dir>
#   <out-dir>/jniLibs/<abi>/libfieldlog_core.so
#   <out-dir>/kotlin/uniffi/fieldlog_core/fieldlog_core.kt
#
# Requires: rustup targets (aarch64-linux-android, armv7-linux-androideabi,
# x86_64-linux-android), cargo-ndk, an NDK (ANDROID_NDK_HOME), and perl
# (vendored OpenSSL build).
set -euo pipefail

OUT="${1:?usage: $0 <out-dir>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/core"
PROFILE="${FIELDLOG_RUST_PROFILE:-release}"
ABIS="${FIELDLOG_ABIS:-arm64-v8a armeabi-v7a x86_64}"
API="${FIELDLOG_ANDROID_API:-28}"

mkdir -p "$OUT/jniLibs" "$OUT/kotlin"

args=()
for abi in $ABIS; do args+=(-t "$abi"); done

cd "$CORE"
profile_flag=()
[ "$PROFILE" = "release" ] && profile_flag=(--release)

cargo ndk "${args[@]}" --platform "$API" -o "$OUT/jniLibs" build -p fieldlog-core "${profile_flag[@]}"

# UniFFI metadata is stripped from release builds, so generate the bindings
# from a host debug build of the same source.
cargo build -p fieldlog-core
host_lib="$CORE/target/debug/libfieldlog_core.so"
[ -f "$host_lib" ] || host_lib="$CORE/target/debug/libfieldlog_core.dylib"
cargo run -q -p uniffi-bindgen -- generate --library "$host_lib" \
  --language kotlin --no-format --out-dir "$OUT/kotlin"

echo "Built core for: $ABIS -> $OUT"
