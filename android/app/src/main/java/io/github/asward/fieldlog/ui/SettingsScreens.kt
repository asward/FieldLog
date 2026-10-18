@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.asward.fieldlog.ui

import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.asward.fieldlog.data.ConnectionFile
import io.github.asward.fieldlog.data.PhotoExport
import io.github.asward.fieldlog.feedback.Cue
import io.github.asward.fieldlog.feedback.Feedback
import io.github.asward.fieldlog.data.VaultSecrets
import io.github.asward.fieldlog.sync.SyncWorker
import io.github.asward.fieldlog.userMessage
import io.github.asward.fieldlog.vaults
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.fieldlog_core.Author
import uniffi.fieldlog_core.ImageStorage
import uniffi.fieldlog_core.S3Config
import uniffi.fieldlog_core.generateSshKey
import uniffi.fieldlog_core.isHostedGitRemote
import uniffi.fieldlog_core.libgitInfo

@Composable
private fun Section(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen(nav: Nav) {
    val context = LocalContext.current
    val vaults = context.vaults
    val version by nav.version
    val author = remember { vaults.author }
    var name by remember { mutableStateOf(author.name) }
    var email by remember { mutableStateOf(author.email) }
    var idle by remember { mutableStateOf(vaults.idleMinutes.toFloat()) }
    var volumeKeys by remember { mutableStateOf(vaults.volumeKeys) }
    var fbSound by remember { mutableStateOf(vaults.feedbackSound) }
    var fbVolume by remember { mutableStateOf(vaults.feedbackVolume) }
    var fbHaptics by remember { mutableStateOf(vaults.feedbackHaptics) }
    val feedback = remember { Feedback(context) }
    DisposableEffect(Unit) { onDispose { feedback.release() } }
    val list = remember(version) { vaults.list() }

    fun save() {
        vaults.author = Author(name.trim().ifEmpty { "FieldLog" }, email.trim().ifEmpty { "fieldlog@localhost" })
        vaults.idleMinutes = idle.roundToInt()
        vaults.volumeKeys = volumeKeys
        vaults.feedbackSound = fbSound
        vaults.feedbackVolume = fbVolume
        vaults.feedbackHaptics = fbHaptics
    }

    Scaffold(topBar = {
        BackBar(nav, "Settings") { TextButton(onClick = { save(); nav.pop() }) { Text("Save") } }
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Section("Voice")
            ListItem(
                modifier = Modifier.clickable { save(); nav.push(Screen.Speech) },
                headlineContent = { Text("Speech recognition") },
                supportingContent = { Text("${vaults.speechSetup.label} · run the speech check") },
            )
            ListItem(
                modifier = Modifier.clickable { save(); nav.push(Screen.Phrases) },
                headlineContent = { Text("Command phrases") },
                supportingContent = { Text("What to say for note, end note, snap, end log…") },
            )
            val mins = idle.roundToInt()
            Text("Stop the mic after $mins min without speech", modifier = Modifier.padding(top = 12.dp))
            Text("The log stays open; a notification lets you resume or end it.", style = MaterialTheme.typography.bodySmall)
            Slider(value = idle, onValueChange = { idle = it }, valueRange = 5f..60f, steps = 10)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Volume keys on the log screen")
                    Text("Up takes a photo, down mutes or unmutes the mic.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = volumeKeys, onCheckedChange = { volumeKeys = it })
            }

            Section("Feedback")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sounds")
                    Text("A short sound for every mode change and save.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = fbSound, onCheckedChange = { fbSound = it })
            }
            if (fbSound) {
                Text("Sound volume", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                Slider(value = fbVolume, onValueChange = { fbVolume = it }, valueRange = 0.1f..1f)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Vibration")
                    Text("A distinct pattern per mode, so you can tell without looking.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = fbHaptics, onCheckedChange = { fbHaptics = it })
            }
            TextButton(onClick = {
                save()
                feedback.play(Cue.ENTER_NOTE)
            }) { Text("Try it") }

            Section("Commit author")
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                email, { email = it }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )

            Section("Vaults")
            list.forEach { rec ->
                ListItem(
                    modifier = Modifier.clickable { save(); nav.push(Screen.VaultSetup(rec.id)) },
                    headlineContent = { Text(rec.name) },
                    supportingContent = { Text(rec.remoteUrl ?: "Local only") },
                )
            }
            OutlinedButton(onClick = { save(); nav.push(Screen.VaultSetup(null)) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Text("Add vault")
            }

            Section("About")
            val info = remember { runCatching { libgitInfo() }.getOrNull() }
            Text(
                "FieldLog core ${info?.coreVersion ?: "?"} · libgit2 ${info?.libgit2Version ?: "?"} · " +
                    "ssh ${info?.ssh} · https ${info?.https}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 32.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- vault setup

@Composable
fun VaultSetupScreen(nav: Nav, vaultId: String?) {
    val context = LocalContext.current
    val vaults = context.vaults
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val editing = vaultId != null
    val id = remember { vaultId ?: UUID.randomUUID().toString() }
    val rec = remember { vaultId?.let(vaults::get) }
    val secrets = remember { vaultId?.let(vaults::secretsFor) ?: VaultSecrets() }
    val config = remember { if (editing) runCatching { vaults.open(id).config() }.getOrNull() else null }

    var name by remember { mutableStateOf(rec?.name ?: "") }
    var useRemote by remember { mutableStateOf(rec?.remoteUrl != null || !editing) }
    var url by remember { mutableStateOf(rec?.remoteUrl ?: "") }
    var useHttps by remember { mutableStateOf(secrets.httpsToken != null) }
    var privateKey by remember { mutableStateOf(secrets.sshPrivateKey ?: "") }
    var publicKey by remember { mutableStateOf(secrets.sshPublicKey ?: "") }
    var passphrase by remember { mutableStateOf(secrets.sshPassphrase ?: "") }
    var showPaste by remember { mutableStateOf(false) }
    var httpsUser by remember { mutableStateOf(secrets.httpsUsername ?: "") }
    var httpsToken by remember { mutableStateOf(secrets.httpsToken ?: "") }
    var storage by remember { mutableStateOf(config?.imageStorage ?: ImageStorage.LOCAL) }
    val s3Mode = storage == ImageStorage.S3
    val backup = remember { if (editing) runCatching { vaults.open(id).photoBackup() }.getOrNull() else null }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { PhotoExport.write(context, id, uri) } }
                r.onSuccess { n -> context.toast(if (n == 1) "Exported 1 photo" else "Exported $n photos") }
                    .onFailure { context.toast(it.userMessage()) }
            }
        }
    }
    var s3Endpoint by remember { mutableStateOf(config?.s3?.endpoint ?: "https://s3.us-east-1.amazonaws.com") }
    var s3Region by remember { mutableStateOf(config?.s3?.region ?: "us-east-1") }
    var s3Bucket by remember { mutableStateOf(config?.s3?.bucket ?: "") }
    var s3Prefix by remember { mutableStateOf(config?.s3?.prefix ?: "") }
    var s3PathStyle by remember { mutableStateOf(config?.s3?.pathStyle ?: false) }
    var s3Key by remember { mutableStateOf(secrets.s3AccessKey ?: "") }
    var s3Secret by remember { mutableStateOf(secrets.s3SecretKey ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val connectionOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { ConnectionFile.export(context, id, uri) } }
                r.onSuccess { context.toast("Saved. It contains your private key: keep it private, delete it after importing.") }
                    .onFailure { context.toast(it.userMessage()) }
            }
        }
    }
    val connectionIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            error = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { ConnectionFile.import(context, uri) } }
                busy = false
                r.onSuccess { name ->
                    context.toast("Connected “$name”")
                    nav.changed()
                    nav.home()
                }.onFailure { error = it.userMessage() }
            }
        }
    }

    fun currentSecrets() = VaultSecrets(
        sshPrivateKey = privateKey.trim().ifEmpty { null }.takeIf { !useHttps },
        sshPublicKey = publicKey.trim().ifEmpty { null }.takeIf { !useHttps },
        sshPassphrase = passphrase.ifEmpty { null }.takeIf { !useHttps },
        httpsUsername = httpsUser.trim().ifEmpty { null }.takeIf { useHttps },
        httpsToken = httpsToken.trim().ifEmpty { null }.takeIf { useHttps },
        s3AccessKey = s3Key.trim().ifEmpty { null },
        s3SecretKey = s3Secret.trim().ifEmpty { null },
    )

    fun work(block: () -> Unit) {
        busy = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            busy = false
            r.onSuccess {
                nav.changed()
                nav.home()
            }.onFailure { error = it.userMessage() }
        }
    }

    Scaffold(topBar = { BackBar(nav, if (editing) rec?.name ?: "Vault" else "New vault") }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!editing) {
                OutlinedButton(enabled = !busy, onClick = { connectionIn.launch(arrayOf("application/json", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Import connection file")
                }
                Text(
                    "Reconnect a vault exported from another install (remote, keys, pinned host key); no new deploy key needed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Vault name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sync with a git remote", modifier = Modifier.weight(1f))
                Switch(checked = useRemote, onCheckedChange = { useRemote = it })
            }
            if (useRemote) {
                OutlinedTextField(
                    url, { url = it; if (it.startsWith("https://")) useHttps = true else if (it.startsWith("git@") || it.startsWith("ssh://")) useHttps = false },
                    label = { Text("Remote URL") }, singleLine = true,
                    placeholder = { Text("git@github.com:you/notes.git") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !useHttps, onClick = { useHttps = false }, label = { Text("SSH key") })
                    FilterChip(selected = useHttps, onClick = { useHttps = true }, label = { Text("HTTPS token") })
                }
                if (!useHttps) {
                    if (publicKey.isNotEmpty()) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Add this as a deploy key with write access on your git host:", style = MaterialTheme.typography.bodySmall)
                                Text(publicKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                                TextButton(onClick = { clipboard.setText(AnnotatedString(publicKey)) }) { Text("Copy public key") }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            runCatching { generateSshKey("fieldlog@${Build.MODEL.replace(' ', '-')}") }
                                .onSuccess { k ->
                                    privateKey = k.privateOpenssh
                                    publicKey = k.publicOpenssh
                                    passphrase = ""
                                }
                                .onFailure { error = it.userMessage() }
                        }) { Text(if (privateKey.isEmpty()) "Generate key" else "New key") }
                        OutlinedButton(onClick = { showPaste = !showPaste }) { Text("Paste key") }
                    }
                    if (showPaste) {
                        OutlinedTextField(
                            privateKey, { privateKey = it; publicKey = "" }, label = { Text("Private key (OpenSSH/PEM)") },
                            minLines = 4, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            passphrase, { passphrase = it }, label = { Text("Passphrase (if any)") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    OutlinedTextField(httpsUser, { httpsUser = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        httpsToken, { httpsToken = it }, label = { Text("Token / password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (rec?.hostKey != null) {
                    Text("Pinned SSH host key: ${rec.hostKey.take(16)}…", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vaults.update(rec.copy(hostKey = null)); context.toast("Host key forgotten") }) {
                        Text("Forget host key")
                    }
                }
            }

            if (editing) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Photo storage", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = storage == ImageStorage.LOCAL, onClick = { storage = ImageStorage.LOCAL }, label = { Text("This phone") })
                    FilterChip(selected = storage == ImageStorage.S3, onClick = { storage = ImageStorage.S3 }, label = { Text("S3 / R2") })
                    FilterChip(selected = storage == ImageStorage.GIT, onClick = { storage = ImageStorage.GIT }, label = { Text("Git") })
                }
                Text(
                    when (storage) {
                        ImageStorage.LOCAL -> "Photos stay on this phone and are queued for upload. When you set up S3 or R2, " +
                            "they upload with the same names, so nothing in the repo changes."
                        ImageStorage.S3 -> "Photos upload to your bucket (S3, Cloudflare R2, MinIO…) before every push and stay out of git."
                        ImageStorage.GIT -> "Photos are committed to the repo with each log. Best for self-hosted remotes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (storage == ImageStorage.GIT && useRemote && isHostedGitRemote(url)) {
                    Text(
                        "This remote has a storage quota. Committing photos will fill it quickly; " +
                            "“This phone” or S3 / R2 is a better fit.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (backup != null && backup.count > 0u) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${backup.count} photo${if (backup.count == 1u) "" else "s"} not backed up " +
                                    "(${Formatter.formatShortFileSize(context, backup.bytes.toLong())})",
                            )
                            Text(
                                "They live only in this app's storage. Uninstalling the app or clearing its data deletes them.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { exporter.launch("fieldlog-photos.zip") }) { Text("Export photos") }
                        }
                    }
                }
                if (s3Mode) {
                    OutlinedTextField(s3Endpoint, { s3Endpoint = it }, label = { Text("Endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(s3Region, { s3Region = it }, label = { Text("Region") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(s3Bucket, { s3Bucket = it }, label = { Text("Bucket") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(s3Prefix, { s3Prefix = it }, label = { Text("Key prefix (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Path-style URLs (MinIO, most self-hosted)", modifier = Modifier.weight(1f))
                        Switch(checked = s3PathStyle, onCheckedChange = { s3PathStyle = it })
                    }
                    OutlinedTextField(s3Key, { s3Key = it }, label = { Text("Access key ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        s3Secret, { s3Secret = it }, label = { Text("Secret access key") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                enabled = !busy && (!useRemote || url.isNotBlank()),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                onClick = {
                    if (!editing) {
                        work {
                            if (useRemote) {
                                vaults.setSecrets(id, currentSecrets())
                                vaults.cloneRemote(id, name, url.trim())
                            } else {
                                vaults.createLocal(name)
                            }
                        }
                    } else {
                        work {
                            vaults.setSecrets(id, currentSecrets())
                            vaults.setRemote(id, if (useRemote) url else null)
                            val v = vaults.open(id)
                            val cfg = v.config()
                            val newCfg = cfg.copy(
                                imageStorage = storage,
                                s3 = if (s3Mode) S3Config(s3Endpoint.trim(), s3Region.trim(), s3Bucket.trim(), s3Prefix.trim(), s3PathStyle) else cfg.s3,
                            )
                            if (newCfg != cfg) v.setConfig(newCfg)
                            if (useRemote) SyncWorker.enqueue(context, id, 0)
                        }
                    }
                },
            ) {
                Text(
                    when {
                        busy -> "Working…"
                        editing -> "Save"
                        useRemote -> "Connect"
                        else -> "Create vault"
                    },
                )
            }
            if (!editing && useRemote) {
                Text(
                    "Connect clones the repo. An empty repo becomes a new vault.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (editing && rec?.remoteUrl != null) {
                OutlinedButton(onClick = { connectionOut.launch("fieldlog-connection-${rec.name}.json") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Export connection file")
                }
                Text(
                    "For reinstalling: saves the remote, keys and pinned host key (not settings). The file contains your private key.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (editing) {
                TextButton(onClick = { confirmRemove = true }) {
                    Text("Remove from this device", color = MaterialTheme.colorScheme.error)
                }
            }
            Text("", modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove vault?") },
            text = { Text("Deletes this device's copy and its saved keys. Anything not yet pushed is lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    work { vaults.remove(id) }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
