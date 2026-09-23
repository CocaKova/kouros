package com.cocakova.kouros.ui.workflows

import com.cocakova.kouros.ui.theme.fieldColors
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.cocakova.kouros.core.form.OutputKind
import com.cocakova.kouros.ui.theme.Space
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cocakova.kouros.app
import com.cocakova.kouros.data.WorkflowEntity
import com.cocakova.kouros.ui.CurrentServer
import com.cocakova.kouros.ui.components.EmptyState
import com.cocakova.kouros.ui.components.ScreenHeader
import com.cocakova.kouros.ui.components.StatusDot
import com.cocakova.kouros.ui.theme.Accent
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsScreen(
    pad: PaddingValues,
    onOpen: (String) -> Unit,
    onAddServer: () -> Unit,
    sharing: com.cocakova.kouros.ui.SharedMedia? = null,
    onCancelShare: () -> Unit = {},
) {
    val server by CurrentServer.server.collectAsState()
    val s = server
    if (s == null) {
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            EmptyState("Nothing to sculpt yet", "Connect a ComfyUI server and its saved workflows appear here, ready to run.") {
                Button(onClick = onAddServer) { Text("Connect a server") }
            }
        }
        return
    }
    val session = remember(s) { CurrentServer.session(s) }
    DisposableEffect(session) { session.acquire(); onDispose { session.release() } }
    val conn by session.state.collectAsState()
    val list by remember(s.id) { app.db.workflows().forServer(s.id) }.collectAsState(initial = null)
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun refresh() = scope.launch {
        refreshing = true
        error = runCatching { app.workflows.sync(session) }.exceptionOrNull()?.let { it.message ?: "Couldn't load workflows" }
        refreshing = false
    }
    LaunchedEffect(s.id) { refresh() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            if (text != null) onOpen(app.workflows.importLocal(s.id, uri.lastPathSegment?.substringAfterLast('/') ?: "Imported", text))
        }
    }

    var kindFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var acting by remember { mutableStateOf<WorkflowEntity?>(null) }
    val all = list.orEmpty()
    // The kinds present, in the engine's precedence order, with how many of each.
    val kinds = remember(all) {
        val counts = all.groupingBy { it.kind ?: "" }.eachCount()
        (OutputKind.entries.map { it.name } + "OTHER").mapNotNull { k -> counts[k]?.let { k to it } }
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.fillMaxSize().padding(pad)) {
        val shown = all.filter { w ->
            (query.isBlank() || w.name.contains(query, ignoreCase = true) || w.path.contains(query, ignoreCase = true)) &&
                (kindFilter == null || w.kind == kindFilter)
        }
        val pinned = shown.filter { it.pinned }
        // The rest, by the folder the desktop saved them in: top level first, then folders A→Z.
        val byFolder = shown.filterNot { it.pinned }.groupBy { it.path.substringBeforeLast('/', "") }
            .toSortedMap(compareBy<String>({ it.isNotEmpty() }, { it.lowercase() }))
        val hasFolders = byFolder.keys.any { it.isNotEmpty() }

        LazyColumn(contentPadding = PaddingValues(bottom = Space.xl)) {
            item {
                ScreenHeader("Workflows", overline = s.name) {
                    StatusDot(conn, 10.dp)
                    Spacer(Modifier.width(Space.xs))
                    IconButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Icon(Icons.Outlined.FileOpen, "Open a workflow file") }
                }
            }
            sharing?.let { sh ->
                item {
                    com.cocakova.kouros.ui.components.Slab(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Choose a workflow for your ${if (sh.uris.size > 1) "${sh.uris.size} ${sh.kind}s" else sh.kind}",
                                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                            )
                            androidx.compose.material3.TextButton(onClick = onCancelShare) { Text("Cancel") }
                        }
                    }
                }
            }
            if (all.size > 6) item {
                OutlinedTextField(
                    query, { query = it }, placeholder = { Text("Search") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.xs),
                    shape = MaterialTheme.shapes.medium,
                    colors = fieldColors(),
                )
            }
            if (kinds.size > 1) item {
                LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    item { KindChip("All", all.size, null, kindFilter == null) { kindFilter = null } }
                    items(kinds, key = { it.first }) { (k, n) ->
                        KindChip(kindLabel(k), n, k, kindFilter == k) { kindFilter = if (kindFilter == k) null else k }
                    }
                }
            }
            error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = Space.s)) } }
            if (list != null && all.isEmpty() && !refreshing) item {
                EmptyState(
                    "No saved workflows",
                    "Save a workflow in ComfyUI on your desktop (Workflow → Save) and pull down to refresh — or open a workflow file from this phone.",
                )
            }
            if (pinned.isNotEmpty()) {
                item(key = "h:pinned") { SectionLabel("Pinned", pinned.size) }
                items(pinned, key = { "p:" + it.key }) { w -> WorkflowCard(w, showFolder = true, onClick = { onOpen(w.key) }, onPin = { scope.launch { app.db.workflows().pin(w.key, !w.pinned) } }, onMore = { acting = w }) }
            }
            byFolder.forEach { (folder, rows) ->
                val header = when {
                    hasFolders -> folder.ifEmpty { "Top level" }
                    pinned.isNotEmpty() -> "Everything else"
                    else -> null
                }
                header?.let { h -> item(key = "h:$folder") { SectionLabel(h, rows.size) } }
                items(rows, key = { it.key }) { w -> WorkflowCard(w, showFolder = false, onClick = { onOpen(w.key) }, onPin = { scope.launch { app.db.workflows().pin(w.key, !w.pinned) } }, onMore = { acting = w }) }
            }
        }
    }
    acting?.let { w ->
        WorkflowActions(
            w, folders = all.map { it.path.substringBeforeLast('/', "") }.filter { it.isNotEmpty() }.distinct().sorted(),
            session = session, onOpen = { acting = null; onOpen(w.key) }, onDone = { msg -> acting = null; msg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } },
        )
    }
}

/** What the desktop's workflow browser can do to a file: pin, rename, move, duplicate, delete. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WorkflowActions(w: WorkflowEntity, folders: List<String>, session: com.cocakova.kouros.net.ServerSession, onOpen: () -> Unit, onDone: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("menu") } // menu | rename | move | delete
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val folder = w.path.substringBeforeLast('/', "")
    val s = if (w.source == com.cocakova.kouros.data.WorkflowRepo.SOURCE_USERDATA) session else null
    fun go(done: String, block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            runCatching { block() }
                .onSuccess { onDone(done) }
                .onFailure { e -> error = (e as? com.cocakova.kouros.core.api.ComfyHttpException)?.takeIf { it.status == 409 }?.let { "A workflow with that name already exists" } ?: (e.message ?: "Couldn't do that"); busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { onDone(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = Space.gutter).navigationBarsPadding().imePadding().padding(bottom = Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(w.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (w.source == "local") "Saved on this phone" else "On the server" + if (folder.isNotEmpty()) " · $folder" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.xs))
            when (mode) {
                "menu" -> {
                    ActionRow(Icons.Outlined.PlayArrow, "Open") { onOpen() }
                    ActionRow(if (w.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (w.pinned) "Unpin" else "Pin to the top") {
                        scope.launch { app.db.workflows().pin(w.key, !w.pinned); onDone(null) }
                    }
                    ActionRow(Icons.Outlined.DriveFileRenameOutline, "Rename") { mode = "rename" }
                    ActionRow(Icons.Outlined.DriveFileMove, "Move to folder") { mode = "move" }
                    ActionRow(Icons.Outlined.ContentCopy, "Duplicate") { go("Duplicated") { app.workflows.duplicate(s, w) } }
                    ActionRow(Icons.Outlined.Delete, "Delete", danger = true) { mode = "delete" }
                }
                "rename" -> {
                    var name by remember { mutableStateOf(w.name) }
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = fieldColors())
                    Row { Spacer(Modifier.weight(1f)); TextButton(onClick = { mode = "menu" }) { Text("Back") }
                        TextButton(enabled = !busy && name.isNotBlank(), onClick = { go("Renamed") { app.workflows.move(s, w, (if (folder.isEmpty()) "" else "$folder/") + name.trim().replace('/', '-')) } }) { Text("Rename") } }
                }
                "move" -> {
                    var newFolder by remember { mutableStateOf("") }
                    val file = w.path.substringAfterLast('/')
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        (listOf("") + folders).filter { it != folder }.forEach { f ->
                            FilterChip(false, onClick = { go("Moved") { app.workflows.move(s, w, if (f.isEmpty()) file else "$f/$file") } },
                                label = { Text(f.ifEmpty { "Top level" }) }, leadingIcon = { Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp)) })
                        }
                    }
                    OutlinedTextField(newFolder, { newFolder = it }, label = { Text("New folder") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = fieldColors())
                    Row { Spacer(Modifier.weight(1f)); TextButton(onClick = { mode = "menu" }) { Text("Back") }
                        TextButton(enabled = !busy && newFolder.isNotBlank(), onClick = { go("Moved") { app.workflows.move(s, w, newFolder.trim().trim('/') + "/" + file) } }) { Text("Move") } }
                }
                "delete" -> {
                    Text(
                        if (w.source == "local") "Delete this workflow from the phone?"
                        else "Delete this workflow from the server? The desktop loses it too. Its past results stay.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row { Spacer(Modifier.weight(1f)); TextButton(onClick = { mode = "menu" }) { Text("Keep") }
                        TextButton(enabled = !busy, onClick = { go("Deleted") { app.workflows.delete(s, w) } }) { Text("Delete", color = com.cocakova.kouros.ui.theme.Atelier.colors.error) } }
                }
            }
            error?.let { Text(it, color = com.cocakova.kouros.ui.theme.Atelier.colors.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) com.cocakova.kouros.ui.theme.Atelier.colors.error else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = Space.m, horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(Space.l))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** Servers report file times in seconds or in milliseconds; anything past year ~5000 in seconds is milliseconds. */
private fun epochMillis(t: Double): Long = if (t > 1e11) t.toLong() else (t * 1000).toLong()

private fun kindLabel(k: String): String = runCatching { OutputKind.valueOf(k).label }.getOrDefault("Other")

private fun kindIcon(k: String?): ImageVector = when (k) {
    "IMAGE" -> Icons.Outlined.Image
    "VIDEO" -> Icons.Outlined.Movie
    "AUDIO" -> Icons.Outlined.MusicNote
    "MODEL3D" -> Icons.Outlined.ViewInAr
    "TEXT" -> Icons.Outlined.Notes
    else -> Icons.Outlined.AccountTree
}

@Composable
private fun KindChip(label: String, count: Int, kind: String?, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text("$label  $count") },
        leadingIcon = kind?.let { { Icon(kindIcon(it), null, Modifier.size(16.dp)) } },
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
            selectedLeadingIconColor = Accent.clay,
        ),
        border = FilterChipDefaults.filterChipBorder(true, selected, borderColor = MaterialTheme.colorScheme.outlineVariant, selectedBorderColor = Accent.clay.copy(alpha = 0.5f)),
    )
}

@Composable
private fun SectionLabel(text: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = Space.l, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkflowCard(w: WorkflowEntity, showFolder: Boolean, onClick: () -> Unit, onPin: () -> Unit, onMore: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onMore() })
            .padding(horizontal = Space.gutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A tile showing what it makes, warmed by the name's hash so neighbours differ.
        val hue = (w.name.hashCode().toLong() and 0xFFFF).toFloat() / 0xFFFF
        Box(
            Modifier.size(48.dp).clip(MaterialTheme.shapes.medium)
                .background(Brush.linearGradient(listOf(Accent.clayDeep.copy(alpha = 0.45f + hue * 0.3f), Accent.clay.copy(alpha = 0.18f + hue * 0.3f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(kindIcon(w.kind), null, tint = Accent.ember, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(w.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val folder = w.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() && showFolder }
            val takes = w.inputs?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { "from " + it.joinToString(" + ") }
            val meta = listOfNotNull(
                folder,
                takes,
                if (w.source == "local") "on this phone" else null,
                w.modified?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis(it))) },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onPin) {
            Icon(if (w.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (w.pinned) "Unpin" else "Pin",
                tint = if (w.pinned) Accent.clay else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}
