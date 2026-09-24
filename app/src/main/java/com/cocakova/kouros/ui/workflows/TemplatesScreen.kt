package com.cocakova.kouros.ui.workflows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.cocakova.kouros.app
import com.cocakova.kouros.core.api.TemplateEntry
import com.cocakova.kouros.media.Thumbs
import com.cocakova.kouros.net.ServerSession
import com.cocakova.kouros.ui.CurrentServer
import com.cocakova.kouros.ui.components.EmptyState
import com.cocakova.kouros.ui.theme.Space
import com.cocakova.kouros.ui.theme.fieldColors
import kotlinx.coroutines.launch

/**
 * The server's own template gallery — the desktop's "Browse templates" — for starting from
 * scratch. Only templates that run on the person's own hardware are offered; the ones built on
 * paid cloud API nodes need an account with Comfy and are left out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val server by CurrentServer.server.collectAsState()
    val s = server ?: return
    val session = remember(s) { CurrentServer.session(s) }
    DisposableEffect(session) { session.acquire(); onDispose { session.release() } }
    val all by produceState<List<TemplateEntry>?>(null, session) { value = session.client.templates().filter { it.runsLocally } }
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<TemplateEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Templates", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        val list = all
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                EmptyState("No templates here", "This server's ComfyUI doesn't serve a template gallery. Newer versions do; you can still open workflow files from the phone.")
            }
            else -> {
                val groups = remember(list) { list.map { it.group }.distinct() }
                val shown = remember(list, query, group) {
                    list.filter { t ->
                        (group == null || t.group == group) &&
                            (query.isBlank() || listOf(t.title, t.description, t.models.joinToString(" "), t.tags.joinToString(" ")).any { it.contains(query, ignoreCase = true) })
                    }
                }
                LazyVerticalGrid(
                    GridCells.Adaptive(160.dp), Modifier.fillMaxSize().padding(pad),
                    contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = Space.xl),
                    horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        OutlinedTextField(
                            query, { query = it }, placeholder = { Text("Search templates or models") }, singleLine = true,
                            leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = MaterialTheme.shapes.medium, colors = fieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            item { FilterChip(group == null, { group = null }, label = { com.cocakova.kouros.ui.components.ChipLabel("All ${list.size}") }) }
                            items(groups, key = { it }) { g -> FilterChip(group == g, { group = if (group == g) null else g }, label = { com.cocakova.kouros.ui.components.ChipLabel(g) }) }
                        }
                    }
                    items(shown, key = { it.name }) { t -> TemplateCard(t, session) { chosen = t } }
                    if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Nothing matches.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space.l))
                    }
                }
            }
        }
    }
    chosen?.let { t -> TemplateSheet(t, session, onDismiss = { chosen = null }, onOpen = { key -> chosen = null; onOpen(key) }) }
}

@Composable
private fun TemplateCard(t: TemplateEntry, session: ServerSession, onClick: () -> Unit) {
    // Padded inside the clip so the rounded corners never cut into the text.
    Column(Modifier.clip(MaterialTheme.shapes.large).clickable(onClickLabel = "Details", onClick = onClick).padding(bottom = Space.s)) {
        Thumbnail(t, session, Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large))
        Spacer(Modifier.height(Space.s))
        Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(t.models.firstOrNull(), t.size?.let(::size)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Thumbnail(t: TemplateEntry, session: ServerSession, modifier: Modifier) {
    val context = LocalContext.current
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        if (t.thumbnailIsImage) AsyncImage(
            model = remember(t.name) {
                ImageRequest.Builder(context).data(session.client.endpoint.http(t.thumbnailPath)).httpHeaders(Thumbs.headers(session)).build()
            },
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        ) else Icon(Icons.Outlined.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateSheet(t: TemplateEntry, session: ServerSession, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = Space.gutter).navigationBarsPadding().imePadding().padding(bottom = Space.l).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Thumbnail(t, session, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large))
            Text(t.title, style = MaterialTheme.typography.headlineSmall)
            Text(t.group + (t.date?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodyMedium)
            if (t.models.isNotEmpty()) Text(
                "Uses " + t.models.joinToString(", ") + (t.size?.let { " · about ${size(it)} of model files" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Adds it to your workflows on the server, so the desktop has it too. If the server is missing any of its models, the form lists them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth().padding(top = Space.s), horizontalArrangement = Arrangement.End) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { app.workflows.addFromTemplate(session, t) }
                                .onSuccess(onOpen)
                                .onFailure { error = it.message ?: "Couldn't add it"; busy = false }
                        }
                    },
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Add to my workflows")
                }
            }
        }
    }
}

private fun size(bytes: Long): String =
    if (bytes >= 1_000_000_000) "%.0f GB".format(bytes / 1_073_741_824.0) else "%.0f MB".format(bytes / 1_048_576.0)
