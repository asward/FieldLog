//! FieldLog shared core: vaults (git repos via libgit2), the log file
//! format, the log state machine and voice commands, search, content-addressed
//! images and an S3 client. Exposed to Android (and later iOS) via UniFFI.

uniffi::setup_scaffolding!();

mod error;
mod format;
mod images;
mod keys;
mod log;
mod logsession;
mod s3;
mod search;
mod shared;
mod spike;
mod vault;
mod voice;

pub use error::CoreError;
pub use format::Block;
pub use keys::*;
pub use log::{EntryKind, LogEntry, PhotoRef};
pub use logsession::*;
pub use s3::{S3Config, S3Credentials};
pub use shared::*;
pub use spike::*;
pub use vault::*;
pub use voice::*;
