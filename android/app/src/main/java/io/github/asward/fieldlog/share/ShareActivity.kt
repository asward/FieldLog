package io.github.asward.fieldlog.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.asward.fieldlog.media.Photos
import io.github.asward.fieldlog.sync.SyncWorker
import io.github.asward.fieldlog.ui.FieldLogTheme
import io.github.asward.fieldlog.ui.MainActivity
import io.github.asward.fieldlog.userMessage
import io.github.asward.fieldlog.vaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.fieldlog_core.ProjectInfo
import uniffi.fieldlog_core.commitShared

/** Share sheet target: text, links and images. One tap saves a one-note log to the active project. */
class ShareActivity : ComponentActivity() {

    private data class Shared(val text: String?, val images: List<Uri>) {
        fun preview(): String = listOfNotNull(
            text,
            images.size.takeIf { it > 0 }?.let { if (it == 1) "1 image" else "$it images" },
        ).joinToString("\n")
    }

    private val saving = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = parse(intent)
        val vaultId = vaults.currentId
        if (vaultId == null) {
            Toast.makeText(this, "Set up FieldLog first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        if (shared.text.isNullOrBlank() && shared.images.isEmpty()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        lifecycleScope.launch {
            val projects = withContext(Dispatchers.IO) {
                runCatching { vaults.open(vaultId).projects() }.getOrDefault(emptyList())
            }
            if (projects.isEmpty()) {
                Toast.makeText(this@ShareActivity, "Create a project in FieldLog first", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            val active = vaults.activeProject(vaultId)
            val ordered = projects.sortedByDescending { it.slug == active }
            setContent {
                FieldLogTheme {
                    Surface(shape = MaterialTheme.shapes.extraLarge) {
                        ShareSheet(shared.preview(), ordered, saving.value, onCancel = { finish() }) { project, note ->
                            saving.value = true
                            save(vaultId, project, shared, note)
                        }
                    }
                }
            }
        }
    }

    private fun save(vaultId: String, project: ProjectInfo, shared: Shared, note: String) {
        val app = applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val images = shared.images.mapNotNull { uri ->
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { Photos.normalize(it) }
                    }
                    val text = listOfNotNull(note.trim().ifEmpty { null }, shared.text).joinToString("\n\n")
                    commitShared(vaults.open(vaultId), project.slug, text.ifEmpty { null }, images, emptyList())
                }
            }
            result.onSuccess {
                vaults.touchRecent(vaultId, project.slug)
                SyncWorker.enqueue(app, vaultId)
                Toast.makeText(app, "Saved to ${project.title}", Toast.LENGTH_SHORT).show()
                finish()
            }.onFailure {
                saving.value = false
                Toast.makeText(app, it.userMessage(), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parse(intent: Intent): Shared {
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()
        val raw = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
        val isUrl = raw != null && !raw.contains(Regex("\\s")) && Regex("^https?://").containsMatchIn(raw)
        val text = when {
            raw.isNullOrEmpty() -> subject
            isUrl && !subject.isNullOrEmpty() -> "[$subject]($raw)"
            isUrl -> "<$raw>"
            else -> raw
        }
        val images = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamExtra(intent))
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra(intent)
            else -> emptyList()
        }.filter { uri ->
            (contentResolver.getType(uri) ?: intent.type ?: "").startsWith("image/")
        }
        return Shared(text, images)
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
}

@Composable
private fun ShareSheet(
    preview: String,
    projects: List<ProjectInfo>,
    saving: Boolean,
    onCancel: () -> Unit,
    onSave: (ProjectInfo, String) -> Unit,
) {
    var selected by remember { mutableStateOf(projects.first()) }
    var note by remember { mutableStateOf("") }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Save to FieldLog", style = MaterialTheme.typography.titleLarge)
        Text(preview, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            projects.forEach { p ->
                FilterChip(selected = p == selected, onClick = { selected = p }, label = { Text(p.title) })
            }
        }
        OutlinedTextField(
            note, { note = it }, label = { Text("Note (optional)") },
            modifier = Modifier.fillMaxWidth(), minLines = 2,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(enabled = !saving, onClick = { onSave(selected, note) }) { Text("Save") }
        }
    }
}
