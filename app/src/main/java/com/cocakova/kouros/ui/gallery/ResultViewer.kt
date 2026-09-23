package com.cocakova.kouros.ui.gallery

import androidx.compose.material.icons.outlined.Delete
import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.cocakova.kouros.app
import com.cocakova.kouros.core.api.MediaKind
import com.cocakova.kouros.core.api.OutputItem
import com.cocakova.kouros.core.api.Outputs
import com.cocakova.kouros.data.RunEntity
import com.cocakova.kouros.media.Thumbs
import com.cocakova.kouros.net.ServerSession
import com.cocakova.kouros.notify.Notifier
import com.cocakova.kouros.run.RunCoordinator
import kotlinx.coroutines.launch

@Composable
fun ResultViewer(serverId: String, promptId: String, startIndex: Int, onBack: () -> Unit, onRemix: (workflowKey: String, runId: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by produceState<ServerSession?>(null, serverId) { value = app.sessions.byId(serverId) }
    val run by produceState<RunEntity?>(null, promptId) { value = app.db.runs().get(promptId) }
    // Outputs: this phone's record when it has one, else the server's history.
    val items by produceState<List<OutputItem>>(emptyList(), promptId, session, run) {
        value = FileTile.ref(promptId)?.let { f -> listOf(OutputItem("", Outputs.byExtension(f.filename) ?: com.cocakova.kouros.core.api.MediaKind.FILE, f)) }
            ?: run?.let { RunCoordinator.decodeOutputs(it.outputsJson) }
            ?: session?.let { s -> runCatching { s.client.historyFor(promptId) }.getOrNull() }
                ?.outputs?.flatMap { (id, o) -> Outputs.classify(id, o) }?.filter { !it.isTemp }
            ?: emptyList()
    }
    var chrome by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val canDeleteFiles by produceState<Boolean?>(null, serverId) { value = app.library.canDeleteFiles(serverId) }
    val pager = rememberPagerState(initialPage = startIndex) { items.size }
    if (confirmDelete) {
        val item = items.getOrNull(pager.currentPage)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ${if (item?.kind == MediaKind.VIDEO) "video" else if (item?.kind == MediaKind.AUDIO) "track" else "image"}?") },
            text = {
                Text(
                    if (canDeleteFiles == true) "The file is deleted from the server. This can't be undone."
                    else "It's removed from this app and the server's history; the file stays in the server's output folder (install Kouros Bridge to delete files).",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDelete = false
                    val it = item ?: return@TextButton
                    scope.launch {
                        val out = app.library.delete(listOf(com.cocakova.kouros.data.MediaManager.Target(serverId, promptId, it)))
                        android.widget.Toast.makeText(context, out.summary(), android.widget.Toast.LENGTH_SHORT).show()
                        onBack()
                    }
                }) { Text("Delete", color = com.cocakova.kouros.ui.theme.Atelier.colors.error) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val s = session
        if (s != null && items.isNotEmpty()) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                val item = items[page]
                when (item.kind) {
                    MediaKind.VIDEO, MediaKind.AUDIO -> Player(s, item)
                    MediaKind.TEXT -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).statusBarsPadding()) {
                        Text(item.text ?: item.file?.filename ?: "", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    }
                    else -> ZoomableImage(s, item) { chrome = !chrome }
                }
            }
        }
        if (chrome) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(run?.workflowName ?: "Result", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    val secs = run?.let { r -> r.startedAt?.let { a -> r.finishedAt?.let { b -> (b - a) / 1000 } } }
                    Text(
                        listOfNotNull(if (items.size > 1) "${pager.currentPage + 1} of ${items.size}" else null, secs?.let { "made in ${Notifier.formatDuration(it)}" }).joinToString(" · "),
                        color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val current = items.getOrNull(pager.currentPage)
                Action(Icons.Outlined.Download, "Save") {
                    val s = session ?: return@Action; val it = current ?: return@Action
                    scope.launch { toast = runCatching { app.media.save(s, it) }.fold({ "Saved to your gallery" }, { e -> "Couldn't save: ${e.message}" }) }
                }
                Action(Icons.Outlined.Share, "Share") {
                    val s = session ?: return@Action; val f = current?.file ?: return@Action
                    scope.launch {
                        runCatching { app.media.shareable(s, f) }.onSuccess { uri ->
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType(Outputs.mimeOf(f.filename)).putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                    null,
                                ),
                            )
                        }.onFailure { toast = "Couldn't share: ${it.message}" }
                    }
                }
                if (run != null) {
                    Action(Icons.Outlined.Replay, "Run again") {
                        scope.launch { toast = app.runs.rerun(promptId) ?: "Queued again" }
                    }
                    run?.workflowKey?.let { key -> Action(Icons.Outlined.AutoFixHigh, "Remix") { onRemix(key, promptId) } }
                }
                Action(Icons.Outlined.Delete, "Delete") { confirmDelete = true }
            }
            toast?.let {
                Text(it, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp).background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
        Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ZoomableImage(session: ServerSession, item: OutputItem, onTap: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    val f = item.file ?: return
    AsyncImage(
        model = Thumbs.request(context, session, f, item.kind, thumbnail = false).build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .transformable(state)
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun Player(session: ServerSession, item: OutputItem) {
    val context = LocalContext.current
    val f = item.file ?: return
    val player = remember(f) {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(session.client.endpoint.headers)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().apply {
            setMediaItem(MediaItem.fromUri(session.client.viewUrl(f)))
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        AndroidView({ PlayerView(it).apply { this.player = player; useController = true } }, Modifier.fillMaxWidth().height(if (item.kind == MediaKind.AUDIO) 160.dp else 480.dp))
        if (item.kind == MediaKind.AUDIO) {
            Spacer(Modifier.height(12.dp))
            Text(f.filename, color = Color.White, modifier = Modifier.padding(horizontal = 24.dp))
        }
    }
}
