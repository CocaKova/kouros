package com.cocakova.kouros.ui.servers

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cocakova.kouros.app
import com.cocakova.kouros.core.api.Bridge
import com.cocakova.kouros.core.api.LogLine
import com.cocakova.kouros.core.api.SystemStats
import com.cocakova.kouros.net.ServerSession
import com.cocakova.kouros.ui.components.Slab
import com.cocakova.kouros.ui.components.Tag
import com.cocakova.kouros.ui.theme.Atelier
import com.cocakova.kouros.ui.theme.fieldColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the desktop's settings, terminal and model library show, for one server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(serverId: String, onBack: () -> Unit) {
    val session by produceState<ServerSession?>(null, serverId) { value = app.sessions.byId(serverId) }
    var tab by rememberSaveable { mutableStateOf(0) }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(session?.server?.name ?: "Server", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                    listOf("System", "Logs", "Models").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
                }
            }
        },
    ) { pad ->
        val s = session ?: return@Scaffold
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> SystemTab(s)
                1 -> LogsTab(s)
                else -> ModelsTab(s)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemTab(s: ServerSession) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var stats by remember { mutableStateOf<SystemStats?>(null) }
    var bridge by remember { mutableStateOf<Bridge?>(null) }
    var bridgeChecked by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    LaunchedEffect(s) {
        stats = runCatching { s.client.systemStats() }.getOrNull()
        bridge = runCatching { s.client.bridge() }.getOrNull(); bridgeChecked = true
    }
    fun run(label: String, block: suspend () -> Unit) = scope.launch {
        val ok = runCatching { block() }.isSuccess
        Toast.makeText(context, if (ok) label else "Couldn't reach the server", Toast.LENGTH_SHORT).show()
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Slab(Modifier.fillMaxWidth()) {
                Text("ComfyUI", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                val st = stats
                if (st == null) Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    Info("Version", st.comfyuiVersion)
                    Info("Frontend", st.frontendVersion)
                    Info("Python", st.pythonVersion?.substringBefore(" ("))
                    Info("PyTorch", st.pytorchVersion)
                    Info("OS", st.os)
                    st.devices.forEach { d -> Info("Device", d.name) }
                    Info("Memory", st.ramTotal?.let { "%.0f GB".format(it / 1.073741824e9) })
                }
            }
        }
        stats?.argv?.drop(1)?.takeIf { it.isNotEmpty() }?.let { args ->
            item {
                Slab(Modifier.fillMaxWidth()) {
                    Text("Started with", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { args.forEach { Tag(it) } }
                }
            }
        }
        item {
            Slab(Modifier.fillMaxWidth()) {
                Text("Kouros Bridge", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        !bridgeChecked -> "Checking…"
                        bridge != null -> "Installed (v${bridge!!.version}): deleting removes files, and memory shows what's loaded."
                        else -> "Not installed. Without it, deleting only clears history (the files stay on disk) and loaded models can't be listed. " +
                            "It's a small, optional folder for ComfyUI's custom_nodes; see bridge/README.md in the Kouros repo."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bridge != null) Atelier.colors.done else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Slab(Modifier.fillMaxWidth()) {
                Text("Actions", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { run("Interrupted") { s.client.interruptAny() } }) { Text("Interrupt") }
                    OutlinedButton(onClick = { run("Unloading models") { s.client.free(unloadModels = true, freeMemory = true) } }) { Text("Unload models") }
                    OutlinedButton(onClick = { run("Freeing cache") { s.client.free(unloadModels = false, freeMemory = true) } }) { Text("Free cache") }
                    OutlinedButton(onClick = { confirm = "Clear everything waiting in the queue?" to { s.client.clearQueue() } }) { Text("Clear queue") }
                    OutlinedButton(onClick = { confirm = "Clear the server's whole history list? Output files stay on disk." to { s.client.clearHistory() } }) { Text("Clear history") }
                }
            }
        }
    }
    confirm?.let { (question, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(question) },
            confirmButton = { TextButton(onClick = { confirm = null; run("Done") { action() } }) { Text("Clear", color = Atelier.colors.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun Info(label: String, value: String?) {
    value ?: return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Terminal colour codes the server writes into its log. */
private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")

/** The server's console, refreshed while it's on screen, following the end unless scrolled back. */
@Composable
private fun LogsTab(s: ServerSession) {
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(s) {
        while (true) {
            runCatching { s.client.logs() }
                .onSuccess { l -> lines = l; error = null }
                .onFailure { error = "This server doesn't share its logs (${it.message})" }
            delay(2000)
        }
    }
    // Entries are fragments; join them into lines the way a terminal shows them.
    val text = remember(lines) {
        lines.joinToString("") { it.text }.replace(ANSI, "").split('\n').filter { it.isNotBlank() }.takeLast(2000)
    }
    val shown = remember(text, filter) { if (filter.isBlank()) text else text.filter { it.contains(filter, ignoreCase = true) } }
    val atEnd = !list.canScrollForward
    LaunchedEffect(shown.size) { if (atEnd && shown.isNotEmpty()) list.scrollToItem(shown.size - 1) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            filter, { filter = it }, placeholder = { Text("Filter") }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.medium, colors = fieldColors(),
        )
        error?.let { Text(it, color = Atelier.colors.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            items(shown.size) { i ->
                val line = shown[i]
                Text(
                    line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                    color = when {
                        line.contains("error", true) || line.contains("traceback", true) || line.contains("exception", true) -> Atelier.colors.error
                        line.contains("warn", true) -> Atelier.colors.running
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

/** Every model folder the server knows, opened one at a time. */
@Composable
private fun ModelsTab(s: ServerSession) {
    val folders by produceState<List<String>?>(null, s) { value = runCatching { s.client.modelFolders() }.getOrDefault(emptyList()) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(open) {
        val f = open ?: return@LaunchedEffect
        if (f !in files) files = files + (f to runCatching { s.client.models(f) }.getOrDefault(emptyList()))
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            OutlinedTextField(
                query, { query = it }, placeholder = { Text("Filter the open folder") }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.medium, colors = fieldColors(),
            )
        }
        if (folders == null) item { Text("Loading…", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        folders.orEmpty().forEach { folder ->
            item(key = "f:$folder") {
                Row(
                    Modifier.fillMaxWidth().clickable { open = if (open == folder) null else folder }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(folder, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    files[folder]?.let { Text("${it.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.width(6.dp))
                    Icon(if (open == folder) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
            }
            if (open == folder) {
                val list = files[folder]
                if (list == null) item { Text("Loading…", modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else {
                    val shown = list.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
                    if (shown.isEmpty()) item { Text("Empty", modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(shown, key = { "m:$folder/$it" }) { name ->
                        Text(name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}
