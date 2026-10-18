@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.asward.fieldlog.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.asward.fieldlog.log.LogActivity
import io.github.asward.fieldlog.logEngine
import io.github.asward.fieldlog.sync.SyncWorker
import io.github.asward.fieldlog.userMessage
import io.github.asward.fieldlog.vaults
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.fieldlog_core.Block
import uniffi.fieldlog_core.EntryKind
import uniffi.fieldlog_core.EntrySummary
import uniffi.fieldlog_core.ImageStorage
import uniffi.fieldlog_core.LogSummary
import uniffi.fieldlog_core.OpenLogInfo
import uniffi.fieldlog_core.ProjectInfo

/** Run [block] on IO whenever [keys] change. `null` while loading. */
@Composable
fun <T> load(vararg keys: Any?, block: () -> T): State<Result<T>?> =
    produceState<Result<T>?>(null, *keys) {
        value = withContext(Dispatchers.IO) { runCatching(block) }
    }

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

private fun parseTime(rfc3339: String?): OffsetDateTime? = runCatching { OffsetDateTime.parse(rfc3339) }.getOrNull()

fun prettyDate(rfc3339: String?): String =
    parseTime(rfc3339)?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
        ?: (rfc3339 ?: "")

/** "10:35" in the log's own offset. */
fun prettyTime(rfc3339: String?): String =
    parseTime(rfc3339)?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) ?: ""

/** "Sun, Oct 18, 2026" style day header. */
fun prettyDay(rfc3339: String?): String =
    parseTime(rfc3339)?.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")) ?: ""

private fun dayKey(rfc3339: String?): String = parseTime(rfc3339)?.toLocalDate()?.toString() ?: ""

@Composable
fun BackBar(nav: Nav, title: String, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = { actions() },
    )
}

/** "Log open … Resume" — shown until the open log is ended. */
@Composable
private fun OpenLogCard(vaultId: String, open: OpenLogInfo, title: String) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(if (open.editing) "Editing a log" else "Log open", style = MaterialTheme.typography.labelMedium)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                "Started ${prettyDate(open.started)} · ${open.entryCount} entr${if (open.entryCount == 1u) "y" else "ies"} · not saved yet",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { context.startActivity(LogActivity.resumeIntent(context, vaultId)) }) { Text("Resume") }
        }
    }
}

// ---------------------------------------------------------------- projects

private data class HomeData(
    val vaultName: String,
    val projects: List<ProjectInfo>,
    val active: String?,
    val hasRemote: Boolean,
    val unpushed: UInt,
    val pendingUploads: UInt,
    val localOnly: Boolean,
    val lastSync: String?,
    val open: OpenLogInfo?,
)

@Composable
fun HomeScreen(nav: Nav) {
    val context = LocalContext.current
    val vaults = context.vaults
    val scope = rememberCoroutineScope()
    val version by nav.version
    val all = remember(version) { vaults.list() }
    val currentId = remember(version) { vaults.currentId }
    var newProject by remember { mutableStateOf(false) }
    var vaultMenu by remember { mutableStateOf(false) }
    val engineOpen = context.logEngine.isOpen

    if (currentId == null) {
        Welcome(nav)
        return
    }

    val data by load(version, currentId, engineOpen) {
        val v = vaults.open(currentId)
        HomeData(
            vaultName = v.config().name,
            projects = v.projects(),
            active = vaults.activeProject(currentId),
            hasRemote = v.hasRemote(),
            unpushed = v.unpushedCommits(),
            pendingUploads = v.photoBackup().count,
            localOnly = v.config().imageStorage == ImageStorage.LOCAL,
            lastSync = vaults.lastSync(currentId),
            open = v.openLog(),
        )
    }
    val d = data?.getOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Row(Modifier.clickable { vaultMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                            Text(d?.vaultName ?: "FieldLog")
                            Icon(Icons.Default.ArrowDropDown, "Switch vault")
                        }
                        DropdownMenu(expanded = vaultMenu, onDismissRequest = { vaultMenu = false }) {
                            all.forEach { rec ->
                                DropdownMenuItem(
                                    text = { Text(rec.name) },
                                    onClick = {
                                        vaults.currentId = rec.id
                                        vaultMenu = false
                                        nav.changed()
                                    },
                                    leadingIcon = {
                                        Icon(if (rec.id == currentId) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null)
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Add vault…") },
                                onClick = { vaultMenu = false; nav.push(Screen.VaultSetup(null)) },
                                leadingIcon = { Icon(Icons.Default.Add, null) },
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { nav.push(Screen.Search) }) { Icon(Icons.Default.Search, "Search") }
                    if (d?.hasRemote == true) {
                        IconButton(onClick = {
                            SyncWorker.enqueue(context, currentId, 0)
                            context.toast("Syncing…")
                        }) { Icon(Icons.Default.Sync, "Sync now") }
                    }
                    IconButton(onClick = { nav.push(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            val active = d?.projects?.firstOrNull { it.slug == d.active }
            if (active != null && d.open == null) {
                ExtendedFloatingActionButton(
                    onClick = { context.startActivity(LogActivity.newLogIntent(context, currentId, active.slug)) },
                    icon = { Icon(Icons.Default.Mic, null) },
                    text = { Text("New log · ${active.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 96.dp)) {
            data?.exceptionOrNull()?.let { e ->
                item { Text(e.userMessage(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            }
            if (d != null) {
                item { SyncLine(d) }
                d.open?.let { open ->
                    item {
                        OpenLogCard(currentId, open, d.projects.firstOrNull { it.slug == open.project }?.title ?: open.project)
                    }
                }
                if (!vaults.speechChecked) {
                    item {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Check speech on this phone", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "A one-minute test finds the fastest speech setup that works here.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row {
                                    TextButton(onClick = { nav.push(Screen.Speech) }) { Text("Run check") }
                                    TextButton(onClick = { vaults.dismissSpeechCheck(); nav.changed() }) { Text("Not now") }
                                }
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Projects", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { newProject = true }) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(4.dp))
                            Text("New")
                        }
                    }
                }
                if (d.projects.isEmpty()) {
                    item {
                        Text(
                            "Create a project for a body of work, like a deck rebuild or a new app idea. " +
                                "Tap a project to make it active and see its logs.",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(d.projects, key = { it.slug }) { p ->
                    val isActive = p.slug == d.active
                    ListItem(
                        modifier = Modifier.clickable {
                            vaults.setActiveProject(currentId, p.slug)
                            nav.push(Screen.Project(p.slug, p.title))
                        },
                        headlineContent = { Text(p.title, fontWeight = if (isActive) FontWeight.Bold else null) },
                        supportingContent = {
                            Text(
                                if (p.entryCount == 0u) "No logs yet"
                                else "${p.entryCount} log${if (p.entryCount == 1u) "" else "s"} · last ${prettyDate(p.lastEntry)}",
                            )
                        },
                        trailingContent = {
                            if (isActive) {
                                Text("Active", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                            }
                        },
                    )
                }
            }
        }
    }

    if (newProject) {
        TextDialog("New project", "Name", confirm = "Create", onDismiss = { newProject = false }) { name ->
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val slug = vaults.open(currentId).createProject(name)
                        vaults.setActiveProject(currentId, slug)
                        slug
                    }
                }
                r.onFailure { context.toast(it.userMessage()) }
                SyncWorker.enqueue(context, currentId)
                nav.changed()
            }
        }
    }
}

@Composable
private fun SyncLine(d: HomeData) {
    val parts = buildList {
        if (!d.hasRemote) add("Local only — add a git remote in settings to sync")
        else {
            d.lastSync?.let(::add)
            if (d.unpushed > 0u) add("${d.unpushed} to push")
        }
        if (d.pendingUploads > 0u) {
            add(if (d.localOnly) "${d.pendingUploads} photos only on this phone" else "${d.pendingUploads} photos to upload")
        }
    }
    if (parts.isNotEmpty()) {
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Welcome(nav: Nav) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("FieldLog", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Talk while you work. Snap photos. Logs land as Markdown in a git repo you own.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = { nav.push(Screen.VaultSetup(null)) }, modifier = Modifier.fillMaxWidth()) {
            Text("Set up a vault")
        }
    }
}

// ---------------------------------------------------------------- project feed

@Composable
fun ProjectScreen(nav: Nav, slug: String, title: String) {
    val context = LocalContext.current
    val vaults = context.vaults
    val version by nav.version
    val id = vaults.currentId ?: return
    val engineOpen = context.logEngine.isOpen
    val data by load(version, slug, engineOpen) {
        val v = vaults.open(id)
        v.listLogs(slug, 0u) to v.openLog()
    }
    val logs: List<LogSummary> = data?.getOrNull()?.first.orEmpty()
    val open: OpenLogInfo? = data?.getOrNull()?.second
    Scaffold(
        topBar = { BackBar(nav, title) },
        floatingActionButton = {
            if (open == null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        vaults.setActiveProject(id, slug)
                        context.startActivity(LogActivity.newLogIntent(context, id, slug))
                    },
                    icon = { Icon(Icons.Default.Mic, null) },
                    text = { Text("New log") },
                )
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 96.dp),
        ) {
            data?.exceptionOrNull()?.let { e ->
                item { Text(e.userMessage(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            }
            open?.let { o -> item { OpenLogCard(id, o, if (o.project == slug) title else o.project) } }
            if (data != null && logs.isEmpty()) {
                item { Text("No logs yet. Tap New log and start talking.", modifier = Modifier.padding(16.dp)) }
            }
            // Blog-style: newest first, grouped by day.
            var lastDay = ""
            logs.forEach { log ->
                val day = dayKey(log.started)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day:$day") {
                        Text(
                            prettyDay(log.started),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                }
                item(key = log.path) { LogCard(id, log) { nav.push(Screen.Log(log.path)) } }
            }
        }
    }
}

@Composable
private fun LogCard(vaultId: String, log: LogSummary, onClick: () -> Unit) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val range = listOfNotNull(prettyTime(log.started), log.ended?.let { prettyTime(it) })
                    .distinct().joinToString("–")
                Text(range, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (log.conflict) {
                    Text("Conflict copy", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            if (log.summary.isNotBlank()) {
                Text(log.summary, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            } else if (log.photoCount == 0u) {
                Text("(empty)", fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (log.previewPhotos.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    log.previewPhotos.forEach { p ->
                        Thumb(
                            key = p.sha256, edge = 300,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                        ) { context.vaults.open(vaultId).fetchPhoto(p.sha256, p.ext, context.vaults.s3Credentials(vaultId)) }
                    }
                    val more = log.photoCount.toInt() - log.previewPhotos.size
                    Text(
                        if (more > 0) "+$more" else if (log.photoCount == 1u) "1 photo" else "${log.photoCount} photos",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (log.tags.isNotEmpty()) {
                Text(
                    log.tags.joinToString("  ") { "#$it" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- one log

@Composable
fun LogViewScreen(nav: Nav, path: String) {
    val context = LocalContext.current
    val vaults = context.vaults
    val scope = rememberCoroutineScope()
    val version by nav.version
    val id = vaults.currentId ?: return
    var confirmRemove by remember { mutableStateOf(false) }
    val log by load(version, path) { vaults.open(id).readLog(path) }
    val l = log?.getOrNull()
    Scaffold(topBar = {
        BackBar(nav, l?.let { prettyDay(it.started) } ?: "") {
            if (l != null) {
                IconButton(onClick = { context.startActivity(LogActivity.editIntent(context, id, path)) }) {
                    Icon(Icons.Default.Edit, "Edit log")
                }
                IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Default.Delete, "Remove log") }
            }
        }
    }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = pad.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            log?.exceptionOrNull()?.let { item { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) } }
            if (l != null) {
                item {
                    Column {
                        Text(
                            listOfNotNull(prettyTime(l.started), l.ended?.let { prettyTime(it) }).distinct().joinToString("–"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (l.conflict) {
                            Text(
                                "This is this device's copy, kept because the log was also edited elsewhere.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (l.tags.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                l.tags.forEach { Text("#$it", color = MaterialTheme.colorScheme.secondary) }
                            }
                        }
                    }
                }
                items(l.entries) { e ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${prettyTime(e.time)} · ${if (e.kind == EntryKind.NOTE) "Note" else "Snap"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Consecutive text blocks read as one paragraph.
                        val groups = mutableListOf<MutableList<Block>>()
                        e.blocks.forEach { b ->
                            if (b is Block.Text && groups.lastOrNull()?.lastOrNull() is Block.Text) groups.last().add(b)
                            else groups.add(mutableListOf(b))
                        }
                        groups.forEach { g ->
                            when (val first = g.first()) {
                                is Block.Text -> Text(
                                    g.filterIsInstance<Block.Text>().joinToString(" ") { it.text },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                is Block.Photo -> Column {
                                    Thumb(
                                        key = first.sha256, edge = 1600,
                                        modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(12.dp)),
                                    ) { vaults.open(id).fetchPhoto(first.sha256, first.ext, vaults.s3Credentials(id)) }
                                    if (first.annotation.isNotBlank()) {
                                        Text(first.annotation, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 6.dp))
                                    }
                                    first.markup.forEach { m ->
                                        Text("✎ $m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove this log?") },
            text = { Text("It's removed in a new commit; git history keeps it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { vaults.open(id).revertLog(path) } }
                        r.onSuccess {
                            SyncWorker.enqueue(context, id)
                            nav.changed()
                            nav.pop()
                        }.onFailure { context.toast(it.userMessage()) }
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- search

@Composable
fun EntryRow(e: EntrySummary, snippet: String? = null, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        overlineContent = { Text("${prettyDate(e.created)} · ${e.project}") },
        headlineContent = {
            Text(snippet ?: e.summary.ifEmpty { "(photos)" }, maxLines = 3, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val bits = buildList {
                if (e.photoCount > 0u) add(if (e.photoCount == 1u) "1 photo" else "${e.photoCount} photos")
                addAll(e.tags.map { "#$it" })
            }
            if (bits.isNotEmpty()) Text(bits.joinToString("  "))
        },
    )
}

@Composable
fun SearchScreen(nav: Nav) {
    val context = LocalContext.current
    val vaults = context.vaults
    val id = vaults.currentId ?: return
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val hits by load(submitted) {
        if (submitted.isBlank()) emptyList() else vaults.open(id).search(submitted, null, null, 100u)
    }
    Scaffold(topBar = { BackBar(nav, "Search") }) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Words, #tags or project names") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitted = query }),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            val list = hits?.getOrNull().orEmpty()
            if (submitted.isNotBlank() && hits != null && list.isEmpty()) {
                Text("No matches.", modifier = Modifier.padding(16.dp))
            }
            LazyColumn {
                items(list, key = { it.entry.path }) { h ->
                    EntryRow(h.entry, snippet = h.snippet) { nav.push(Screen.Log(h.entry.path)) }
                }
            }
        }
    }
}

@Composable
fun OutlinedAction(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}
