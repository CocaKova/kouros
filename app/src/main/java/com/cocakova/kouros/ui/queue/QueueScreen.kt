package com.cocakova.kouros.ui.queue

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cocakova.kouros.app
import com.cocakova.kouros.core.api.Bridge
import com.cocakova.kouros.core.api.BridgeMemory
import com.cocakova.kouros.core.api.HistoryEntry
import com.cocakova.kouros.core.api.MediaKind
import com.cocakova.kouros.core.api.Outputs
import com.cocakova.kouros.core.api.QueueSnapshot
import com.cocakova.kouros.core.api.SystemStats
import com.cocakova.kouros.media.Thumbs
import com.cocakova.kouros.net.ServerSession
import com.cocakova.kouros.run.Reseed
import com.cocakova.kouros.ui.CurrentServer
import com.cocakova.kouros.ui.components.ChiselProgress
import com.cocakova.kouros.ui.components.EmptyState
import com.cocakova.kouros.ui.components.ScreenHeader
import com.cocakova.kouros.ui.components.Slab
import com.cocakova.kouros.ui.components.Tag
import com.cocakova.kouros.ui.theme.Accent
import com.cocakova.kouros.ui.theme.Atelier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val GB = 1.073741824e9

/**
 * The server's activity, whoever caused it: memory and what's loaded, what's running and
 * waiting, and the history of what ran — with the controls the desktop has for each.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun QueueScreen(pad: PaddingValues, onConsole: (String) -> Unit = {}, onOpenResult: (String, String) -> Unit = { _, _ -> }) {
    val server by CurrentServer.server.collectAsState()
    val s = server ?: run { EmptyState("No server", "Add a server to see its activity.", Modifier.padding(pad)); return }
    val session = remember(s) { CurrentServer.session(s) }
    DisposableEffect(session) { session.acquire(); onDispose { session.release() } }
    var queue by remember { mutableStateOf<QueueSnapshot?>(null) }
    var stats by remember { mutableStateOf<SystemStats?>(null) }
    var memory by remember { mutableStateOf<BridgeMemory?>(null) }
    var bridge by remember { mutableStateOf<Bridge?>(null) }
    var history by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    val progress by app.runs.progress.collectAsState()
    val runs by app.db.runs().recent(500).collectAsState(initial = emptyList())
    val names = remember(runs) { runs.associate { it.promptId to it.workflowName } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var tick by remember { mutableIntStateOf(0) }
    var historyTick by remember { mutableIntStateOf(0) }

    // The queue and memory change whether or not we caused it: poll while visible.
    LaunchedEffect(s.id, tick) {
        bridge = runCatching { session.client.bridge() }.getOrNull()
        while (true) {
            queue = runCatching { session.client.queue() }.getOrNull() ?: queue
            stats = runCatching { session.client.systemStats() }.getOrNull() ?: stats
            if (bridge?.hasMemory == true) memory = runCatching { session.client.memory() }.getOrNull() ?: memory
            delay(2500)
        }
    }
    // History refreshes when the queue drains a job, or on demand.
    val runningIds = queue?.running?.map { it.promptId }.orEmpty()
    LaunchedEffect(s.id, historyTick, runningIds.size) {
        history = runCatching { session.client.history(100) }.getOrNull() ?: history
    }

    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selecting = selected.isNotEmpty()
    BackHandler(selecting) { selected = emptySet() }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(pad)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = if (selecting) 96.dp else 24.dp)) {
            item {
                ScreenHeader("Activity", overline = s.name) {
                    IconButton(onClick = { onConsole(s.id) }) { Icon(Icons.Outlined.Terminal, "Server console") }
                }
            }
            item {
                MemoryPanel(stats, memory, session, onChanged = { tick++ }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }

            val q = queue
            val waiting = q?.pending?.sortedBy { it.number ?: 0.0 }.orEmpty()
            item { Section("Now", if (q == null) null else "${q.running.size + waiting.size}") }
            if (q != null && q.running.isEmpty() && waiting.isEmpty()) item {
                Text(
                    "The server is idle. Jobs from every client (this phone, the desktop, anything) show up here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            items(q?.running.orEmpty(), key = { "r" + it.promptId }) { item ->
                val p = progress[item.promptId]
                Slab(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(names[item.promptId] ?: "Running", style = MaterialTheme.typography.titleMedium)
                            Text(p?.currentTitle ?: if (names[item.promptId] == null) "Started elsewhere" else "Working", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Tag("running", Atelier.colors.running)
                        IconButton(onClick = { scope.launch { runCatching { session.client.interrupt(item.promptId) }; tick++ } }) { Icon(Icons.Outlined.Close, "Stop") }
                    }
                    if (p != null) { Spacer(Modifier.height(8.dp)); ChiselProgress(p.totalNodes, p.doneNodes, if (p.steps > 0) p.step.toFloat() / p.steps else 0f) }
                }
            }
            items(waiting, key = { "p" + it.promptId }) { item ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(names[item.promptId] ?: "Queued", style = MaterialTheme.typography.bodyLarge)
                        Text(item.promptId.take(8), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { scope.launch { runCatching { session.client.deleteQueued(listOf(item.promptId)) }; tick++ } }) { Icon(Icons.Outlined.Close, "Remove") }
                }
            }
            if (waiting.isNotEmpty()) item {
                TextButton(onClick = { scope.launch { runCatching { session.client.clearQueue() }; tick++ } }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("Clear ${waiting.size} waiting")
                }
            }

            val h = history.orEmpty()
            item {
                Section("History", if (history == null) null else "${h.size}") {
                    if (h.isNotEmpty() && !selecting) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
                }
            }
            if (history != null && h.isEmpty()) item {
                Text("Nothing has run yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            }
            items(h, key = { "h" + it.promptId }) { e ->
                val isSel = e.promptId in selected
                HistoryRow(
                    e, names[e.promptId], session, isSel, selecting,
                    modifier = Modifier.combinedClickable(
                        onClickLabel = if (selecting) "Select" else "Open", onLongClickLabel = "Select",
                        onClick = {
                            if (selecting) selected = if (isSel) selected - e.promptId else selected + e.promptId
                            else onOpenResult(s.id, e.promptId)
                        },
                        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); selected = if (isSel) selected - e.promptId else selected + e.promptId },
                    ),
                    onRerun = {
                        scope.launch {
                            val msg = rerun(session, e, names[e.promptId])
                            Toast.makeText(context, msg ?: "Queued again with a new seed", Toast.LENGTH_SHORT).show()
                            tick++
                        }
                    },
                )
            }
        }

        if (selecting) Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Outlined.Close, "Cancel selection") }
                Text("${selected.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = if (selected.size == history.orEmpty().size) emptySet() else history.orEmpty().map { it.promptId }.toSet() }) {
                    Text(if (selected.size == history.orEmpty().size) "None" else "All")
                }
                Button(
                    onClick = { confirmDelete = true }, enabled = !working,
                    colors = ButtonDefaults.buttonColors(containerColor = Atelier.colors.error, contentColor = MaterialTheme.colorScheme.background),
                ) {
                    if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.background)
                    else { Icon(Icons.Outlined.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete") }
                }
            }
        }
    }

    fun deleteRuns(ids: List<String>) {
        working = true
        scope.launch {
            val out = app.library.deleteRuns(s.id, ids)
            selected = emptySet(); working = false; historyTick++
            Toast.makeText(context, out.summary(), Toast.LENGTH_LONG).show()
        }
    }
    if (confirmDelete) DeleteDialog(selected.size, bridge?.canDelete == true, onConfirm = { confirmDelete = false; deleteRuns(selected.toList()) }, onDismiss = { confirmDelete = false })
    if (confirmClear) DeleteDialog(history.orEmpty().size, bridge?.canDelete == true, everything = true, onConfirm = {
        confirmClear = false; deleteRuns(history.orEmpty().map { it.promptId })
    }, onDismiss = { confirmClear = false })
}

@Composable
private fun DeleteDialog(n: Int, files: Boolean, everything: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (everything) "Clear all $n runs?" else "Delete $n run${if (n == 1) "" else "s"}?") },
        text = {
            Text(
                if (files) "Their output files are deleted from the server, and they're removed from its history and this phone. This can't be undone."
                else "They're removed from the server's history and this phone. The output files stay in the server's output folder: stock ComfyUI can't delete them. Install Kouros Bridge to delete files too.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = Atelier.colors.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

/** Queues a history entry's prompt again, with seeds moved on the way the desktop does. */
private suspend fun rerun(session: ServerSession, e: HistoryEntry, name: String?): String? {
    val prompt = e.prompt ?: return "The server didn't keep this run's prompt"
    val oi = runCatching { session.objectInfo() }.getOrNull()
    val local = app.db.runs().get(e.promptId)
    return when (val r = app.runs.submit(session, Reseed.advance(prompt, oi), local?.workflowKey, name ?: "Run again", local?.valuesJson, e.workflow)) {
        is com.cocakova.kouros.run.RunCoordinator.Submitted.Ok -> null
        is com.cocakova.kouros.run.RunCoordinator.Submitted.Rejected -> r.message
        is com.cocakova.kouros.run.RunCoordinator.Submitted.Failed -> r.message
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, name: String?, session: ServerSession, selected: Boolean, selecting: Boolean, modifier: Modifier, onRerun: () -> Unit) {
    val context = LocalContext.current
    val outs = remember(e) { e.outputs.flatMap { (id, o) -> Outputs.classify(id, o) }.filter { !it.isTemp } }
    val first = outs.firstOrNull { it.kind == MediaKind.IMAGE || it.kind == MediaKind.ANIMATED || it.kind == MediaKind.VIDEO }
    val failed = e.statusStr == "error"
    val started = e.messages.firstOrNull { it.first == "execution_start" }?.second?.get("timestamp")?.toString()?.toDoubleOrNull()
    Row(
        modifier.fillMaxWidth().background(if (selected) Accent.clay.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
            when {
                first?.file != null -> AsyncImage(
                    model = Thumbs.request(context, session, first.file!!, first.kind).build(),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                failed -> Icon(Icons.Outlined.ErrorOutline, null, tint = Atelier.colors.error)
                outs.any { it.kind == MediaKind.AUDIO } -> Icon(Icons.Outlined.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                else -> Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selecting) Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
                tint = if (selected) Accent.ember else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name ?: outs.firstOrNull()?.file?.filename?.substringBeforeLast('_') ?: "Run ${e.promptId.take(8)}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                started?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.toLong())) },
                if (failed) "failed" else if (outs.isNotEmpty()) "${outs.size} output${if (outs.size == 1) "" else "s"}" else null,
                if (name == null) "from another client" else null,
            ).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = if (failed) Atelier.colors.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (!selecting) IconButton(onClick = onRerun) { Icon(Icons.Outlined.Replay, "Run again") }
    }
}

@Composable
private fun Section(title: String, count: String?, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        count?.let { Spacer(Modifier.width(8.dp)); Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/**
 * Memory: what the machine can still hand out, what ComfyUI holds, and the two levers — unload
 * the models, or drop cached results — each reporting what it gave back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryPanel(stats: SystemStats?, memory: BridgeMemory?, session: ServerSession, onChanged: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val total = memory?.total ?: stats?.ramTotal
    val free = memory?.available ?: stats?.ramFree

    suspend fun available(): Long? =
        (if (memory != null) runCatching { session.client.memory()?.available }.getOrNull() else null)
            ?: runCatching { session.client.systemStats().ramFree }.getOrNull()

    fun act(label: String, unload: Boolean, cache: Boolean) {
        busy = label; result = null
        scope.launch {
            val before = available()
            runCatching { session.client.free(unloadModels = unload, freeMemory = cache) }
                .onFailure { result = "Couldn't reach the server"; busy = null; return@launch }
            // The server acts between jobs; give it a moment, then measure.
            var after = before
            repeat(6) { delay(700); after = available() ?: after }
            val gained = if (before != null && after != null) (after - before) / GB else null
            result = when {
                gained == null -> "Asked the server to $label"
                gained >= 0.1 -> "Freed %.1f GB".format(gained)
                else -> "Nothing to free right now" + if (unload) "" else " — try unloading models"
            }
            busy = null
            onChanged()
        }
    }

    Slab(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Memory", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (total != null && free != null) Text("%.1f GB free of %.0f".format(free / GB, total / GB), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (total != null && total > 0 && free != null) {
            Spacer(Modifier.height(8.dp))
            val used = (total - free).coerceAtLeast(0)
            LinearProgressIndicator(
                progress = { used.toFloat() / total }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.extraSmall),
                color = if (used > total * 0.9) Atelier.colors.error else Atelier.colors.running, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        // A separate GPU pool only when there is one (unified-memory machines report the same pool twice).
        stats?.devices?.firstOrNull()?.takeIf { d -> d.vramTotal != null && total != null && d.vramTotal!! < total * 0.9 }?.let { d ->
            Spacer(Modifier.height(10.dp))
            val used = (d.vramTotal!! - (d.vramFree ?: 0)).coerceAtLeast(0)
            Row { Text("GPU", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)); Text("%.1f / %.1f GB".format(used / GB, d.vramTotal!! / GB), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { used.toFloat() / d.vramTotal!! }, modifier = Modifier.fillMaxWidth())
        }
        memory?.models?.takeIf { it.isNotEmpty() }?.let { models ->
            Spacer(Modifier.height(10.dp))
            Text("Loaded", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                models.forEach { m -> Tag("${m.name} · %.1f GB".format((if (m.loaded > 0) m.loaded else m.size) / GB)) }
            }
        } ?: run {
            if (memory != null) { Spacer(Modifier.height(6.dp)); Text("No models loaded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { act("unload models", unload = true, cache = true) }, enabled = busy == null) { Text("Unload models") }
            OutlinedButton(onClick = { act("free cache", unload = false, cache = true) }, enabled = busy == null) { Text("Free cache") }
            if (busy != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        result?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = Atelier.colors.done) }
        if (memory == null && stats != null) {
            Spacer(Modifier.height(6.dp))
            Text("Install Kouros Bridge on the server to see which models are loaded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
