package io.github.asward.fieldlog.log

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.asward.fieldlog.feedback.Cue
import io.github.asward.fieldlog.ui.BannerColors
import uniffi.fieldlog_core.LogCommand
import uniffi.fieldlog_core.LogMode
import uniffi.fieldlog_core.VoicePhrases

val LogMode.title: String
    get() = when (this) {
        LogMode.STANDBY -> "Standby"
        LogMode.NOTE -> "Note"
        LogMode.TAG -> "Tags"
        LogMode.ANNOTATE -> "Annotate"
        LogMode.MARKUP -> "Markup"
    }

val LogMode.color: Color
    get() = when (this) {
        LogMode.STANDBY -> BannerColors.Standby
        LogMode.NOTE -> BannerColors.Note
        LogMode.TAG -> BannerColors.Tags
        LogMode.ANNOTATE -> BannerColors.Annotate
        LogMode.MARKUP -> BannerColors.Markup
    }

/** Sound and vibration when entering a mode. ANNOTATE's cue is the shutter. */
val LogMode.enterCue: Cue?
    get() = when (this) {
        LogMode.STANDBY -> Cue.MODE_ENDED
        LogMode.NOTE -> Cue.ENTER_NOTE
        LogMode.TAG -> Cue.ENTER_TAG
        LogMode.ANNOTATE -> null
        LogMode.MARKUP -> Cue.ENTER_MARKUP
    }

/** Silence that ends an utterance: short when only commands are expected. */
val LogMode.silenceMs: Long
    get() = if (this == LogMode.STANDBY) 700L else 1500L

/** What dictation does in this mode, for the hint line. */
val LogMode.does: String
    get() = when (this) {
        LogMode.STANDBY -> "Listening for commands"
        LogMode.NOTE -> "Talk; it becomes the note"
        LogMode.TAG -> "Each phrase becomes a tag"
        LogMode.ANNOTATE -> "Describe the photo"
        LogMode.MARKUP -> "Say what to mark on the photo"
    }

data class CommandUi(val label: String, val icon: ImageVector)

val LogCommand.ui: CommandUi
    get() = when (this) {
        LogCommand.Note -> CommandUi("Note", Icons.Default.EditNote)
        LogCommand.EndNote -> CommandUi("End note", Icons.Default.Check)
        LogCommand.Tag -> CommandUi("Tag", Icons.Default.Tag)
        LogCommand.EndTags -> CommandUi("End tags", Icons.Default.Check)
        LogCommand.Snap -> CommandUi("Snap", Icons.Default.PhotoCamera)
        LogCommand.EndAnnotation -> CommandUi("End annotation", Icons.Default.Check)
        LogCommand.EndMarkup -> CommandUi("End markup", Icons.Default.Check)
        LogCommand.ScratchThat -> CommandUi("Scratch that", Icons.Default.Backspace)
        LogCommand.Cancel -> CommandUi("Cancel", Icons.Default.Close)
        LogCommand.Undo -> CommandUi("Undo", Icons.AutoMirrored.Filled.Undo)
        LogCommand.EndLog -> CommandUi("End log", Icons.Default.Save)
        is LogCommand.TagWith -> CommandUi("Tag", Icons.Default.Tag)
    }

/** The first phrase for a command, as the person would say it. */
fun VoicePhrases.spoken(c: LogCommand): String = when (c) {
    LogCommand.Note -> note
    LogCommand.EndNote -> endNote
    LogCommand.Tag -> tag
    LogCommand.EndTags -> endTags
    LogCommand.Snap -> snap
    LogCommand.EndAnnotation -> endAnnotation
    LogCommand.EndMarkup -> endMarkup
    LogCommand.ScratchThat -> scratchThat
    LogCommand.Cancel -> cancel
    LogCommand.Undo -> undo
    LogCommand.EndLog -> endLog
    is LogCommand.TagWith -> tag
}.firstOrNull() ?: c.ui.label.lowercase()

/** "Say: end note · snap · scratch that · cancel · end log" */
fun VoicePhrases.hint(allowed: List<LogCommand>): String =
    "Say: " + allowed.joinToString(" · ") { "“${spoken(it)}”" }
