package com.cocakova.kouros.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.cocakova.kouros.app
import com.cocakova.kouros.core.api.ModelDownload
import com.cocakova.kouros.data.AppStatus
import com.cocakova.kouros.net.ServerSession
import com.cocakova.kouros.ui.theme.Atelier
import com.cocakova.kouros.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where setting an app up has got to. */
private sealed interface Phase {
    data object Reading : Phase
    data object Waiting : Phase
    data object Working : Phase
    /** The pack is installed but the server has to restart before it can see it. */
    data object NeedsRestart : Phase
    data object Restarting : Phase
    data class Failed(val message: String) : Phase
}

/**
 * What an app still needs from this server, and the one button that gets it: the models come
 * down through the bridge, the node packs through the server's manager, and a restart lets the
 * server see them. A server that can do neither is told plainly, with what to install by hand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSetupSheet(session: ServerSession, status: AppStatus, onDismiss: () -> Unit, onReady: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var phase by remember { mutableStateOf<Phase>(Phase.Reading) }
    var canDownload by remember { mutableStateOf(false) }
    var hasManager by remember { mutableStateOf(false) }
    var downloads by remember { mutableStateOf<List<ModelDownload>>(emptyList()) }
    var jobIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installing by remember { mutableStateOf(false) }

    val needs = status.needs

    LaunchedEffect(status.spec.id) {
        canDownload = runCatching { session.client.bridge()?.canDownloadModels == true }.getOrDefault(false)
        hasManager = runCatching { session.client.managerVersion() != null }.getOrDefault(false)
        phase = Phase.Waiting
    }

    // While anything is in flight, follow the bridge's downloads and the manager's queue.
    LaunchedEffect(phase) {
        if (phase != Phase.Working) return@LaunchedEffect
        var polls = 0
        while (true) {
            downloads = runCatching { session.client.modelDownloads() }.getOrDefault(downloads).filter { it.id in jobIds }
            // A job the bridge no longer knows about went away with a restart; waiting for it
            // would be waiting forever.
            val lost = jobIds - downloads.map { it.id }.toSet()
            if (lost.isNotEmpty() && polls++ > 2) {
                phase = Phase.Failed("The server lost track of the download. Try again once it is settled.")
                return@LaunchedEffect
            }
            val fetching = downloads.any { it.running } || lost.isNotEmpty()
            val queue = if (installing) runCatching { session.client.managerQueue() }.getOrNull() else null
            val stillInstalling = installing && (queue == null || !queue.finished)
            if (!fetching && !stillInstalling) {
                val failed = downloads.filter { it.state == "failed" }
                phase = when {
                    failed.isNotEmpty() -> Phase.Failed(failed.joinToString("; ") { "${it.name}: ${it.error ?: "failed"}" })
                    installing -> Phase.NeedsRestart
                    else -> {
                        val now = runCatching { app.apps.status(session, status.spec) }.getOrNull()
                        if (now?.ready == true) { onReady(); return@LaunchedEffect } else Phase.Waiting
                    }
                }
                return@LaunchedEffect
            }
            delay(1200)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl).navigationBarsPadding().padding(bottom = Space.xl),
        ) {
            Text(status.spec.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(Space.xs))
            Text(
                status.spec.tagline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status.spec.about?.let {
                Spacer(Modifier.height(Space.m))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(Space.l))
            Text("This server still needs", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(Space.s))

            for (p in needs.packs) {
                NeedRow(
                    title = p.title,
                    detail = if (hasManager) "node pack · installed through the server's manager" else "node pack · install it on the server yourself",
                    trailing = {
                        if (!hasManager) TextButton(onClick = { clipboard.setText(AnnotatedString(p.repository)) }) { Text("Copy link") }
                    },
                )
            }
            for (m in needs.models) {
                val job = downloads.firstOrNull { it.name == m.name }
                NeedRow(
                    title = m.name,
                    detail = buildString {
                        append(m.directory)
                        m.size?.let { append(" · ${gb(it)}") }
                        if (m.url == null) append(" · no link — copy it in yourself")
                    },
                    progress = job?.fraction,
                    trailing = {
                        when (job?.state) {
                            "done" -> Text("done", style = MaterialTheme.typography.labelMedium, color = Atelier.colors.done)
                            "failed" -> Text("failed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                            else -> {}
                        }
                    },
                )
            }

            if (needs.models.isNotEmpty() && !canDownload && phase != Phase.Reading) {
                Spacer(Modifier.height(Space.m))
                Text(
                    "This server can't fetch models for itself. Install the Kouros Bridge on it, or put the files in the folders above by hand.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (needs.packs.isNotEmpty() && !hasManager && phase != Phase.Reading) {
                Spacer(Modifier.height(Space.m))
                Text(
                    "This server has no ComfyUI-Manager, so Kouros can't install the node pack for it. Clone the repository into the server's custom_nodes folder, install its requirements, and restart it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            (phase as? Phase.Failed)?.let {
                Spacer(Modifier.height(Space.m))
                Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(Space.l))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                when (phase) {
                    Phase.Reading -> {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Reading the server", style = MaterialTheme.typography.bodyMedium)
                    }
                    Phase.Working -> {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            if (installing) "Installing, then fetching" else "Fetching",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Phase.Restarting -> {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Restarting the server", style = MaterialTheme.typography.bodyMedium)
                    }
                    Phase.NeedsRestart -> {
                        Button(onClick = {
                            phase = Phase.Restarting
                            scope.launch {
                                runCatching { app.apps.restart(session) }
                                // The server drops its socket and comes back; wait for it to answer again.
                                val ready = waitForServer(session)
                                val now = runCatching { app.apps.status(session, status.spec) }.getOrNull()
                                phase = when {
                                    now?.ready == true -> { onReady(); return@launch }
                                    !ready -> Phase.Failed("The server didn't come back. Start it again and reopen this app.")
                                    else -> Phase.Waiting
                                }
                            }
                        }) { Text("Restart the server") }
                        Text("The pack is in place; a restart lets the server see it.", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        val canAct = (needs.models.isEmpty() || canDownload) && (needs.packs.isEmpty() || hasManager)
                        Button(
                            enabled = canAct,
                            onClick = {
                                phase = Phase.Working
                                scope.launch {
                                    installing = needs.packs.isNotEmpty() && app.apps.installPacks(session, needs.packs) == true
                                    jobIds = runCatching { app.apps.fetchModels(session, needs).toSet() }.getOrDefault(emptySet())
                                    if (!installing && jobIds.isEmpty()) {
                                        phase = Phase.Failed("Nothing could be started. Check the server's log.")
                                    }
                                }
                            },
                        ) { Text(if (needs.downloadSize != null && needs.models.isNotEmpty()) "Set up · ${gb(needs.downloadSize!!)}" else "Set up") }
                        TextButton(onClick = onDismiss) { Text("Not now") }
                    }
                }
            }
        }
    }
}

@Composable
private fun NeedRow(title: String, detail: String, progress: Float? = null, trailing: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(Space.s))
            trailing()
        }
        if (progress != null) {
            Spacer(Modifier.height(Space.xs))
            LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
        }
    }
}

/** Waits for a restarted server to answer again; false when it stays away. */
private suspend fun waitForServer(session: ServerSession, attempts: Int = 60): Boolean {
    delay(2000)
    repeat(attempts) {
        if (runCatching { session.client.systemStats() }.isSuccess) {
            runCatching { session.objectInfo(refresh = true) }
            return true
        }
        delay(2000)
    }
    return false
}
