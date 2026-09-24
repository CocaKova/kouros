package com.cocakova.kouros.ui.gallery

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.cocakova.kouros.data.MediaManager
import com.cocakova.kouros.ui.theme.Accent
import com.cocakova.kouros.ui.theme.Atelier
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cocakova.kouros.app
import com.cocakova.kouros.core.api.GalleryFilter
import com.cocakova.kouros.core.api.MediaKind
import com.cocakova.kouros.core.api.OutputItem
import com.cocakova.kouros.data.RunState
import com.cocakova.kouros.media.Thumbs
import com.cocakova.kouros.net.ServerSession
import com.cocakova.kouros.run.RunCoordinator
import com.cocakova.kouros.ui.CurrentServer
import com.cocakova.kouros.ui.components.EmptyState
import com.cocakova.kouros.ui.components.ScreenHeader

/** Gallery entries that are just a file in the server's output folder, with no run behind them. */
object FileTile {
    const val PREFIX = "file:"
    fun id(f: com.cocakova.kouros.core.api.FileRef) = PREFIX + android.net.Uri.encode("${f.type}|${f.subfolder}|${f.filename}")
    fun ref(id: String): com.cocakova.kouros.core.api.FileRef? {
        if (!id.startsWith(PREFIX)) return null
        val parts = android.net.Uri.decode(id.removePrefix(PREFIX)).split('|', limit = 3)
        return if (parts.size == 3) com.cocakova.kouros.core.api.FileRef(parts[2], parts[1], parts[0]) else null
    }
}

/** One tile: an output of a run, from this phone's runs or from the server's history. */
data class Tile(val serverId: String, val promptId: String, val index: Int, val item: OutputItem, val title: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(pad: PaddingValues, onOpen: (serverId: String, promptId: String, index: Int) -> Unit) {
    val server by CurrentServer.server.collectAsState()
    var fromServer by rememberSaveable { mutableStateOf(false) }
    val runs by app.db.runs().recent().collectAsState(initial = emptyList())
    var history by remember { mutableStateOf<List<Tile>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val s = server
    LaunchedEffect(fromServer, s?.id, reload) {
        if (fromServer && s != null) history = runCatching {
            val client = app.sessions.get(s).client
            val runs = client.history(150).flatMap { h ->
                h.outputs.flatMap { (id, o) -> com.cocakova.kouros.core.api.Outputs.classify(id, o) }
                    .filter { !it.isTemp }
                    .let(com.cocakova.kouros.core.api.Outputs::forViewing)
                    .mapIndexed { i, item -> Tile(s.id, h.promptId, i, item, h.promptId.take(8)) }
            }
            // The whole output folder, newest first — history forgets everything when the server
            // restarts. A file that came from a run in history keeps that run (for remixing).
            val byFile = runs.associateBy { it.item.file }
            client.outputFiles()?.map { item -> val f = item.file!!; byFile[f] ?: Tile(s.id, FileTile.id(f), 0, item, f.filename) } ?: runs
        }.getOrDefault(emptyList())
    }
    val phoneTiles = remember(runs) {
        runs.filter { it.state == RunState.SUCCEEDED }.flatMap { r ->
            // Same order the viewer pages through, or a tile would open the wrong page.
            com.cocakova.kouros.core.api.Outputs.forViewing(RunCoordinator.decodeOutputs(r.outputsJson))
                .mapIndexed { i, item -> Tile(r.serverId, r.promptId, i, item, r.workflowName) }
        }
    }
    val all = if (fromServer) history.orEmpty() else phoneTiles
    val groups = remember(all) { GalleryFilter.counts(all.map { it.item.kind }) }
    // Null until the person chooses: the gallery opens on whatever it mostly holds.
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = chosen?.let { name -> groups.map { it.first }.firstOrNull { it.name == name } }
        ?: remember(all) { GalleryFilter.initial(all.map { it.item.kind }) }
    val tiles = remember(all, filter) { all.filter { filter.accepts(it.item.kind) } }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Selection: long-press starts it, taps then toggle.
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selecting = selected.isNotEmpty()
    fun key(t: Tile) = "${t.serverId}|${t.promptId}:${t.index}:${t.item.file?.filename}"
    LaunchedEffect(fromServer, filter) { selected = emptySet() }
    BackHandler(selecting) { selected = emptySet() }
    var confirm by remember { mutableStateOf(false) }
    var filesToo by remember { mutableStateOf<Boolean?>(null) }
    var working by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(pad)) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (selecting) 96.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalItemSpacing = 8.dp,
        ) {
            item(span = StaggeredGridItemSpan.FullLine) {
                ScreenHeader("Gallery", overline = if (fromServer) s?.name else "Made on this phone") {}
            }
            item(span = StaggeredGridItemSpan.FullLine) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
                    FilterChip(!fromServer, { fromServer = false }, label = { Text("This phone") })
                    FilterChip(fromServer, { fromServer = true }, label = { Text("Everything on the server") })
                }
            }
            if (groups.size > 1) item(span = StaggeredGridItemSpan.FullLine) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    groups.forEach { (g, n) ->
                        FilterChip(filter == g, { chosen = g.name }, label = { Text("${g.label} $n") })
                    }
                }
            }
            if (tiles.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
                EmptyState(
                    if (fromServer) "Nothing here yet" else "Your results will gather here",
                    if (fromServer) "Nothing in the server's output folder yet." else "Run a workflow and what it makes appears here — also saved on your server as always.",
                )
            } else if (!selecting) item(span = StaggeredGridItemSpan.FullLine) {
                Text("Long-press to select", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
            items(tiles, key = { key(it) }) { t ->
                val session = remember(t.serverId) { CurrentServer.servers.value.firstOrNull { it.id == t.serverId }?.let(app.sessions::get) }
                val k = key(t)
                val isSel = k in selected
                Box(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer)
                        .combinedClickable(
                            onClickLabel = if (selecting) "Select" else "Open", onLongClickLabel = "Select",
                            onClick = { if (selecting) selected = if (isSel) selected - k else selected + k else onOpen(t.serverId, t.promptId, t.index) },
                            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); selected = if (isSel) selected - k else selected + k },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    TileContent(t, session, context)
                    if (selecting) {
                        Box(Modifier.matchParentSize().background(if (isSel) Accent.clay.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.12f)))
                        Icon(
                            if (isSel) Icons.Filled.CheckCircle else Icons.Outlined.Circle, if (isSel) "Selected" else "Not selected",
                            tint = if (isSel) Accent.ember else Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp),
                        )
                    }
                }
            }
        }

        // The selection bar: count, select all, delete.
        AnimatedVisibility(selecting, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Outlined.Close, "Cancel selection") }
                    Text("${selected.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { selected = if (selected.size == tiles.size) emptySet() else tiles.map { key(it) }.toSet() }) {
                        Text(if (selected.size == tiles.size) "None" else "All")
                    }
                    Button(
                        onClick = {
                            confirm = true; filesToo = null
                            scope.launch { filesToo = tiles.filter { key(it) in selected }.map { it.serverId }.distinct().all { app.library.canDeleteFiles(it) } }
                        },
                        enabled = !working,
                        colors = ButtonDefaults.buttonColors(containerColor = Atelier.colors.error, contentColor = MaterialTheme.colorScheme.background),
                    ) {
                        if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.background)
                        else { Icon(Icons.Outlined.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete") }
                    }
                }
            }
        }
    }

    if (confirm) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete $n item${if (n == 1) "" else "s"}?") },
            text = {
                Text(
                    when (filesToo) {
                        null -> "Checking the server…"
                        true -> "The files are deleted from the server's output folder, and runs with nothing left are removed from its history. This can't be undone."
                        false -> "They'll be removed from this app and the server's history. The files themselves stay in the server's output folder: stock ComfyUI can't delete them. Install Kouros Bridge on the server to delete files too."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = filesToo != null,
                    onClick = {
                        confirm = false; working = true
                        val targets = tiles.filter { key(it) in selected }.map { MediaManager.Target(it.serverId, it.promptId, it.item) }
                        scope.launch {
                            val out = app.library.delete(targets)
                            selected = emptySet(); working = false; reload++
                            Toast.makeText(context, out.summary(), Toast.LENGTH_LONG).show()
                        }
                    },
                ) { Text("Delete", color = Atelier.colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun TileContent(t: Tile, session: ServerSession?, context: android.content.Context) {
    val f = t.item.file
    when (t.item.kind) {
        MediaKind.IMAGE, MediaKind.ANIMATED, MediaKind.VIDEO -> if (f != null && session != null) {
            AsyncImage(
                model = Thumbs.request(context, session, f, t.item.kind).build(),
                contentDescription = t.title, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth(),
            )
            if (t.item.kind == MediaKind.VIDEO) Icon(Icons.Outlined.PlayCircle, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(40.dp))
        }
        MediaKind.AUDIO -> Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text(f?.filename ?: "", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp), maxLines = 1)
        }
        else -> Box(Modifier.fillMaxWidth().aspectRatio(1.6f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.TextSnippet, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    t.item.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(60) ?: f?.filename ?: "Note",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}
