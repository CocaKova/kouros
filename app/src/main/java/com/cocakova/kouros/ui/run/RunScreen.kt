package com.cocakova.kouros.ui.run

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import com.cocakova.kouros.core.assist.AssistRequest
import com.cocakova.kouros.core.form.isMultilineText
import com.cocakova.kouros.data.Settings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.cocakova.kouros.app
import com.cocakova.kouros.core.api.MediaKind
import com.cocakova.kouros.core.compile.PromptValidator
import com.cocakova.kouros.core.compile.TemplateSource
import com.cocakova.kouros.core.form.FieldRole
import com.cocakova.kouros.core.form.FormField
import com.cocakova.kouros.core.run.RunPhase
import com.cocakova.kouros.core.run.RunProgress
import com.cocakova.kouros.media.Thumbs
import com.cocakova.kouros.run.RunCoordinator
import com.cocakova.kouros.ui.components.ChiselProgress
import com.cocakova.kouros.ui.components.PlinthMark
import com.cocakova.kouros.ui.components.Slab
import com.cocakova.kouros.ui.components.Tag
import com.cocakova.kouros.ui.theme.Accent
import com.cocakova.kouros.ui.theme.Atelier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(workflowKey: String, remixRunId: String?, onBack: () -> Unit, onOpenResult: (String) -> Unit) {
    val model: RunModel = viewModel(key = "$workflowKey|$remixRunId") { RunModel(workflowKey, remixRunId) }
    val state by model.state.collectAsState()
    val busy by model.busy.collectAsState()
    val lastRun by model.lastRun.collectAsState()
    val allProgress by app.runs.progress.collectAsState()
    val progress = lastRun?.let { allProgress[it] }
    val uploading by model.uploading.collectAsState()
    val snack = remember { SnackbarHostState() }
    val message by model.message.collectAsState()
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); model.consumeMessage() } }

    // The field waiting for a picked file.
    var picking by remember { mutableStateOf<FormField?>(null) }
    // The prompt field the assistant is rewriting.
    val assist by Settings.assist.collectAsState()
    var enhancing by remember { mutableStateOf<FormField?>(null) }
    val pickVisual = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val f = picking; picking = null
        if (uri != null && f != null) model.upload(f, uri)
    }
    val pickAny = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val f = picking; picking = null
        if (uri != null && f != null) model.upload(f, uri)
    }
    // Reference photos: several at once, as many as the workflow has room for.
    val downloads by model.downloads.collectAsState()
    var arranging by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var savingPreset by remember { mutableStateOf(false) }
    val readyNow = state as? RunScreenState.Ready
    val refRoom = readyNow?.let { it.references.capacity - it.refs.size } ?: 0
    val pickRefs = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, minOf(refRoom, 16)))) { uris ->
        uris.take(maxOf(refRoom, 0)).forEach { model.addReference(it) }
    }

    // Notifications are asked for at the first Run — when it's clear what they are for — and once only.
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun runWithNotifications(n: Int) {
        val prefs = context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !app.notifier.canPost() && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        model.run(n)
    }

    val ready = state as? RunScreenState.Ready
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ready?.workflow?.name ?: "", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ready?.let { r ->
                            val spec = r.appSpec
                            if (spec != null) {
                                Text(
                                    spec.tagline, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            } else SourceLine(r.template.source)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    if (arranging) TextButton(onClick = { arranging = false }) { Text("Done") }
                    else Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More options") }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Arrange fields") }, leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                                enabled = ready != null, onClick = { menuOpen = false; arranging = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Save as preset") }, leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, null) },
                                enabled = ready != null, onClick = { menuOpen = false; savingPreset = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Reload from server") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                onClick = { menuOpen = false; model.load(true) },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (ready != null && !arranging) RunBar(
                busy = busy,
                running = progress != null && !progress.phase.isTerminal,
                issues = ready.issues,
                action = ready.appSpec?.action ?: "Run",
                onRun = { n -> runWithNotifications(n) },
                onCancel = { model.cancel() },
            )
        },
    ) { pad ->
        when (val st = state) {
            RunScreenState.Loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Reading the workflow…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is RunScreenState.Failed -> Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(st.message, style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { model.load(true) }) { Text("Try again") }
                }
            }
            is RunScreenState.Ready -> {
                var advancedOpen by remember { mutableStateOf(false) }
                val session = model.session ?: return@Scaffold
                LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Stage(
                            progress = progress,
                            lastRunId = lastRun,
                            workflowKey = st.workflow.key,
                            onOpenResult = onOpenResult,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    if (st.missing.isNotEmpty()) item(key = "missing") {
                        MissingModels(st.missing, downloads, st.canDownload, onDownload = { model.download(it) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    // Missing model files are covered above; the rest of what the server may refuse, here.
                    val otherIssues = st.issues.filterNot { i -> i.kind == PromptValidator.Kind.BAD_CHOICE && st.missing.any { it.name == i.value } }
                    if (otherIssues.isNotEmpty()) item { Issues(otherIssues, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                    if (arranging) {
                        item(key = "arrange") { ArrangeFields(st, onEdit = { model.editLayout(it) }, onDone = { arranging = false }, modifier = Modifier.padding(vertical = 8.dp)) }
                        return@LazyColumn
                    }
                    if (st.layout.presets.isNotEmpty()) item(key = "presets") {
                        PresetRow(
                            st.layout.presets.keys, onApply = { model.applyPreset(it) }, onSave = { model.savePreset(it) }, onDelete = { model.deletePreset(it) },
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    if (st.template.source == TemplateSource.COMPILED_UNSURE) item { UnsureNote(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                    items(st.form.hero, key = { it.key }) { f ->
                        FieldControl(
                            f, st.values[f.key] ?: f.initial, { model.set(f, it) }, session, f.key in uploading,
                            onPickMedia = { picking = f; launchPicker(f, pickVisual, pickAny) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            control = st.controls[f.key], onControl = { model.setControl(f, it) },
                            onEnhance = if (assist.enabled && f.spec.isMultilineText()) ({ enhancing = f }) else null,
                        )
                    }
                    if (st.references.capacity > 0) item(key = "refs") {
                        ReferenceStrip(
                            st, session, uploading = uploading.count { it.startsWith("ref:") },
                            onAdd = { pickRefs.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onRemove = { model.removeReference(it) },
                            onInsert = { model.insertIntoPrompt(it) },
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                    if (st.form.advanced.isNotEmpty()) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().clickable { advancedOpen = !advancedOpen }.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Advanced", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text("${st.form.advanced.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(if (advancedOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                            }
                        }
                        if (advancedOpen) {
                            st.form.advanced.groupBy { it.group }.forEach { (group, fields) ->
                                item(key = "g:$group") {
                                    Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
                                }
                                items(fields, key = { it.key }) { f ->
                                    FieldControl(
                                        f, st.values[f.key] ?: f.initial, { model.set(f, it) }, session, f.key in uploading,
                                        onPickMedia = { picking = f; launchPicker(f, pickVisual, pickAny) },
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        control = st.controls[f.key], onControl = { model.setControl(f, it) },
                                        onEnhance = if (assist.enabled && f.spec.isMultilineText()) ({ enhancing = f }) else null,
                                    )
                                }
                            }
                        }
                    }
                }
                if (savingPreset) PresetNameDialog(onSave = { model.savePreset(it); savingPreset = false }, onDismiss = { savingPreset = false })
                enhancing?.let { f ->
                    EnhanceSheet(
                        request = assistRequest(st, f),
                        onResult = { text -> model.set(f, JsonPrimitive(text)); enhancing = null },
                        onDismiss = { enhancing = null },
                    )
                }
            }
        }
    }
}

/** Everything the assistant is told: this prompt, its counterpart, what the workflow makes and loads. */
private fun assistRequest(st: RunScreenState.Ready, f: FormField): AssistRequest {
    fun text(field: FormField?) = field?.let { (st.values[it.key] as? JsonPrimitive)?.contentOrNull }
    val negative = f.role == FieldRole.NEGATIVE_PROMPT
    val counterpart = st.form.all.firstOrNull { it.role == if (negative) FieldRole.PROMPT else FieldRole.NEGATIVE_PROMPT }
    return AssistRequest(
        current = text(f) ?: "",
        instruction = "",
        negative = negative,
        workflowName = st.workflow.name,
        outputKind = st.traits?.primary?.noun,
        models = st.traits?.models.orEmpty().take(8),
        counterpart = text(counterpart),
        references = st.refs.indices.mapNotNull { st.references.tokenFor(it) },
        // Samplers skip the negative pass entirely at CFG 1.
        negativeActive = st.form.all.firstOrNull { it.spec.name == "cfg" }?.let { (st.values[it.key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() != 1.0 } ?: true,
    )
}

/**
 * Photos the model will look at alongside the prompt, wired into the workflow's free reference
 * slots. Each shows the name the model gives it, which is how the prompt refers to it.
 */
@Composable
private fun ReferenceStrip(
    st: RunScreenState.Ready,
    session: com.cocakova.kouros.net.ServerSession,
    uploading: Int,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onInsert: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val room = minOf(st.references.capacity, 16)
    Column(modifier) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("REFERENCES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Text("${st.refs.size} of $room", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(st.refs, key = { i, r -> "$i:$r" }) { i, path ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(88.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        AsyncImage(
                            model = Thumbs.request(context, session, inputRef(path), MediaKind.IMAGE).build(),
                            contentDescription = st.references.labelFor(i), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        )
                        Surface(
                            onClick = { onRemove(i) }, shape = CircleShape, color = MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp),
                        ) { Icon(Icons.Outlined.Close, "Remove", Modifier.padding(4.dp)) }
                    }
                    Spacer(Modifier.height(4.dp))
                    // The name the prompt uses for this photo; tap to write it into the prompt.
                    val token = st.references.tokenFor(i) ?: ""
                    Surface(onClick = { onInsert(token) }, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                        Text(token, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }
            if (uploading > 0) items(uploading) {
                Box(Modifier.size(88.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            if (st.refs.size + uploading < room) item {
                Surface(
                    onClick = onAdd, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.size(88.dp),
                ) {
                    Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.AddPhotoAlternate, null, tint = Accent.clay)
                        Spacer(Modifier.height(4.dp))
                        Text("Add", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (st.refs.isEmpty()) "Add photos for the model to work from, then refer to each in the prompt by its name, like ${st.references.tokenFor(0) ?: "image 1"}."
            else "Name each photo in the prompt to say what it is for (\"the phone from ${st.references.tokenFor(0)}\"). Tap a name to add it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

private fun launchPicker(
    f: FormField,
    visual: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>,
    any: androidx.activity.result.ActivityResultLauncher<String>,
) {
    when (f.spec.uploadKind) {
        "audio" -> any.launch("audio/*")
        "video" -> visual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        else -> visual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

@Composable
private fun SourceLine(source: TemplateSource) {
    val (text, warn) = when (source) {
        TemplateSource.API_FILE -> "API workflow" to false
        TemplateSource.COMPILED -> "Ready" to false
        TemplateSource.FRONTEND -> "Compiled by your server's editor" to false
        TemplateSource.HISTORY -> "Using its last run on the server" to false
        TemplateSource.EMBEDDED -> "Using its saved prompt" to false
        TemplateSource.COMPILED_UNSURE -> "Compiled with warnings" to true
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = if (warn) Atelier.colors.running else MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The stage: while a run is going it shows the live preview and the chisel line; when a run has
 * finished it shows the result; before anything, the mark on its plinth.
 */
@Composable
private fun Stage(progress: RunProgress?, lastRunId: String?, workflowKey: String, onOpenResult: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val preview by produceState<Bitmap?>(null, lastRunId) {
        app.runs.preview.filter { it.promptId == lastRunId }.collect { f ->
            value = withContext(Dispatchers.Default) { Thumbs.decode(f.bytes, 1024) }
        }
    }
    // The last finished run of this workflow, shown when nothing is running.
    val lastDone by produceState<com.cocakova.kouros.data.RunEntity?>(null, workflowKey, progress?.phase) {
        value = app.db.runs().lastFor(workflowKey)
    }
    LaunchedEffect(progress?.phase) {
        when (progress?.phase) {
            RunPhase.SUCCEEDED -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            RunPhase.FAILED -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            else -> Unit
        }
    }
    val running = progress != null && !progress.phase.isTerminal
    Column(modifier.animateContentSize()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(24.dp))
                .background(Brush.radialGradient(listOf(Accent.clay.copy(alpha = 0.18f), MaterialTheme.colorScheme.surfaceContainerLow)))
                .clickable(enabled = !running && lastDone != null, onClickLabel = "Open the last result") { lastDone?.let { onOpenResult(it.promptId) } },
            contentAlignment = Alignment.Center,
        ) {
            val finishedOutput = lastDone?.let { r ->
                com.cocakova.kouros.core.api.Outputs.forViewing(RunCoordinator.decodeOutputs(r.outputsJson))
                    .firstOrNull { it.kind == MediaKind.IMAGE || it.kind == MediaKind.ANIMATED || it.kind == MediaKind.VIDEO || it.kind == MediaKind.AUDIO }
            }
            when {
                running && preview != null -> Crossfade(preview, animationSpec = tween(350), label = "preview") { bmp ->
                    bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                }
                // A track has no thumbnail: say what was made and that tapping plays it.
                !running && finishedOutput?.kind == MediaKind.AUDIO -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.GraphicEq, null, tint = Accent.clay, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        finishedOutput.file?.filename ?: "Track", style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Text("Tap to listen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                !running && finishedOutput?.file != null -> {
                    val session = remember(lastDone) { lastDone?.serverId }
                    val s by produceState<com.cocakova.kouros.net.ServerSession?>(null, session) { value = session?.let { app.sessions.byId(it) } }
                    s?.let {
                        AsyncImage(
                            model = Thumbs.request(context, it, finishedOutput.file!!, finishedOutput.kind, thumbnail = false).build(),
                            contentDescription = "Last result", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                else -> PlinthMark(Modifier.size(96.dp))
            }
            if (running && preview == null) {
                Text(
                    if (progress!!.phase == RunPhase.RUNNING) "Shaping…" else "Waiting in the queue",
                    style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
        AnimatedVisibility(running) {
            val p = progress ?: return@AnimatedVisibility
            Column(Modifier.padding(top = 12.dp)) {
                ChiselProgress(p.totalNodes, p.doneNodes, if (p.steps > 0) p.step.toFloat() / p.steps else 0f)
                Spacer(Modifier.height(6.dp))
                Row {
                    Text(
                        when (p.phase) {
                            RunPhase.RUNNING -> p.currentTitle ?: "Working"
                            RunPhase.SUBMITTING -> "Sending"
                            else -> "Queued" + (p.queueAhead?.takeIf { it > 0 }?.let { " · $it ahead" } ?: "")
                        },
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (p.steps > 0) Text("${p.step}/${p.steps}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("${(p.fraction * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, color = Atelier.colors.running)
                }
            }
        }
        progress?.takeIf { it.phase == RunPhase.FAILED }?.error?.let { e ->
            Text("${e.nodeType ?: "Run"} failed: ${e.message}", color = Atelier.colors.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun Issues(issues: List<PromptValidator.Issue>, modifier: Modifier) {
    Slab(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, null, tint = Atelier.colors.running)
            Spacer(Modifier.width(8.dp))
            Text("The server may refuse this", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(6.dp))
        issues.take(4).forEach { i ->
            Text(
                when (i.kind) {
                    PromptValidator.Kind.MISSING_NODE -> "Missing node type ${i.value} — install its node pack"
                    PromptValidator.Kind.BAD_CHOICE -> "'${i.value}' isn't on the server (${i.input})"
                    PromptValidator.Kind.MISSING_INPUT -> i.message
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (issues.size > 4) Text("and ${issues.size - 4} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UnsureNote(modifier: Modifier) {
    Slab(modifier) {
        Text("Part of this workflow is computed by the desktop editor when it queues (a painted mask, a 3D view…). " +
            "It will run with the values last saved in the workflow.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RunBar(
    busy: Boolean,
    running: Boolean,
    issues: List<PromptValidator.Issue>,
    /** What the button says when nothing is running — an app names its own verb ("Restore"). */
    action: String,
    onRun: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    var count by remember { mutableIntStateOf(1) }
    val haptics = LocalHapticFeedback.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Batch stepper: ×1 … ×8.
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { if (count > 1) count-- }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                Text("×$count", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { if (count < 8) count++ }) { Text("+", style = MaterialTheme.typography.titleLarge) }
            }
            Spacer(Modifier.width(12.dp))
            if (running) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.height(52.dp)) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
            }
            Button(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onRun(count) },
                enabled = !busy,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent.clay, contentColor = MaterialTheme.colorScheme.background),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.background)
                else Text(
                    if (running) (if (count > 1) "Queue $count" else "Queue") else if (count > 1) "$action $count" else action,
                    style = MaterialTheme.typography.titleMedium, maxLines = 1,
                )
            }
        }
    }
}
