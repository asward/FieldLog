@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.asward.fieldlog.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.asward.fieldlog.session.SessionActivity
import io.github.asward.fieldlog.session.TextDialog
import io.github.asward.fieldlog.sync.SyncWorker
import io.github.asward.fieldlog.userMessage
import io.github.asward.fieldlog.vaults
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.fieldlog_core.EntrySummary
import uniffi.fieldlog_core.ProjectInfo

/** Run [block] on IO whenever [keys] change. `null` while loading. */
@Composable
fun <T> load(vararg keys: Any?, block: () -> T): State<Result<T>?> =
    produceState<Result<T>?>(null, *keys) {
        value = withContext(Dispatchers.IO) { runCatching(block) }
    }

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun prettyDate(rfc3339: String?): String = runCatching {
    OffsetDateTime.parse(rfc3339).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
}.getOrDefault(rfc3339 ?: "")

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

// ---------------------------------------------------------------- home

private data class HomeData(
    val vaultName: String,
    val projects: List<ProjectInfo>,
    val sticky: String?,
    val hasRemote: Boolean,
    val unpushed: UInt,
    val pendingUploads: UInt,
    val lastSync: String?,
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

    if (currentId == null) {
        Welcome(nav)
        return
    }

    val data by load(version, currentId) {
        val v = vaults.open(currentId)
        HomeData(
            vaultName = v.config().name,
            projects = v.projects(),
            sticky = vaults.stickyProject(currentId),
            hasRemote = v.hasRemote(),
            unpushed = v.unpushedCommits(),
            pendingUploads = v.pendingUploadCount(),
            lastSync = vaults.lastSync(currentId),
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
            if (!d?.projects.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { context.startActivity(SessionActivity.intent(context, currentId)) },
                    icon = { Icon(Icons.Default.Mic, null) },
                    text = { Text("Start session") },
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
                val sticky = d.projects.firstOrNull { it.slug == d.sticky }
                if (sticky != null) {
                    item {
                        Card(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                .clickable { context.startActivity(SessionActivity.intent(context, currentId, sticky.slug)) },
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Session project", style = MaterialTheme.typography.labelMedium)
                                Text(sticky.title, style = MaterialTheme.typography.headlineSmall)
                                Text("Tap to start logging", style = MaterialTheme.typography.bodySmall)
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
                            "Create a project to start logging. Your session sticks to it until you pick another.",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(d.projects, key = { it.slug }) { p ->
                    ListItem(
                        modifier = Modifier.clickable { nav.push(Screen.Project(p.slug, p.title)) },
                        headlineContent = { Text(p.title, fontWeight = if (p.slug == d.sticky) FontWeight.Bold else null) },
                        supportingContent = {
                            Text(
                                if (p.entryCount == 0u) "No entries yet"
                                else "${p.entryCount} entries · last ${prettyDate(p.lastEntry)}",
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                vaults.setStickyProject(currentId, p.slug)
                                nav.changed()
                            }) {
                                Icon(
                                    if (p.slug == d.sticky) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    "Use for sessions",
                                    tint = if (p.slug == d.sticky) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                                )
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
                        if (vaults.stickyProject(currentId) == null) vaults.setStickyProject(currentId, slug)
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
        if (d.pendingUploads > 0u) add("${d.pendingUploads} photos to upload")
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
            "Talk while you work. Snap photos. Entries land as Markdown in a git repo you own.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = { nav.push(Screen.VaultSetup(null)) }, modifier = Modifier.fillMaxWidth()) {
            Text("Set up a vault")
        }
    }
}

// ---------------------------------------------------------------- project

@Composable
fun ProjectScreen(nav: Nav, slug: String, title: String) {
    val context = LocalContext.current
    val vaults = context.vaults
    val version by nav.version
    val id = vaults.currentId ?: return
    val entries by load(version, slug) { vaults.open(id).listEntries(slug, 0u) }
    Scaffold(
        topBar = { BackBar(nav, title) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    vaults.setStickyProject(id, slug)
                    context.startActivity(SessionActivity.intent(context, id, slug))
                },
                icon = { Icon(Icons.Default.Mic, null) },
                text = { Text("Log here") },
            )
        },
    ) { pad ->
        val list = entries?.getOrNull().orEmpty()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 96.dp)) {
            if (entries != null && list.isEmpty()) {
                item { Text("No entries yet.", modifier = Modifier.padding(16.dp)) }
            }
            items(list, key = { it.id }) { e -> EntryRow(e) { nav.push(Screen.Entry(e.path)) } }
        }
    }
}

@Composable
fun EntryRow(e: EntrySummary, snippet: String? = null, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        overlineContent = { Text(prettyDate(e.created)) },
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

// ---------------------------------------------------------------- entry

private val imageLine = Regex("""^!\[[^\]]*]\(([^)]+)\)$""")

@Composable
fun EntryScreen(nav: Nav, path: String) {
    val context = LocalContext.current
    val vaults = context.vaults
    val id = vaults.currentId ?: return
    val entry by load(path) { vaults.open(id).readEntry(path) }
    Scaffold(topBar = { BackBar(nav, entry?.getOrNull()?.let { prettyDate(it.created) } ?: "") }) { pad ->
        val e = entry?.getOrNull()
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = pad.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            entry?.exceptionOrNull()?.let { item { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) } }
            if (e != null) {
                if (e.tags.isNotEmpty()) {
                    item { Text(e.tags.joinToString("  ") { "#$it" }, color = MaterialTheme.colorScheme.secondary) }
                }
                val paragraphs = e.body.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
                items(paragraphs) { para ->
                    val m = imageLine.find(para)
                    if (m != null) {
                        val rel = m.groupValues[1]
                        val file = File(File(vaults.dir(id), path).parentFile, rel).normalize()
                        val name = file.name
                        val sha = name.substringBefore('.')
                        val ext = name.substringAfter('.', "jpg")
                        Thumb(
                            key = name, edge = 1600,
                            modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(12.dp)),
                        ) { vaults.open(id).fetchPhoto(sha, ext, vaults.s3Credentials(id)) }
                    } else {
                        Text(para, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- search

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
                items(list, key = { it.entry.id }) { h ->
                    EntryRow(h.entry, snippet = h.snippet) { nav.push(Screen.Entry(h.entry.path)) }
                }
            }
        }
    }
}

@Composable
fun OutlinedAction(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}
