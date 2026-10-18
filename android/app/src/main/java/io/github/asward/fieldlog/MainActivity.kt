package io.github.asward.fieldlog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import uniffi.fieldlog_core.libgitInfo
import uniffi.fieldlog_core.spikeLocalCommit
import uniffi.fieldlog_core.spikeLsRemote

/** Spike 0: proves the Rust core + libgit2 + SSH run on the device. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { SpikeScreen(filesDir) }
            }
        }
    }
}

@Composable
private fun SpikeScreen(filesDir: File) {
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("git@github.com:you/notes.git") }
    var key by remember { mutableStateOf("") }

    fun run(label: String, block: () -> String) {
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                try { block() } catch (e: Throwable) { "ERROR ${e::class.simpleName}: ${e.message}" }
            }
            log = "$label\n$out\n\n$log"
        }
    }

    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("FieldLog — Spike 0", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { run("libgit2") { libgitInfo().toString() } }) { Text("Core info") }
        Button(onClick = {
            run("local commit") { spikeLocalCommit(File(filesDir, "spike-repo").absolutePath) }
        }) { Text("Local commit") }
        OutlinedTextField(url, { url = it }, label = { Text("SSH remote") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            key, { key = it }, label = { Text("Private key (PEM / OpenSSH)") },
            modifier = Modifier.fillMaxWidth(), minLines = 3,
        )
        Button(onClick = { run("ls-remote") {
            val refs = spikeLsRemote(url, key, null)
            if (refs.isEmpty()) "Connected and authenticated. 0 refs (the repo is empty)." else "OK, ${refs.size} refs:\n" + refs.joinToString("\n")
        } }) {
            Text("ls-remote over SSH")
        }
        Text(log, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}
