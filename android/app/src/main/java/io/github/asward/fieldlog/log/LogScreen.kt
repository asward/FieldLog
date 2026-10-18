@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package io.github.asward.fieldlog.log

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.asward.fieldlog.media.Camera
import io.github.asward.fieldlog.speech.SpeechSupport
import io.github.asward.fieldlog.ui.BannerColors
import io.github.asward.fieldlog.ui.ModeBanner
import io.github.asward.fieldlog.ui.TextDialog
import io.github.asward.fieldlog.ui.Thumb
import io.github.asward.fieldlog.ui.prettyTime
import io.github.asward.fieldlog.vaults
import kotlinx.coroutines.delay
import uniffi.fieldlog_core.Block
import uniffi.fieldlog_core.EntryKind
import uniffi.fieldlog_core.LogCommand
import uniffi.fieldlog_core.LogEntry
import uniffi.fieldlog_core.LogMode

private sealed interface EditTarget {
    data class TextBlock(val entry: Int, val block: Int, val text: String) : EditTarget
    data class PhotoBlock(val entry: Int, val block: Int, val annotation: String, val markup: List<String>) : EditTarget
    data class Entry(val entry: Int) : EditTarget
}

@Composable
fun LogScreen(engine: LogEngine, camera: Camera, onFinished: () -> Unit) {
    val context = LocalContext.current
    val vaults = context.vaults
    val phrases = remember { vaults.voicePhrases }
    val state = engine.state
    var closeDialog by remember { mutableStateOf(false) }
    var typeDialog by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<EditTarget?>(null) }
    val listState = rememberLazyListState()

    val save = engine.save
    if (save != null && !engine.isOpen) {
        SavedResult(save, onDone = onFinished)
        return
    }
    if (state == null) return

    BackHandler { closeDialog = true }
    val count = state.entries.size
    LaunchedEffect(count, state.currentEntry) {
        if (count > 0) listState.animateScrollToItem((state.currentEntry?.toInt() ?: (count - 1)).coerceAtMost(count - 1))
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(engine.projectTitle, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        if (state.editing) "Editing" else "Started ${prettyTime(state.started)}",
                        engine.speechLabel.takeIf { it.isNotEmpty() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { engine.toggleMic() }) {
                Icon(if (engine.micOn) Icons.Default.Mic else Icons.Default.MicOff, if (engine.micOn) "Mute" else "Listen")
            }
            IconButton(onClick = { closeDialog = true }) { Icon(Icons.Default.Close, "Close log") }
        }

        // Mode banner (spec §8): colour per mode, what to say, mic level.
        val title = when {
            !engine.micOn && engine.idleStopped -> "Paused · ${state.mode.title}"
            !engine.micOn -> "Mic off · ${state.mode.title}"
            else -> state.mode.title
        }
        ModeBanner(
            title = title,
            color = if (engine.micOn) state.mode.color else BannerColors.Off,
            hint = engine.partial.takeIf { it.isNotBlank() }
                ?: "${state.mode.does} · ${phrases.hint(state.allowed)}",
            level = if (engine.micOn && engine.listening) engine.level else null,
            modifier = Modifier.padding(top = 4.dp),
        )

        engine.fallback?.let { msg ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (!SpeechSupport.triggerLanguageDownload(context)) {
                        android.widget.Toast.makeText(context, "Download it in the phone's speech settings", android.widget.Toast.LENGTH_LONG).show()
                    }
                    engine.fallback = null
                }) { Text("Download") }
            }
        }
        Text(
            engine.message ?: "",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(20.dp),
        )

        // Camera: tap to snap.
        Box(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(140.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color.Black)
                .clickable { engine.takePhoto() },
        ) {
            AndroidView(factory = { camera.previewView }, modifier = Modifier.fillMaxSize())
        }

        if (state.tags.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.tags.forEach { t ->
                    InputChip(
                        selected = false,
                        onClick = { engine.removeTag(t) },
                        label = { Text("#$t") },
                        trailingIcon = { Icon(Icons.Default.Close, "Remove tag", Modifier.size(16.dp)) },
                    )
                }
            }
        }

        // The log so far, in reading order.
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.entries.isEmpty()) {
                item {
                    Text(
                        "Nothing yet. Say “${phrases.spoken(LogCommand.Note)}” to start a note, " +
                            "“${phrases.spoken(LogCommand.Snap)}” for a photo, " +
                            "“${phrases.spoken(LogCommand.EndLog)}” when you're done.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(state.entries) { i, e ->
                EntryCard(
                    index = i,
                    entry = e,
                    current = state.currentEntry?.toInt() == i,
                    currentColor = state.mode.color,
                    vaultId = engine.vaultId,
                    onLongPress = { if (state.mode == LogMode.STANDBY) edit = EditTarget.Entry(i) },
                    onText = { b, text -> edit = EditTarget.TextBlock(i, b, text) },
                    onPhoto = { b, a, m -> edit = EditTarget.PhotoBlock(i, b, a, m) },
                )
            }
        }

        // Touch backups: exactly the commands this mode accepts.
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.allowed.forEach { cmd ->
                val ui = cmd.ui
                val primary = cmd == LogCommand.EndLog || cmd == LogCommand.Snap
                if (primary) {
                    Button(
                        onClick = { engine.command(cmd) },
                        colors = if (cmd == LogCommand.EndLog) ButtonDefaults.buttonColors()
                        else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    ) {
                        Icon(ui.icon, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(ui.label)
                    }
                } else {
                    FilledTonalButton(onClick = { engine.command(cmd) }) {
                        Icon(ui.icon, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(ui.label)
                    }
                }
            }
            if (state.mode != LogMode.STANDBY) {
                TextButton(onClick = { typeDialog = true }) {
                    Icon(Icons.Default.Keyboard, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Type")
                }
            }
        }
    }

    if (closeDialog) {
        AlertDialog(
            onDismissRequest = { closeDialog = false },
            title = { Text("Close this log?") },
            text = {
                Text(
                    if (state.editing) "End log commits your changes. Discard puts the log back as it was."
                    else "End log saves it (commit, then push). Discard throws it away.",
                )
            },
            confirmButton = {
                TextButton(onClick = { closeDialog = false; engine.endLog() }) { Text("End log") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { closeDialog = false; engine.discard() }) {
                        Text("Discard", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { closeDialog = false }) { Text("Keep logging") }
                }
            },
        )
    }
    if (typeDialog) {
        TextDialog(state.mode.title, "Text", multiline = true, onDismiss = { typeDialog = false }) { engine.type(it) }
    }
    when (val t = edit) {
        is EditTarget.TextBlock -> TextDialog(
            "Edit text", "Text", multiline = true, initial = t.text, confirm = "Save", allowEmpty = true,
            onDismiss = { edit = null },
        ) { engine.editText(t.entry, t.block, it) }
        is EditTarget.PhotoBlock -> PhotoDialog(t, onDismiss = { edit = null }) { a, m ->
            engine.editPhoto(t.entry, t.block, a, m)
        }
        is EditTarget.Entry -> AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text("Delete this entry?") },
            confirmButton = {
                TextButton(onClick = { engine.deleteEntry(t.entry); edit = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

@Composable
private fun EntryCard(
    index: Int,
    entry: LogEntry,
    current: Boolean,
    currentColor: Color,
    vaultId: String?,
    onLongPress: () -> Unit,
    onText: (Int, String) -> Unit,
    onPhoto: (Int, String, List<String>) -> Unit,
) {
    val context = LocalContext.current
    val border = if (current) Modifier.border(2.dp, currentColor, RoundedCornerShape(12.dp)) else Modifier
    Card(
        Modifier.fillMaxWidth().then(border).combinedClickable(onClick = {}, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${prettyTime(entry.time)} · ${if (entry.kind == EntryKind.NOTE) "Note" else "Snap"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.blocks.isEmpty()) {
                Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Consecutive text blocks read as one paragraph; each stays editable.
            entry.blocks.forEachIndexed { b, block ->
                when (block) {
                    is Block.Text -> Text(
                        block.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onText(b, block.text) },
                    )
                    is Block.Photo -> Column(
                        Modifier.fillMaxWidth().clickable { onPhoto(b, block.annotation, block.markup) },
                    ) {
                        Thumb(
                            key = block.sha256, edge = 600,
                            modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(8.dp)),
                        ) { vaultId?.let { context.vaults.open(it).photoPath(block.sha256, block.ext) } }
                        if (block.annotation.isNotBlank()) {
                            Text(block.annotation, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 4.dp))
                        }
                        block.markup.forEach { m ->
                            Text("✎ $m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoDialog(t: EditTarget.PhotoBlock, onDismiss: () -> Unit, onSave: (String, List<String>) -> Unit) {
    var annotation by remember { mutableStateOf(t.annotation) }
    var markup by remember { mutableStateOf(t.markup.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Photo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(annotation, { annotation = it }, label = { Text("Annotation") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    markup, { markup = it }, label = { Text("Markup (one per line)") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(annotation.trim(), markup.lines().map { it.trim() }.filter { it.isNotEmpty() })
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** After `end log`: Committed, then Pushed (or queued), then back to the project. */
@Composable
private fun SavedResult(save: SaveStatus, onDone: () -> Unit) {
    LaunchedEffect(save) {
        if (save.done) {
            delay(if (save is SaveStatus.Failed) 4000 else 1500)
            onDone()
        }
    }
    val (title, detail, color) = when (save) {
        SaveStatus.Saving -> Triple("Saving…", null, BannerColors.Standby)
        SaveStatus.NothingToSave -> Triple("Nothing to save", "The log was empty.", BannerColors.Standby)
        SaveStatus.Discarded -> Triple("Discarded", null, BannerColors.Standby)
        is SaveStatus.Committed -> Triple(
            "Committed",
            if (save.pushing) "Pushing…" else "Saved on this phone (no remote set up).",
            BannerColors.Saved,
        )
        is SaveStatus.Pushed -> Triple("Committed · Pushed", save.info.entry.summary, BannerColors.Saved)
        is SaveStatus.PushQueued -> Triple("Committed · push queued", save.reason, BannerColors.Saved)
        is SaveStatus.Failed -> Triple("Save failed", save.reason, BannerColors.Failed)
    }
    Column(
        Modifier.fillMaxSize().background(color).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!save.done) CircularProgressIndicator(color = Color.White)
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium)
        detail?.let { Text(it, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(top = 8.dp)) }
        if (save.done) {
            TextButton(onClick = onDone, modifier = Modifier.padding(top = 24.dp)) { Text("Done", color = Color.White) }
        }
    }
}
