package io.github.asward.fieldlog

import uniffi.fieldlog_core.CoreException

/** Human-readable message for anything the core (or Android) throws. */
fun Throwable.userMessage(): String = when (this) {
    is CoreException.Git -> msg
    is CoreException.Io -> msg
    is CoreException.Format -> "Unreadable file: $msg"
    is CoreException.NotFound -> "Not found: $msg"
    is CoreException.Invalid -> msg
    is CoreException.Conflict -> "Sync conflict: $msg"
    is CoreException.HostKeyMismatch ->
        "The server's SSH host key changed. If you expected this, forget the saved key in vault settings."
    is CoreException.S3 -> "Image storage: $msg"
    is CoreException.LogOpen -> "A log is open. Resume or end it first."
    else -> message ?: javaClass.simpleName
}
