package com.cocakova.pygmalion.ui.workflows

import android.net.Uri
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
import com.cocakova.pygmalion.app
import com.cocakova.pygmalion.data.WorkflowEntity
import com.cocakova.pygmalion.ui.CurrentServer
import com.cocakova.pygmalion.ui.components.EmptyState
import com.cocakova.pygmalion.ui.components.ScreenHeader
import com.cocakova.pygmalion.ui.components.StatusDot
import com.cocakova.pygmalion.ui.theme.Accent
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsScreen(pad: PaddingValues, onOpen: (String) -> Unit, onAddServer: () -> Unit) {
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

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.fillMaxSize().padding(pad)) {
        val items = list.orEmpty().filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.path.contains(query, ignoreCase = true) }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                ScreenHeader("Workflows", overline = s.name) {
                    StatusDot(conn, 10.dp)
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Icon(Icons.Outlined.FileOpen, "Open a workflow file") }
                }
            }
            if ((list?.size ?: 0) > 6) item {
                OutlinedTextField(
                    query, { query = it }, placeholder = { Text("Search") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                )
            }
            error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) } }
            if (list != null && list!!.isEmpty() && !refreshing) item {
                EmptyState(
                    "No saved workflows",
                    "Save a workflow in ComfyUI on your desktop (Workflow → Save) and pull down to refresh — or open a workflow file from this phone.",
                )
            }
            items(items, key = { it.key }) { w -> WorkflowCard(w, onClick = { onOpen(w.key) }, onPin = { scope.launch { app.db.workflows().pin(w.key, !w.pinned) } }) }
        }
    }
}

@Composable
private fun WorkflowCard(w: WorkflowEntity, onClick: () -> Unit, onPin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A monogram tile tinted by the workflow's name: stable, distinct, no network needed.
        val hue = (w.name.hashCode().toLong() and 0xFFFF).toFloat() / 0xFFFF
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Accent.clayDeep.copy(alpha = 0.55f + hue * 0.3f), Accent.clay.copy(alpha = 0.25f + hue * 0.4f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(w.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "·", style = MaterialTheme.typography.headlineSmall, color = Accent.ember)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(w.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val folder = w.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
            val meta = listOfNotNull(
                folder,
                if (w.source == "local") "on this phone" else null,
                w.modified?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date((it * 1000).toLong())) },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onPin) {
            Icon(if (w.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (w.pinned) "Unpin" else "Pin",
                tint = if (w.pinned) Accent.clay else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}
