//! FieldLog shared core: vaults (git repos via libgit2), the entry file
//! format, the session engine and voice commands, search, content-addressed
//! images and an S3 client. Exposed to Android (and later iOS) via UniFFI.

uniffi::setup_scaffolding!();

mod error;
mod format;
mod images;
mod s3;
mod search;
mod session;
mod spike;
mod vault;
mod voice;

pub use error::CoreError;
pub use format::Block;
pub use s3::{S3Config, S3Credentials};
pub use session::*;
pub use spike::*;
pub use vault::*;
pub use voice::*;
