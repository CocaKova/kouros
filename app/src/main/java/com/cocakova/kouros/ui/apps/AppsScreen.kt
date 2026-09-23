package com.cocakova.kouros.ui.apps

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cocakova.kouros.app
import com.cocakova.kouros.data.AppStatus
import com.cocakova.kouros.ui.CurrentServer
import com.cocakova.kouros.ui.components.EmptyState
import com.cocakova.kouros.ui.components.ScreenHeader
import com.cocakova.kouros.ui.components.Slab
import com.cocakova.kouros.ui.components.StatusDot
import com.cocakova.kouros.ui.theme.Atelier
import com.cocakova.kouros.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Apps: one-purpose tools built on a workflow — a photo in, a button, a result. Each card says
 * whether this server can run it yet, and the setup sheet is what closes the gap.
 */
@Composable
fun AppsScreen(pad: PaddingValues, onOpen: (String) -> Unit, onAddServer: () -> Unit) {
    val server by CurrentServer.server.collectAsState()
    val s = server
    if (s == null) {
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            EmptyState("No server yet", "Apps run on your own ComfyUI. Connect one and they appear here, ready or a tap from it.") {
                Button(onClick = onAddServer) { Text("Connect a server") }
            }
        }
        return
    }
    val session = remember(s) { CurrentServer.session(s) }
    DisposableEffect(session) { session.acquire(); onDispose { session.release() } }
    val conn by session.state.collectAsState()
    val scope = rememberCoroutineScope()

    var statuses by remember(s.id) { mutableStateOf<List<AppStatus>?>(null) }
    var reload by remember(s.id) { mutableStateOf(0) }
    var setup by remember { mutableStateOf<AppStatus?>(null) }
    LaunchedEffect(s.id, reload) { statuses = app.apps.statuses(session) }

    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = Space.xl)) {
        item { ScreenHeader("Apps", overline = s.name, status = { StatusDot(conn, 8.dp) }) }
        item {
            Text(
                "One thing each, set up once.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.gutter).padding(bottom = Space.s),
            )
        }
        val list = statuses
        if (list == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(Space.xxl), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            }
        } else {
            items(list, key = { it.spec.id }) { st ->
                AppCard(
                    st,
                    onClick = {
                        if (st.ready) scope.launch { onOpen(app.apps.open(session, st.spec)) } else setup = st
                    },
                )
            }
        }
    }

    setup?.let { st ->
        AppSetupSheet(
            session = session,
            status = st,
            onDismiss = { setup = null },
            onReady = {
                setup = null
                reload++
                scope.launch { onOpen(app.apps.open(session, st.spec)) }
            },
        )
    }
}

@Composable
private fun AppCard(st: AppStatus, onClick: () -> Unit) {
    Slab(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.xs).clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(iconFor(st.spec.icon), null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(st.spec.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    st.spec.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Space.s))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (st.ready) Atelier.colors.done else Atelier.colors.idle),
            )
            Text(
                statusLine(st),
                style = MaterialTheme.typography.labelLarge,
                color = if (st.ready) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** What the card says under the name: ready, or what is still missing and how big it is. */
private fun statusLine(st: AppStatus): String {
    if (st.ready) return "Ready"
    val parts = buildList {
        if (st.needs.packs.isNotEmpty()) add(st.needs.packs.joinToString(", ") { it.title })
        if (st.needs.models.isNotEmpty()) {
            val size = st.needs.downloadSize
            add(if (size != null) "${st.needs.models.size} model${if (st.needs.models.size > 1) "s" else ""} · ${gb(size)}" else "${st.needs.models.size} models")
        }
    }
    return "Needs " + parts.joinToString(" + ")
}

internal fun gb(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

private fun iconFor(name: String): ImageVector = when (name) {
    "upscale" -> Icons.Outlined.PhotoSizeSelectLarge
    "restore" -> Icons.Outlined.AutoFixHigh
    else -> Icons.Outlined.Apps
}
