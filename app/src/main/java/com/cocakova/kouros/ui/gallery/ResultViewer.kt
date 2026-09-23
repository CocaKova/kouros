package com.cocakova.kouros.ui.gallery

import androidx.compose.material.icons.outlined.Delete
import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cocakova.kouros.ui.theme.Accent
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
    val clipboard = LocalClipboardManager.current
    val session by produceState<ServerSession?>(null, serverId) { value = app.sessions.byId(serverId) }
    val run by produceState<RunEntity?>(null, promptId) { value = app.db.runs().get(promptId) }
    // Outputs: this phone's record when it has one, else the server's history.
    val items by produceState<List<OutputItem>>(emptyList(), promptId, session, run) {
        value = FileTile.ref(promptId)?.let { f -> listOf(OutputItem("", Outputs.byExtension(f.filename) ?: com.cocakova.kouros.core.api.MediaKind.FILE, f)) }
            ?: run?.let { Outputs.forViewing(RunCoordinator.decodeOutputs(it.outputsJson)) }
            ?: session?.let { s -> runCatching { s.client.historyFor(promptId) }.getOrNull() }
                ?.outputs?.flatMap { (id, o) -> Outputs.classify(id, o) }?.filter { !it.isTemp }?.let(Outputs::forViewing)
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
                    MediaKind.AUDIO -> AudioStage(s, item, run?.workflowName ?: "Track", playing = page == pager.currentPage)
                    MediaKind.VIDEO -> Player(s, item)
                    MediaKind.TEXT -> TextPage(item)
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
                // A text output is not a file on the server: it copies rather than saves or shares.
                if (current?.file == null && current?.text != null) {
                    Action(Icons.Outlined.ContentCopy, "Copy") {
                        clipboard.setText(AnnotatedString(current.text.orEmpty()))
                        toast = "Copied"
                    }
                } else {
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
                }
                if (run != null) {
                    Action(Icons.Outlined.Replay, "Run again") {
                        scope.launch { toast = app.runs.rerun(promptId) ?: "Queued again" }
                    }
                    run?.workflowKey?.let { key -> Action(Icons.Outlined.AutoFixHigh, "Remix") { onRemix(key, promptId) } }
                }
                if (current?.file != null) Action(Icons.Outlined.Delete, "Delete") { confirmDelete = true }
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

/**
 * A track, presented as a track: cover plinth, title, transport, time. A bare PlayerView over
 * audio is a black rectangle whose controls fade out after a few seconds, which reads as "here is
 * a filename" — so the transport here is always on screen and the art says, at a glance, that
 * something is playing.
 */
@OptIn(UnstableApi::class)
@Composable
private fun AudioStage(session: ServerSession, item: OutputItem, title: String, playing: Boolean) {
    val context = LocalContext.current
    val f = item.file ?: return
    val player = remember(f) {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(session.client.endpoint.headers)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().apply {
            setMediaItem(MediaItem.fromUri(session.client.viewUrl(f)))
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // Only the page in front of you plays; the pager keeps neighbours composed.
    LaunchedEffect(player, playing) { player.playWhenReady = playing }

    var isPlaying by remember(player) { mutableStateOf(false) }
    var loop by remember(player) { mutableStateOf(true) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var scrub by remember(player) { mutableStateOf<Float?>(null) }
    DisposableEffect(player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0L } ?: 0L
            kotlinx.coroutines.delay(200)
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(start = 28.dp, end = 28.dp, top = 72.dp, bottom = 132.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth(0.72f).aspectRatio(1f).clip(RoundedCornerShape(28.dp))
                .background(Brush.radialGradient(listOf(Accent.clay.copy(alpha = 0.55f), Color(0xFF14100E)))),
            contentAlignment = Alignment.Center,
        ) { Equalizer(isPlaying, Modifier.fillMaxWidth(0.5f).height(64.dp)) }
        Spacer(Modifier.height(28.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            f.filename, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        val fraction = scrub ?: if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Slider(
            value = fraction,
            onValueChange = { scrub = it },
            onValueChangeFinished = {
                scrub?.let { if (duration > 0L) player.seekTo((it * duration).toLong()) }
                scrub = null
            },
            enabled = duration > 0L,
            colors = SliderDefaults.colors(
                thumbColor = Accent.clayBright, activeTrackColor = Accent.clayBright,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
            modifier = Modifier.semantics { contentDescription = "Seek" },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(clock(if (scrub != null) (fraction * duration).toLong() else position), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0L) clock(duration) else "—:—", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L)) }) {
                Icon(Icons.Outlined.Replay10, "Back ten seconds", tint = Color.White)
            }
            FilledIconButton(
                onClick = { if (player.isPlaying) player.pause() else { if (player.playbackState == ExoPlayer.STATE_ENDED) player.seekTo(0); player.play() } },
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent.clay, contentColor = Color.White),
            ) {
                Icon(
                    if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = { player.seekTo(player.currentPosition + 10_000) }) {
                Icon(Icons.Outlined.Forward10, "Forward ten seconds", tint = Color.White)
            }
            IconButton(onClick = {
                loop = !loop
                player.repeatMode = if (loop) ExoPlayer.REPEAT_MODE_ONE else ExoPlayer.REPEAT_MODE_OFF
            }) {
                Icon(Icons.Outlined.Repeat, if (loop) "Stop repeating" else "Repeat", tint = if (loop) Accent.clayBright else Color.White.copy(alpha = 0.45f))
            }
        }
    }
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/** Five bars that move while the track plays, and rest flat when it doesn't. */
@Composable
private fun Equalizer(playing: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val heights = listOf(620, 980, 760, 1180, 840).mapIndexed { i, period ->
        transition.animateFloat(
            initialValue = if (i % 2 == 0) 0.3f else 0.85f,
            targetValue = if (i % 2 == 0) 0.95f else 0.25f,
            animationSpec = infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Reverse),
            label = "bar$i",
        )
    }
    Canvas(modifier.semantics { contentDescription = if (playing) "Playing" else "Paused" }) {
        val gap = size.width / 11f
        val barWidth = gap * 1.2f
        heights.forEachIndexed { i, h ->
            val level = if (playing) h.value else 0.18f
            val barHeight = size.height * level
            drawRoundRect(
                color = Color.White.copy(alpha = if (playing) 0.92f else 0.5f),
                topLeft = Offset(i * (barWidth + gap) + gap, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** A node's text output — the ABC plan, a caption, a dump. Monospace so structure survives. */
@Composable
private fun TextPage(item: OutputItem) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 72.dp, bottom = 132.dp),
    ) {
        Text(
            if (item.nodeId.isBlank()) "Text output" else "Text output · node ${item.nodeId}",
            color = Accent.clayBright, style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(10.dp))
        SelectionContainer {
            Text(
                item.text ?: item.file?.filename ?: "",
                color = Color.White, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            )
        }
    }
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
        AndroidView({ PlayerView(it).apply { this.player = player; useController = true } }, Modifier.fillMaxWidth().height(480.dp))
    }
}
