#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("git: {msg}")]
    Git { msg: String },
    #[error("io: {msg}")]
    Io { msg: String },
    #[error("format: {msg}")]
    Format { msg: String },
    #[error("not found: {msg}")]
    NotFound { msg: String },
    #[error("invalid: {msg}")]
    Invalid { msg: String },
    #[error("sync conflict: {msg}")]
    Conflict { msg: String },
    #[error("host key mismatch: expected {expected}, got {actual}")]
    HostKeyMismatch { expected: String, actual: String },
    #[error("s3: {msg}")]
    S3 { msg: String },
    #[error("nothing to undo")]
    NothingToUndo,
}

pub type Result<T> = std::result::Result<T, CoreError>;

impl From<git2::Error> for CoreError {
    fn from(e: git2::Error) -> Self {
        CoreError::Git {
            msg: e.message().to_string(),
        }
    }
}

impl From<std::io::Error> for CoreError {
    fn from(e: std::io::Error) -> Self {
        CoreError::Io { msg: e.to_string() }
    }
}

impl From<serde_norway::Error> for CoreError {
    fn from(e: serde_norway::Error) -> Self {
        CoreError::Format { msg: e.to_string() }
    }
}

impl From<serde_json::Error> for CoreError {
    fn from(e: serde_json::Error) -> Self {
        CoreError::Format { msg: e.to_string() }
    }
}

impl From<toml::de::Error> for CoreError {
    fn from(e: toml::de::Error) -> Self {
        CoreError::Format { msg: e.to_string() }
    }
}

impl From<toml::ser::Error> for CoreError {
    fn from(e: toml::ser::Error) -> Self {
        CoreError::Format { msg: e.to_string() }
    }
}

pub(crate) fn invalid(msg: impl Into<String>) -> CoreError {
    CoreError::Invalid { msg: msg.into() }
}
