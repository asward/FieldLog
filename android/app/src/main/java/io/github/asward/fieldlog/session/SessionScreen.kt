package io.github.asward.fieldlog.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.asward.fieldlog.speech.SpeechSupport
import io.github.asward.fieldlog.ui.BannerColors
import io.github.asward.fieldlog.ui.ModeBanner
import io.github.asward.fieldlog.ui.Thumb
import io.github.asward.fieldlog.vaults
import androidx.compose.ui.platform.LocalContext
import uniffi.fieldlog_core.Block

@Composable
fun SessionScreen(c: SessionController, onClose: () -> Unit) {
    var tagDialog by remember { mutableStateOf(false) }
    var typeDialog by remember { mutableStateOf(false) }
    val state = c.state
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val itemCount = state.blocks.size + 1
    LaunchedEffect(itemCount, c.partial) { listState.animateScrollToItem(itemCount - 1) }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        // Header: sticky project, mic state, close.
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(c.projectTitle, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${c.vaultName} · ${c.speechLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ListeningDot(c.micOn && c.listening)
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close session") }
        }

        ModeBanner(
            title = when {
                !c.micOn -> "Mic off"
                c.listening -> "Listening"
                else -> "Starting mic…"
            },
            color = if (c.micOn && c.listening) BannerColors.Note else BannerColors.Off,
            hint = c.partial.takeIf { it.isNotBlank() },
            level = if (c.micOn && c.listening) c.level else null,
            modifier = Modifier.padding(top = 8.dp),
        )

        c.fallback?.let { msg ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (!SpeechSupport.triggerLanguageDownload(context)) {
                        android.widget.Toast.makeText(context, "Download it in the phone's speech settings", android.widget.Toast.LENGTH_LONG).show()
                    }
                    c.fallback = null
                }) { Text("Download") }
            }
        }

        // Camera: tap to snap.
        Box(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(16.dp)).background(Color.Black)
                .clickable { c.snap() },
        ) {
            AndroidView(factory = { c.camera.previewView }, modifier = Modifier.fillMaxSize())
        }

        // Draft, in narration order, with photos inline.
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(state.blocks) { _, b ->
                when (b) {
                    is Block.Text -> Text(b.text, style = MaterialTheme.typography.bodyLarge)
                    is Block.Photo -> Thumb(
                        key = b.sha256, edge = 400,
                        modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)),
                    ) { context.vaults.open(c.vaultId).photoPath(b.sha256, b.ext) }
                }
            }
            item {
                Column {
                    if (c.partial.isNotEmpty()) {
                        Text(c.partial, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (state.blocks.isEmpty()) {
                        Text(
                            hint(c, state.settings.commandPrefix),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (state.tags.isNotEmpty()) {
                        Text(
                            state.tags.joinToString(" ") { "#$it" },
                            color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // Status line.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            val status = c.banner
                ?: c.secondsToAutoSave?.let { "Saving in ${it}s" }
                ?: state.lastCommit?.let { "Last saved: ${it.summary}" }
                ?: ""
            Text(status, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // Touch backups for every voice command.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallAction(Icons.Default.Backspace, "Scratch that") { c.scratch() }
            SmallAction(Icons.AutoMirrored.Filled.Undo, "Undo") { c.undo() }
            LargeFloatingActionButton(onClick = { c.snap() }, containerColor = MaterialTheme.colorScheme.secondary) {
                Icon(Icons.Default.PhotoCamera, "Snap", Modifier.size(36.dp))
            }
            LargeFloatingActionButton(onClick = { c.next() }) {
                Icon(Icons.Default.SkipNext, "Next entry", Modifier.size(36.dp))
            }
            SmallAction(Icons.Default.Tag, "Tag") { tagDialog = true }
            SmallAction(if (c.micOn) Icons.Default.Mic else Icons.Default.MicOff, if (c.micOn) "Mute" else "Listen") { c.toggleMic() }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { typeDialog = true }) {
                Icon(Icons.Default.Keyboard, null)
                Spacer(Modifier.width(6.dp))
                Text("Type")
            }
        }
    }

    if (tagDialog) {
        TextDialog("Add tag", "Tag", onDismiss = { tagDialog = false }) { c.tag(it) }
    }
    if (typeDialog) {
        TextDialog("Add text", "Note", multiline = true, onDismiss = { typeDialog = false }) { c.type(it) }
    }
}

private fun hint(c: SessionController, prefix: String?): String {
    val p = prefix?.let { "$it " } ?: ""
    val voice = if (c.speechAvailable) {
        "Just talk. Say “${p}snap” for a photo, “${p}next” to save, “${p}scratch that”, “${p}undo”, “${p}tag …”."
    } else {
        "Speech recognition isn't available on this device; use the buttons or Type."
    }
    return voice
}

@Composable
private fun ListeningDot(on: Boolean) {
    val color = if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
    Spacer(Modifier.width(8.dp))
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalButton(onClick = onClick, shape = CircleShape, modifier = Modifier.size(52.dp), contentPadding = PaddingValues(0.dp)) {
            Icon(icon, label)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun TextDialog(
    title: String,
    label: String,
    multiline: Boolean = false,
    initial: String = "",
    confirm: String = "Add",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text(label) },
                singleLine = !multiline, minLines = if (multiline) 3 else 1,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isNotBlank()) onConfirm(text.trim())
                onDismiss()
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
