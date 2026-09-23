package com.cocakova.kouros.ui.servers

import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cocakova.kouros.app
import com.cocakova.kouros.data.ServerEntity
import kotlinx.coroutines.launch
import com.cocakova.kouros.net.ConnState
import com.cocakova.kouros.ui.CurrentServer
import com.cocakova.kouros.ui.components.EmptyState
import com.cocakova.kouros.ui.components.ScreenHeader
import com.cocakova.kouros.ui.components.Slab
import com.cocakova.kouros.ui.components.StatusDot
import com.cocakova.kouros.ui.theme.Atelier

@Composable
fun ServersScreen(pad: PaddingValues, onSettings: () -> Unit = {}, onConsole: (String) -> Unit = {}) {
    val servers by CurrentServer.servers.collectAsState()
    val current by CurrentServer.server.collectAsState()
    var editing by remember { mutableStateOf<ServerEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(pad)) {
        if (servers.isEmpty()) {
            IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Icon(Icons.Outlined.Settings, "Settings") }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(
                    "Connect your ComfyUI",
                    "Point Kouros at the ComfyUI server you already run — on your desktop, a home server or a cloud GPU. Everything stays between your phone and your server.",
                ) { Button(onClick = { adding = true }) { Text("Add a server") } }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    ScreenHeader("Servers", overline = "Where your workflows run") {
                        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                    }
                }
                items(servers, key = { it.id }) { s ->
                    ServerRow(s, selected = s.id == current?.id, onSelect = { CurrentServer.select(s.id) }, onEdit = { editing = s }, onConsole = { onConsole(s.id) })
                }
                item { SecurityNote(Modifier.padding(16.dp)) }
            }
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("Add server") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
    if (adding || editing != null) {
        ServerEditor(existing = editing, onDismiss = { adding = false; editing = null })
    }
}

@Composable
private fun ServerRow(s: ServerEntity, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit, onConsole: () -> Unit) {
    val session = remember(s) { CurrentServer.session(s) }
    DisposableEffect(session) { session.acquire(); onDispose { session.release() } }
    val state by session.state.collectAsState()
    Slab(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onSelect)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(state, 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium)
                Text(s.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (selected) Icon(Icons.Outlined.CheckCircle, "Selected", tint = Atelier.colors.done)
            IconButton(onClick = onConsole) { Icon(Icons.Outlined.Terminal, "Console") }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit") }
        }
        Spacer(Modifier.height(8.dp))
        Text(describe(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PowerButtons(s)
    }
}

/** Start / Stop, when the user configured power controls; the answer is shown as the server gave it. */
@Composable
fun PowerButtons(s: ServerEntity) {
    val power = remember(s.powerJson) { com.cocakova.kouros.net.PowerControl.parse(s.powerJson) } ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<com.cocakova.kouros.net.PowerControl.Result?>(null) }
    fun go(a: com.cocakova.kouros.net.PowerControl.Action) {
        busy = true
        scope.launch {
            answer = power.invoke(s, a)
            busy = false
            app.sessions.get(s).kick()
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (power.startUrl != null) androidx.compose.material3.OutlinedButton(enabled = !busy, onClick = { go(com.cocakova.kouros.net.PowerControl.Action.START) }) { Text("Start") }
        if (power.stopUrl != null) androidx.compose.material3.OutlinedButton(enabled = !busy, onClick = { go(com.cocakova.kouros.net.PowerControl.Action.STOP) }) { Text("Stop") }
        if (power.statusUrl != null) androidx.compose.material3.TextButton(enabled = !busy, onClick = { go(com.cocakova.kouros.net.PowerControl.Action.STATUS) }) { Text("Status") }
    }
    answer?.let { a ->
        Text(a.text, style = MaterialTheme.typography.bodySmall, color = if (a.ok) Atelier.colors.done else Atelier.colors.error)
    }
}

internal fun describe(state: ConnState): String = when (state) {
    is ConnState.Online -> state.stats?.let { st ->
        val dev = st.devices.firstOrNull()
        listOfNotNull(
            st.comfyuiVersion?.let { "ComfyUI $it" },
            dev?.name?.substringBefore(" : ")?.removePrefix("cuda:0 ")?.take(40),
            dev?.vramTotal?.takeIf { it > 0 }?.let { t -> "${gb(dev.vramFree ?: 0)} / ${gb(t)} free" },
        ).joinToString(" · ")
    } ?: "Online"
    is ConnState.Connecting -> "Connecting…"
    is ConnState.Degraded -> "Reachable, but live updates are blocked (${state.reason}). Progress will update by polling."
    is ConnState.Offline -> "Offline — ${state.reason}. Retrying in ${state.retryInMs / 1000}s"
    ConnState.Idle -> "Not connected"
}

private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)

@Composable
fun SecurityNote(modifier: Modifier = Modifier) {
    Slab(modifier) {
        Text("About security", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "ComfyUI has no password of its own: anyone who can reach its address can run anything on it, including code from custom nodes. " +
                "Keep it on your home network, a VPN such as Tailscale, or behind a reverse proxy with authentication. Never forward its port to the internet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
