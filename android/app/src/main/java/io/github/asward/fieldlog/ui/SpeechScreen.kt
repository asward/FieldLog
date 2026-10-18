@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.asward.fieldlog.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.asward.fieldlog.speech.SpeechCheck
import io.github.asward.fieldlog.speech.SpeechResult
import io.github.asward.fieldlog.speech.SpeechSetup
import io.github.asward.fieldlog.speech.SpeechSupport
import io.github.asward.fieldlog.vaults
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Settings → Speech: the per-phone speech check (spec §7). Test one setup or
 * run them all; the results list working setups fastest first, and running
 * all of them pre-selects the best.
 */
@Composable
fun SpeechScreen(nav: Nav) {
    val context = LocalContext.current
    val vaults = context.vaults
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf(vaults.speechResults) }
    var chosen by remember { mutableStateOf(vaults.speechSetup) }
    var testing by remember { mutableStateOf<SpeechSetup?>(null) }
    /** The recogniser is listening: time to speak. */
    var speakNow by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(-2f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var pending by remember { mutableStateOf<List<SpeechSetup>>(emptyList()) }

    // Leaving the screen stops a running check (and releases the mic).
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun runTests(setups: List<SpeechSetup>) {
        val all = setups.size > 1
        job = scope.launch {
            try {
                for (setup in setups) {
                    testing = setup
                    speakNow = false
                    level = -2f
                    val r = SpeechCheck.run(context, setup, ready = { speakNow = true }) { level = it }
                    results = listOf(r) + results.filter { it.setup != setup }
                    vaults.speechResults = results
                }
                if (all) {
                    SpeechResult.best(results)?.let {
                        chosen = it.setup
                        vaults.speechSetup = it.setup
                    }
                }
            } finally {
                testing = null
                speakNow = false
            }
        }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) runTests(pending) else context.toast("The speech check needs the microphone")
    }

    fun start(setups: List<SpeechSetup>) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) runTests(setups) else {
            pending = setups
            mic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(topBar = { BackBar(nav, "Speech") }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Phones differ in which speech setups work and how fast they are. Test one setup, or run them all; " +
                    "wait for “Speak now”, then say the phrase. Re-run after an OS update.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!SpeechSupport.continuousSupported()) {
                Text(
                    "This phone runs Android ${android.os.Build.VERSION.RELEASE}. Continuous listening needs Android 13, " +
                        "so only restart mode is available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val t = testing
            if (t != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Testing ${t.label}", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        if (!speakNow) {
                            Text("Getting ready… (loading the recogniser)", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            Text("Speak now: “${SpeechCheck.PROMPT}”", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            LevelMeter(
                                level,
                                Modifier.fillMaxWidth().height(10.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        TextButton(onClick = { job?.cancel() }) { Text("Stop") }
                    }
                }
            } else {
                Button(onClick = { start(SpeechSetup.ALL) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (results.isEmpty()) "Run all four" else "Run all again")
                }
            }

            Text("Setups", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            val ordered = SpeechSetup.ALL.sortedWith(
                compareBy<SpeechSetup> { s -> results.firstOrNull { it.setup == s }?.worked != true }
                    .thenBy { s -> results.firstOrNull { it.setup == s }?.latencyMs ?: Long.MAX_VALUE },
            )
            ordered.forEach { setup ->
                val r = results.firstOrNull { it.setup == setup }
                val unavailable = SpeechSupport.unavailableReason(context, setup)
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = unavailable == null) {
                        chosen = setup
                        vaults.speechSetup = setup
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = chosen == setup,
                        enabled = unavailable == null,
                        onClick = { chosen = setup; vaults.speechSetup = setup },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(setup.label, fontWeight = if (chosen == setup) FontWeight.Bold else null)
                        Text(
                            unavailable ?: r?.summary ?: "Not tested yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r?.worked == false || unavailable != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (r?.heard != null && r.worked) {
                            Text("Heard: “${r.heard}”", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (r?.needsLanguagePack == true) {
                        TextButton(onClick = {
                            if (SpeechSupport.triggerLanguageDownload(context)) {
                                context.toast("Downloading; test again when it's done")
                            } else {
                                context.toast("Download it in the phone's speech settings")
                            }
                        }) { Text("Download") }
                    }
                    if (unavailable == null) {
                        OutlinedButton(enabled = testing == null, onClick = { start(listOf(setup)) }) { Text("Test") }
                    }
                }
            }
            Text(
                "“First words” is how long after you start talking the text appears; that's what you feel while logging. " +
                    "“Ready in” is the one-off start-up when a log opens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
