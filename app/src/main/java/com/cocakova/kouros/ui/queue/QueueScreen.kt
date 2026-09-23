package com.cocakova.kouros.ui.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.cocakova.kouros.app
import com.cocakova.kouros.core.api.QueueSnapshot
import com.cocakova.kouros.core.api.SystemStats
import com.cocakova.kouros.ui.CurrentServer
import com.cocakova.kouros.ui.components.ChiselProgress
import com.cocakova.kouros.ui.components.EmptyState
import com.cocakova.kouros.ui.components.ScreenHeader
import com.cocakova.kouros.ui.components.Slab
import com.cocakova.kouros.ui.components.Tag
import com.cocakova.kouros.ui.theme.Atelier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun QueueScreen(pad: PaddingValues) {
    val server by CurrentServer.server.collectAsState()
    val s = server ?: run { EmptyState("No server", "Add a server to see its queue.", Modifier.padding(pad)); return }
    val session = remember(s) { CurrentServer.session(s) }
    DisposableEffect(session) { session.acquire(); onDispose { session.release() } }
    var queue by remember { mutableStateOf<QueueSnapshot?>(null) }
    var stats by remember { mutableStateOf<SystemStats?>(null) }
    val progress by app.runs.progress.collectAsState()
    val runs by app.db.runs().recent(200).collectAsState(initial = emptyList())
    val names = remember(runs) { runs.associate { it.promptId to it.workflowName } }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }

    // The queue changes on the server whether or not we caused it: poll while visible.
    LaunchedEffect(s.id, tick) {
        while (true) {
            queue = runCatching { session.client.queue() }.getOrNull() ?: queue
            stats = runCatching { session.client.systemStats() }.getOrNull() ?: stats
            delay(2500)
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenHeader("Queue", overline = s.name) }
        stats?.let { st -> item { Resources(st, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) } }
        val q = queue
        if (q != null && q.running.isEmpty() && q.pending.isEmpty()) item {
            EmptyState("The server is idle", "Queued and running prompts from every client — this phone, the desktop, anything — show up here.")
        }
        q?.running?.let { running ->
            items(running, key = { "r" + it.promptId }) { item ->
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
        }
        q?.pending?.sortedBy { it.number ?: 0.0 }?.let { pending ->
            items(pending, key = { "p" + it.promptId }) { item ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(names[item.promptId] ?: "Queued", style = MaterialTheme.typography.bodyLarge)
                        Text(item.promptId.take(8), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { scope.launch { runCatching { session.client.deleteQueued(listOf(item.promptId)) }; tick++ } }) { Icon(Icons.Outlined.Close, "Remove") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { scope.launch { runCatching { session.client.free() } } }) { Text("Free memory") }
                if ((queue?.pending?.size ?: 0) > 0) TextButton(onClick = {
                    scope.launch { runCatching { session.client.deleteQueued(queue!!.pending.map { it.promptId }) }; tick++ }
                }) { Text("Clear waiting") }
            }
        }
    }
}

@Composable
private fun Resources(st: SystemStats, modifier: Modifier) {
    Slab(modifier.fillMaxWidth()) {
        st.devices.firstOrNull()?.let { d ->
            Meter(d.name.substringBefore(" : ").removePrefix("cuda:0").trim().ifEmpty { "GPU" }, d.vramTotal, d.vramFree)
            Spacer(Modifier.height(10.dp))
        }
        Meter("System memory", st.ramTotal, st.ramFree)
    }
}

@Composable
private fun Meter(label: String, total: Long?, free: Long?) {
    if (total == null || total <= 0) return
    val used = (total - (free ?: 0)).coerceAtLeast(0)
    Row { Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)); Text("%.1f / %.1f GB".format(used / 1.073741824e9, total / 1.073741824e9), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Spacer(Modifier.height(4.dp))
    LinearProgressIndicator(progress = { used.toFloat() / total }, modifier = Modifier.fillMaxWidth(), color = if (used > total * 0.9) Atelier.colors.error else MaterialTheme.colorScheme.primary)
}
