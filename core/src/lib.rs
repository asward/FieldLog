//! FieldLog shared core.
//!
//! Spike 0 surface: prove that libgit2 (with libssh2 + OpenSSL, all vendored)
//! cross-compiles for Android, loads over UniFFI, and can do real git work on
//! the device, including talking to an SSH remote.

uniffi::setup_scaffolding!();

mod spike;

pub use spike::*;
