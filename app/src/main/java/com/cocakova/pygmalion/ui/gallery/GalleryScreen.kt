package com.cocakova.pygmalion.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.cocakova.pygmalion.app
import com.cocakova.pygmalion.core.api.MediaKind
import com.cocakova.pygmalion.core.api.OutputItem
import com.cocakova.pygmalion.data.RunState
import com.cocakova.pygmalion.media.Thumbs
import com.cocakova.pygmalion.net.ServerSession
import com.cocakova.pygmalion.run.RunCoordinator
import com.cocakova.pygmalion.ui.CurrentServer
import com.cocakova.pygmalion.ui.components.EmptyState
import com.cocakova.pygmalion.ui.components.ScreenHeader

/** One tile: an output of a run, from this phone's runs or from the server's history. */
data class Tile(val serverId: String, val promptId: String, val index: Int, val item: OutputItem, val title: String)

@Composable
fun GalleryScreen(pad: PaddingValues, onOpen: (serverId: String, promptId: String, index: Int) -> Unit) {
    val server by CurrentServer.server.collectAsState()
    var fromServer by remember { mutableStateOf(false) }
    val runs by app.db.runs().recent().collectAsState(initial = emptyList())
    var history by remember { mutableStateOf<List<Tile>?>(null) }
    val s = server
    LaunchedEffect(fromServer, s?.id) {
        if (fromServer && s != null) history = runCatching {
            app.sessions.get(s).client.history(150).flatMap { h ->
                h.outputs.flatMap { (id, o) -> com.cocakova.pygmalion.core.api.Outputs.classify(id, o) }
                    .filter { !it.isTemp }
                    .mapIndexed { i, item -> Tile(s.id, h.promptId, i, item, h.promptId.take(8)) }
            }
        }.getOrDefault(emptyList())
    }
    val phoneTiles = remember(runs) {
        runs.filter { it.state == RunState.SUCCEEDED }.flatMap { r ->
            RunCoordinator.decodeOutputs(r.outputsJson).filter { !it.isTemp || it.kind != MediaKind.IMAGE }
                .mapIndexed { i, item -> Tile(r.serverId, r.promptId, i, item, r.workflowName) }
        }
    }
    val tiles = if (fromServer) history.orEmpty() else phoneTiles
    val context = LocalContext.current
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().padding(pad),
        contentPadding = PaddingValues(12.dp),
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
        if (tiles.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
            EmptyState(
                if (fromServer) "Nothing here yet" else "Your results will gather here",
                if (fromServer) "The server's recent history is empty." else "Run a workflow and what it makes appears here — also saved on your server as always.",
            )
        }
        items(tiles, key = { "${it.promptId}:${it.index}:${it.item.file?.filename}" }) { t ->
            val session = remember(t.serverId) { CurrentServer.servers.value.firstOrNull { it.id == t.serverId }?.let(app.sessions::get) }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onOpen(t.serverId, t.promptId, t.index) },
                contentAlignment = Alignment.Center,
            ) {
                TileContent(t, session, context)
            }
        }
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
        else -> Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.TextSnippet, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        }
    }
}
