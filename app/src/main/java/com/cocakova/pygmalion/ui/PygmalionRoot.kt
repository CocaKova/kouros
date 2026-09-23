package com.cocakova.pygmalion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cocakova.pygmalion.app
import com.cocakova.pygmalion.core.run.RunPhase
import com.cocakova.pygmalion.ui.components.ChiselProgress
import com.cocakova.pygmalion.ui.gallery.GalleryScreen
import com.cocakova.pygmalion.ui.gallery.ResultViewer
import com.cocakova.pygmalion.ui.queue.QueueScreen
import com.cocakova.pygmalion.ui.run.RunScreen
import com.cocakova.pygmalion.ui.servers.ServersScreen
import com.cocakova.pygmalion.ui.theme.Atelier
import com.cocakova.pygmalion.ui.workflows.WorkflowsScreen
import android.net.Uri

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("workflows", "Workflows", Icons.Outlined.ViewAgenda),
    Tab("gallery", "Gallery", Icons.Outlined.PhotoLibrary),
    Tab("queue", "Queue", Icons.Outlined.Queue),
    Tab("servers", "Servers", Icons.Outlined.Dns),
)

/** Deep link from a notification: the run to show. */
object PendingOpen {
    val run = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

@Composable
fun PygmalionRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val onTab = tabs.any { it.route == route }

    val pendingRun by PendingOpen.run.collectAsState()
    LaunchedEffect(pendingRun) {
        val id = pendingRun ?: return@LaunchedEffect
        PendingOpen.run.value = null
        val run = app.db.runs().get(id) ?: return@LaunchedEffect
        if (run.state.name in setOf("SUBMITTING", "QUEUED", "RUNNING") && run.workflowKey != null) nav.navigate("run/${Uri.encode(run.workflowKey)}")
        else nav.navigate("result/${run.serverId}/${run.promptId}/0")
    }

    Scaffold(
        bottomBar = {
            if (onTab) Column {
                NowRunning(nav)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo("workflows") { saveState = true }
                                    launchSingleTop = true; restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "workflows") {
            composable("workflows") {
                WorkflowsScreen(pad, onOpen = { key -> nav.navigate("run/${Uri.encode(key)}") }, onAddServer = { nav.navigate("servers") })
            }
            composable("gallery") { GalleryScreen(pad) { s, p, i -> nav.navigate("result/$s/$p/$i") } }
            composable("queue") { QueueScreen(pad) }
            composable("servers") { ServersScreen(pad) }
            composable(
                "run/{key}?remix={remix}",
                arguments = listOf(navArgument("key") { type = NavType.StringType }, navArgument("remix") { type = NavType.StringType; nullable = true }),
            ) { e ->
                RunScreen(
                    workflowKey = Uri.decode(e.arguments!!.getString("key")!!),
                    remixRunId = e.arguments?.getString("remix"),
                    onBack = { nav.popBackStack() },
                    onOpenResult = { id -> nav.navigate("result/_/$id/0") },
                )
            }
            composable("result/{server}/{prompt}/{index}") { e ->
                val a = e.arguments!!
                val promptId = a.getString("prompt")!!
                val serverArg = a.getString("server")!!
                val serverId by produceState(serverArg.takeIf { it != "_" }, promptId) {
                    if (value == null) value = app.db.runs().get(promptId)?.serverId
                }
                serverId?.let { sid ->
                    ResultViewer(sid, promptId, a.getString("index")!!.toIntOrNull() ?: 0, onBack = { nav.popBackStack() }) { key, runId ->
                        nav.navigate("run/${Uri.encode(key)}?remix=$runId")
                    }
                }
            }
        }
    }
}

/** A slim bar above the tabs while something is running: name, node, the chisel line. */
@Composable
private fun NowRunning(nav: NavHostController) {
    val progress by app.runs.progress.collectAsState()
    val active by app.db.runs().active().collectAsState(initial = emptyList())
    val lead = active.firstOrNull { progress[it.promptId]?.phase == RunPhase.RUNNING } ?: active.firstOrNull()
    val p = lead?.let { progress[it.promptId] }
    AnimatedVisibility(lead != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        val run = lead ?: return@AnimatedVisibility
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.clickable {
            run.workflowKey?.let { nav.navigate("run/${Uri.encode(it)}") }
        }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(run.workflowName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (p?.phase) {
                            RunPhase.RUNNING -> "${(p.fraction * 100).toInt()}%"
                            null, RunPhase.SUBMITTING -> "sending"
                            else -> "queued" + (if (active.size > 1) " · ${active.size}" else "")
                        },
                        style = MaterialTheme.typography.labelLarge, color = Atelier.colors.running,
                    )
                }
                if (p != null && p.phase == RunPhase.RUNNING) {
                    Spacer(Modifier.height(6.dp))
                    ChiselProgress(p.totalNodes, p.doneNodes, if (p.steps > 0) p.step.toFloat() / p.steps else 0f)
                }
            }
        }
    }
}
