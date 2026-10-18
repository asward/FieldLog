@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.asward.fieldlog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.asward.fieldlog.logEngine
import io.github.asward.fieldlog.vaults
import uniffi.fieldlog_core.VoicePhrases
import uniffi.fieldlog_core.defaultVoicePhrases

private data class PhraseField(val key: String, val label: String, val get: (VoicePhrases) -> List<String>)

private val FIELDS = listOf(
    PhraseField("note", "Start a note") { it.note },
    PhraseField("endNote", "End note") { it.endNote },
    PhraseField("tag", "Start tagging (or “tag pump, valve” in one go)") { it.tag },
    PhraseField("endTags", "End tags") { it.endTags },
    PhraseField("snap", "Snap a photo") { it.snap },
    PhraseField("endAnnotation", "End annotation") { it.endAnnotation },
    PhraseField("endMarkup", "End markup") { it.endMarkup },
    PhraseField("scratchThat", "Scratch that") { it.scratchThat },
    PhraseField("cancel", "Cancel") { it.cancel },
    PhraseField("undo", "Undo (standby)") { it.undo },
    PhraseField("endLog", "End log") { it.endLog },
)

/** Settings → Command phrases: comma-separated phrases per command. */
@Composable
fun PhrasesScreen(nav: Nav) {
    val context = LocalContext.current
    val vaults = context.vaults
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            val p = vaults.voicePhrases
            FIELDS.forEach { put(it.key, it.get(p).joinToString(", ")) }
        }
    }

    fun parsed(key: String, fallback: List<String>): List<String> =
        values[key].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { fallback }

    fun save() {
        val d = defaultVoicePhrases()
        vaults.voicePhrases = VoicePhrases(
            note = parsed("note", d.note),
            endNote = parsed("endNote", d.endNote),
            tag = parsed("tag", d.tag),
            endTags = parsed("endTags", d.endTags),
            snap = parsed("snap", d.snap),
            endAnnotation = parsed("endAnnotation", d.endAnnotation),
            endMarkup = parsed("endMarkup", d.endMarkup),
            scratchThat = parsed("scratchThat", d.scratchThat),
            cancel = parsed("cancel", d.cancel),
            undo = parsed("undo", d.undo),
            endLog = parsed("endLog", d.endLog),
        )
        context.logEngine.refreshPhrases()
    }

    Scaffold(topBar = {
        BackBar(nav, "Command phrases") { TextButton(onClick = { save(); nav.pop() }) { Text("Save") } }
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Separate alternatives with commas. A command fires when it's the whole phrase you say; " +
                    "“end …” commands also work at the end of a sentence. Case, punctuation and plurals don't matter.",
                style = MaterialTheme.typography.bodySmall,
            )
            FIELDS.forEach { f ->
                OutlinedTextField(
                    values[f.key].orEmpty(), { values[f.key] = it },
                    label = { Text(f.label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedButton(onClick = {
                val d = defaultVoicePhrases()
                FIELDS.forEach { values[it.key] = it.get(d).joinToString(", ") }
            }, modifier = Modifier.fillMaxWidth()) { Text("Reset to defaults") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
