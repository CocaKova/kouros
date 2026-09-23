package com.cocakova.kouros.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.cocakova.kouros.BuildConfig
import com.cocakova.kouros.app
import com.cocakova.kouros.core.assist.AssistEndpoint
import com.cocakova.kouros.core.assist.PromptAssist
import com.cocakova.kouros.data.Settings
import com.cocakova.kouros.net.Http
import com.cocakova.kouros.ui.components.Slab
import com.cocakova.kouros.ui.theme.Accent
import com.cocakova.kouros.ui.theme.Atelier
import com.cocakova.kouros.ui.theme.Space
import com.cocakova.kouros.ui.theme.fieldColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                .padding(horizontal = Space.gutter, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            Section("Look") {
                val mode by Settings.theme.collectAsState()
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Settings.ThemeMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = mode == m, onClick = { Settings.setTheme(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, Settings.ThemeMode.entries.size),
                        ) { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
            }

            Section("Live previews", "How the server draws the image while it forms. Sent with each run.") {
                var method by remember { mutableStateOf(Settings.previewMethod) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    listOf("auto" to "Best", "latent2rgb" to "Fast", "none" to "Off", "default" to "Server's own").forEach { (v, label) ->
                        FilterChip(
                            selected = method == v, onClick = { method = v; Settings.previewMethod = v }, label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                        )
                    }
                }
            }

            AssistSection()

            Section("About") {
                Text("Kouros ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Written and maintained by CocaKova. PolyForm Noncommercial 1.0.0.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.heightIn(min = Space.xl))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistSection() {
    val saved by Settings.assist.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(saved.url) }
    var model by remember { mutableStateOf(saved.model) }
    var system by remember { mutableStateOf(saved.system.ifBlank { PromptAssist.DEFAULT_SYSTEM }) }
    val hasKey = remember { app.secrets.get(Settings.ASSIST_KEY) != null }
    var key by remember { mutableStateOf<String?>(null) } // null = unchanged
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var testing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    var showSystem by remember { mutableStateOf(false) }

    fun save() {
        val sys = system.takeUnless { it.trim() == PromptAssist.DEFAULT_SYSTEM.trim() } ?: ""
        Settings.setAssist(Settings.Assist(url, model, sys), key)
        key = null
        note = (if (url.isBlank()) "Assistant turned off" else "Saved") to false
    }

    fun test() {
        testing = true; note = null
        scope.launch {
            val k = key ?: app.secrets.get(Settings.ASSIST_KEY)
            note = runCatching { PromptAssist(Http.patient, AssistEndpoint(url.trim(), k)).models() }
                .fold(
                    { list -> models = list; (if (list.isEmpty()) "Connected, but it lists no models" else "Connected — ${list.size} model${if (list.size == 1) "" else "s"}") to list.isEmpty() },
                    { e -> (e.message ?: "Couldn't reach it") to true },
                )
            if (model.isBlank()) models.firstOrNull()?.let { model = it }
            testing = false
        }
    }

    Section(
        "Prompt assistant",
        "A spark on every prompt field sends it to an OpenAI-compatible endpoint for a rewrite. Point it at an agent " +
            "(such as a Hermes gateway) and it can draw on what that agent knows; point it at a plain model server " +
            "(Ollama, LM Studio, vLLM, a hosted API) for a straight rewrite.",
    ) {
        OutlinedTextField(
            url, { url = it }, label = { Text("Endpoint URL") }, placeholder = { Text("http://host:port/v1") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = fieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            key ?: "", { key = it }, label = { Text("API key") },
            placeholder = { Text(if (hasKey) "Saved — type to replace" else "Optional") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = fieldColors(),
        )
        OutlinedTextField(
            model, { model = it }, label = { Text("Model") }, placeholder = { Text("Blank = the first one it lists") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = fieldColors(),
        )
        if (models.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            models.take(12).forEach { m -> FilterChip(selected = m == model, onClick = { model = m }, label = { Text(m) }) }
        }
        TextButton(onClick = { showSystem = !showSystem }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(if (showSystem) "Hide instructions" else "Edit the assistant's instructions")
        }
        if (showSystem) {
            OutlinedTextField(
                system, { system = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                textStyle = MaterialTheme.typography.bodySmall, shape = MaterialTheme.shapes.medium, colors = fieldColors(),
            )
            TextButton(onClick = { system = PromptAssist.DEFAULT_SYSTEM }) { Text("Reset to default") }
        }
        note?.let { (text, isError) ->
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (isError) Atelier.colors.error else Atelier.colors.done)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { test() }, enabled = url.isNotBlank() && !testing) { Text(if (testing) "Testing…" else "Test") }
            Spacer(Modifier.width(Space.s))
            Button(
                onClick = { save() },
                colors = ButtonDefaults.buttonColors(containerColor = Accent.clay, contentColor = MaterialTheme.colorScheme.background),
            ) {
                Icon(Icons.Outlined.AutoAwesome, null)
                Spacer(Modifier.width(Space.s))
                Text("Save")
            }
        }
    }
}

@Composable
private fun Section(title: String, body: String? = null, content: @Composable () -> Unit) {
    Slab(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        body?.let {
            Spacer(Modifier.heightIn(min = Space.xs))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.heightIn(min = Space.m))
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) { content() }
    }
}
