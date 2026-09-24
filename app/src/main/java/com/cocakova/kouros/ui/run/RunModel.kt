package com.cocakova.kouros.ui.run

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cocakova.kouros.app
import com.cocakova.kouros.compile.FrontendOracle
import com.cocakova.kouros.core.api.ObjectInfo
import com.cocakova.kouros.core.compile.PromptValidator
import com.cocakova.kouros.core.compile.RunTemplate
import com.cocakova.kouros.core.compile.TemplateSource
import com.cocakova.kouros.core.compile.Templates
import com.cocakova.kouros.core.form.Form
import com.cocakova.kouros.core.form.FormEngine
import com.cocakova.kouros.core.form.FormField
import com.cocakova.kouros.core.graph.WorkflowFormat
import com.cocakova.kouros.core.run.RunProgress
import com.cocakova.kouros.data.WorkflowEntity
import com.cocakova.kouros.net.ServerSession
import com.cocakova.kouros.run.RunCoordinator
import com.cocakova.kouros.core.form.WorkflowTraits
import com.cocakova.kouros.core.form.References
import com.cocakova.kouros.core.form.FormLayout
import com.cocakova.kouros.core.form.ModelNeed
import com.cocakova.kouros.core.form.ModelNeeds
import com.cocakova.kouros.core.api.ModelDownload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface RunScreenState {
    data object Loading : RunScreenState
    data class Failed(val message: String) : RunScreenState
    data class Ready(
        val workflow: WorkflowEntity,
        val template: RunTemplate,
        /** The form as the person arranged it ([layout] over [baseForm]). */
        val form: Form,
        val values: Map<String, JsonElement>,
        val issues: List<PromptValidator.Issue>,
        val objectInfo: ObjectInfo,
        /** Seed control per field key, starting from the workflow's own and changed by the user. */
        val controls: Map<String, String> = emptyMap(),
        /** What it makes and loads — context for the prompt assistant. */
        val traits: WorkflowTraits? = null,
        /** Reference-image slots the workflow leaves free, and the photos added to them (server input paths). */
        val references: References = References(emptyList()),
        val refs: List<String> = emptyList(),
        /** The form as the engine chose it; hidden fields still run with their values. */
        val baseForm: Form = form,
        val layout: FormLayout = FormLayout(),
        /** Model files the workflow asks for that the server doesn't have. */
        val missing: List<ModelNeed> = emptyList(),
        val modelLinks: Map<String, ModelNeeds.Link> = emptyMap(),
        /** The server's bridge can fetch missing models itself. */
        val canDownload: Boolean = false,
        /** The app this workflow is, when it came from the app catalog. */
        val appSpec: com.cocakova.kouros.core.apps.AppSpec? = null,
    ) : RunScreenState
}

class RunModel(private val workflowKey: String, private val remixRunId: String?) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val _state = MutableStateFlow<RunScreenState>(RunScreenState.Loading)
    val state: StateFlow<RunScreenState> = _state.asStateFlow()

    /** The most recent run started from this screen. */
    private val _lastRun = MutableStateFlow<String?>(null)

    /**
     * The run this screen follows: the workflow's newest unfinished run from the database — so
     * leaving and coming back mid-run (or opening from the notification) picks it up again —
     * else the last one started here.
     */
    val lastRun: StateFlow<String?> = combine(_lastRun, app.db.runs().activeFor(workflowKey)) { local, active -> active?.promptId ?: local }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _uploading = MutableStateFlow<Set<String>>(emptySet())
    val uploading: StateFlow<Set<String>> = _uploading.asStateFlow()

    var session: ServerSession? = null
        private set

    init { load(false) }

    fun load(refresh: Boolean) = viewModelScope.launch {
        _state.value = RunScreenState.Loading
        _state.value = runCatching { resolve(refresh) }.getOrElse { e -> RunScreenState.Failed(e.message ?: "Couldn't open this workflow") }
        (_state.value as? RunScreenState.Ready)?.let { receiveShared(it.form) }
    }

    private suspend fun resolve(refresh: Boolean): RunScreenState {
        val w = app.db.workflows().get(workflowKey) ?: return RunScreenState.Failed("This workflow is gone")
        val s = app.sessions.byId(w.serverId) ?: return RunScreenState.Failed("Its server was removed")
        session = s
        app.db.workflows().opened(w.key, System.currentTimeMillis())
        if (refresh && w.source == "userdata") app.db.workflows().upsert(w.copy(json = null))
        val text = app.workflows.content(s, w.key) ?: return RunScreenState.Failed("Couldn't read the workflow")
        val oi = s.objectInfo(refresh)
        val stamp = "${w.modified}|${s.objectInfoHash}|${text.hashCode()}"
        val template = app.workflows.cachedTemplate(w.key, stamp).takeUnless { refresh }
            ?: compileTemplate(s, text, oi).also { app.workflows.cacheTemplate(w.key, stamp, it) }
        val baseForm = withContext(Dispatchers.Default) { FormEngine(oi).build(template.compiled, template.workflow) }
        val layout = FormLayout.decode(w.formConfig)
        val form = layout.apply(baseForm)
        val traits = WorkflowTraits.of(template.prompt, oi)
        app.workflows.saveTraits(w, traits)
        val saved = savedValues(w)
        val keys = baseForm.all.map { it.key }.toSet()
        val restored = saved.filterKeys { it in keys }
        val values = baseForm.all.associate { f -> f.key to FormEngine.inRange(restored[f.key] ?: f.initial, f.spec) }
        val controls = baseForm.all.mapNotNull { f -> f.control?.let { f.key to it } }.toMap()
        val references = References.of(template.prompt, oi)
        val refs = (saved[REFS] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().take(references.capacity)
        val appSpec = app.apps.specFor(w)
        val links = template.workflow?.raw?.let(ModelNeeds::links).orEmpty() + appSpec?.modelLinks().orEmpty()
        val canDownload = runCatching { s.client.bridge()?.canDownloadModels == true }.getOrDefault(false)
        val base = RunScreenState.Ready(
            w, template, form, values, emptyList(), oi, controls, traits, references, refs,
            baseForm = baseForm, layout = layout, modelLinks = links, canDownload = canDownload, appSpec = appSpec,
        )
        return withIssues(base)
    }

    /** [st] with its issues and missing models worked out for its current values. */
    private fun withIssues(st: RunScreenState.Ready, values: Map<String, JsonElement> = st.values): RunScreenState.Ready {
        val issues = validate(st, values)
        return st.copy(values = values, issues = issues, missing = ModelNeeds.missing(issues, st.modelLinks))
    }

    private suspend fun compileTemplate(s: ServerSession, text: String, oi: ObjectInfo): RunTemplate {
        val file = withContext(Dispatchers.Default) { json.parseToJsonElement(text) as JsonObject }
        var template = withContext(Dispatchers.Default) { Templates.resolveLocal(file, oi) }

        if (template.source == TemplateSource.COMPILED_UNSURE && template.workflow != null) {
            // 1. The server's own frontend, exactly as the desktop would compile it.
            FrontendOracle(app).compile(s, text)?.let { p ->
                template = RunTemplate(Templates.withPrompt(template.compiled, p), TemplateSource.FRONTEND, template.workflow)
            } ?: run {
                // 2. The prompt this workflow last ran with on the server.
                val id = template.workflow?.id
                val h = id?.let { wid -> runCatching { s.client.history(200) }.getOrNull()?.firstOrNull { it.workflow?.get("id")?.let { v -> (v as? JsonPrimitive)?.content } == wid } }
                h?.prompt?.let { p -> template = RunTemplate(Templates.withPrompt(template.compiled, p), TemplateSource.HISTORY, template.workflow) }
            }
        }
        return template
    }

    /** The remix's or the workflow's remembered values: field values plus [REFS]. */
    private suspend fun savedValues(w: WorkflowEntity): JsonObject {
        val source = remixRunId?.let { app.db.runs().get(it)?.valuesJson } ?: w.lastValues ?: return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(source) as JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    }

    private fun validate(st: RunScreenState.Ready, values: Map<String, JsonElement> = st.values): List<PromptValidator.Issue> =
        PromptValidator.validate(currentPrompt(st, values), st.objectInfo).filter { it.kind != PromptValidator.Kind.MISSING_INPUT }

    /** The prompt to queue: the form's values applied, then the added reference photos wired in. */
    private fun currentPrompt(st: RunScreenState.Ready, values: Map<String, JsonElement> = st.values): JsonObject {
        val applied = FormEngine.apply(st.template.prompt, st.baseForm.all.mapNotNull { f -> values[f.key]?.let { f to it } }.toMap())
        val loader = References.loaderClass(st.objectInfo) ?: return applied
        return st.references.inject(applied, st.refs, loader)
    }

    /** What is remembered for this workflow and stored with each run: field values plus the references. */
    private fun snapshot(st: RunScreenState.Ready, values: Map<String, JsonElement> = st.values): String =
        JsonObject(values + (REFS to JsonArray(st.refs.map { JsonPrimitive(it) }))).toString()

    fun setControl(field: FormField, mode: String) = _state.update { st ->
        if (st !is RunScreenState.Ready) st else st.copy(controls = st.controls + (field.key to mode))
    }

    fun set(field: FormField, value: JsonElement) {
        _state.update { st ->
            if (st !is RunScreenState.Ready) st else {
                withIssues(st, st.values + (field.key to value))
            }
        }
        saveDraft()
    }

    // Edits survive leaving the screen: the form's values are written back (debounced) as the
    // workflow's remembered values, the same slot a run writes.
    private var draftJob: Job? = null
    @Volatile private var draftPending = false
    private fun saveDraft() {
        draftPending = true
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(400)
            val st = _state.value as? RunScreenState.Ready ?: return@launch
            draftPending = false
            app.db.workflows().saveValues(st.workflow.key, snapshot(st))
        }
    }

    override fun onCleared() {
        // Flush a pending draft (the screen's scope is already cancelled; the app's outlives it).
        if (draftPending) (_state.value as? RunScreenState.Ready)?.let { st ->
            app.appScope.launch { app.db.workflows().saveValues(st.workflow.key, snapshot(st)) }
        }
    }

    /**
     * Media shared into the app lands in this workflow's media inputs, in order — images into
     * image loaders first, then any loader, so a single shared photo fills the obvious slot.
     */
    private fun receiveShared(form: Form) {
        val shared = com.cocakova.kouros.ui.ShareHandoff.media ?: return
        com.cocakova.kouros.ui.ShareHandoff.media = null
        val media = form.all.filter { it.role == com.cocakova.kouros.core.form.FieldRole.MEDIA }
        val matching = media.filter { (it.spec.uploadKind ?: "image") == shared.kind } .ifEmpty { media }
        val refRoom = if (shared.kind == "image") (_state.value as? RunScreenState.Ready)?.references?.capacity ?: 0 else 0
        if (matching.isEmpty() && refRoom == 0) { _message.value = "This workflow has no ${shared.kind} input"; return }
        shared.uris.zip(matching).forEach { (uri, field) -> upload(field, uri) }
        // Photos beyond the workflow's own inputs become references, where it takes them.
        val extra = shared.uris.drop(matching.size).take(refRoom)
        extra.forEach { addReference(it) }
        val used = minOf(shared.uris.size, matching.size) + extra.size
        if (shared.uris.size > used) _message.value = "Used $used of ${shared.uris.size} — the workflow has room for $used"
    }

    /** Uploads a picked file to the server's input folder and points the field at it. */
    fun upload(field: FormField, uri: Uri) = viewModelScope.launch {
        _uploading.update { it + field.key }
        uploadToServer(uri)?.let { set(field, JsonPrimitive(it)) }
        _uploading.update { it - field.key }
    }

    /** Adds a photo as the next reference image. */
    /** A reference photo the server already has — its own result, by annotated name. */
    fun addServerReference(path: String) {
        _state.update { st ->
            if (st !is RunScreenState.Ready || st.refs.size >= st.references.capacity) st
            else withIssues(st.copy(refs = st.refs + path))
        }
        saveDraft()
    }

    fun addReference(uri: Uri) = viewModelScope.launch {
        val slot = "ref:${uri}"
        _uploading.update { it + slot }
        uploadToServer(uri)?.let { path ->
            _state.update { st ->
                if (st !is RunScreenState.Ready || st.refs.size >= st.references.capacity) st
                else withIssues(st.copy(refs = st.refs + path))
            }
            saveDraft()
        }
        _uploading.update { it - slot }
    }

    /** Appends [token] to the main prompt (the first positive prompt field). */
    fun insertIntoPrompt(token: String) {
        val st = _state.value as? RunScreenState.Ready ?: return
        val f = st.form.all.firstOrNull { it.role == com.cocakova.kouros.core.form.FieldRole.PROMPT } ?: return
        val cur = (st.values[f.key] as? JsonPrimitive)?.contentOrNull ?: ""
        set(f, JsonPrimitive(if (cur.isBlank()) token else cur.trimEnd() + " " + token))
    }

    fun removeReference(index: Int) {
        _state.update { st ->
            if (st !is RunScreenState.Ready) st else withIssues(st.copy(refs = st.refs.filterIndexed { i, _ -> i != index }))
        }
        saveDraft()
    }

    /** Streams a file from the phone into the server's input folder; returns its "sub/name" path. */
    private suspend fun uploadToServer(uri: Uri): String? {
        val s = session ?: return null
        return runCatching {
            val cr = app.contentResolver
            val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "upload"
            val mime = cr.getType(uri) ?: "application/octet-stream"
            val bytes = withContext(Dispatchers.IO) { cr.openInputStream(uri)!!.use { it.readBytes() } }
            val ref = s.client.upload(bytes, name, mime, subfolder = "kouros")
            if (ref.subfolder.isNotEmpty()) "${ref.subfolder}/${ref.filename}" else ref.filename
        }.onFailure { _message.value = "Upload failed: ${it.message}" }.getOrNull()
    }

    /**
     * Queues [count] runs. Seeds move on after each queue, as the desktop's
     * "control after generate" does, so a batch is [count] different results.
     */
    fun run(count: Int) = viewModelScope.launch {
        val st = _state.value as? RunScreenState.Ready ?: return@launch
        val s = session ?: return@launch
        _busy.value = true
        var values = st.values
        repeat(count) {
            val prompt = currentPrompt(st, values)
            val valuesJson = snapshot(st, values)
            val workflowJson = st.template.workflow?.raw
            when (val r = app.runs.submit(s, prompt, st.workflow.key, st.workflow.name, valuesJson, workflowJson)) {
                is RunCoordinator.Submitted.Ok -> {
                    _lastRun.value = r.promptId
                    app.db.workflows().saveValues(st.workflow.key, valuesJson)
                    values = advanceSeeds(st.baseForm, values, (_state.value as? RunScreenState.Ready)?.controls ?: st.controls)
                }
                is RunCoordinator.Submitted.Rejected -> {
                    _message.value = r.message + describeNodeErrors(r.nodeErrors, st)
                    _busy.value = false
                    _state.value = st.copy(values = values)
                    return@launch
                }
                is RunCoordinator.Submitted.Failed -> {
                    _message.value = r.message
                    _busy.value = false
                    _state.value = st.copy(values = values)
                    return@launch
                }
            }
        }
        (_state.value as? RunScreenState.Ready)?.let { cur -> _state.value = cur.copy(values = values) }
        // Remember the advanced seeds too, so reopening continues rather than repeats.
        app.db.workflows().saveValues(st.workflow.key, snapshot(st, values))
        _busy.value = false
    }

    private fun advanceSeeds(form: Form, values: Map<String, JsonElement>, controls: Map<String, String>): Map<String, JsonElement> =
        values.mapValues { (k, v) ->
            val f = form.all.firstOrNull { it.key == k } ?: return@mapValues v
            val c = controls[k] ?: f.control ?: return@mapValues v
            FormEngine.nextSeed(v, c, f.spec)
        }

    private fun describeNodeErrors(errors: JsonObject, st: RunScreenState.Ready): String {
        val first = errors.entries.firstOrNull() ?: return ""
        val node = first.value as? JsonObject
        val cls = (node?.get("class_type") as? JsonPrimitive)?.content ?: first.key
        val msg = ((node?.get("errors") as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject)
            ?.let { e -> listOfNotNull((e["message"] as? JsonPrimitive)?.content, (e["details"] as? JsonPrimitive)?.content).joinToString(": ") }
        return "\n$cls" + (msg?.let { " — $it" } ?: "")
    }

    // ── the person's layout and presets ─────────────────────────────────────────

    /** Changes the layout with [edit] (given the layout and the form as shown), and saves it. */
    fun editLayout(edit: (FormLayout, Form) -> FormLayout) {
        val st = _state.value as? RunScreenState.Ready ?: return
        val layout = edit(st.layout, st.form)
        _state.value = st.copy(layout = layout, form = layout.apply(st.baseForm))
        viewModelScope.launch { app.db.workflows().saveConfig(st.workflow.key, layout.encode()) }
    }

    fun savePreset(name: String) {
        val st = _state.value as? RunScreenState.Ready ?: return
        if (name.isBlank()) return
        val values = JsonObject(st.values + (REFS to JsonArray(st.refs.map { JsonPrimitive(it) })))
        editLayout { l, _ -> l.savePreset(name, values) }
        _message.value = "Saved \"${name.trim()}\""
    }

    fun deletePreset(name: String) = editLayout { l, _ -> l.deletePreset(name) }

    /** Loads a preset's values (fields the workflow no longer has are skipped). */
    fun applyPreset(name: String) {
        val preset = (_state.value as? RunScreenState.Ready)?.layout?.presets?.get(name) ?: return
        _state.update { st ->
            if (st !is RunScreenState.Ready) st else {
                val keys = st.baseForm.all.map { it.key }.toSet()
                val refs = (preset[REFS] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.take(st.references.capacity) ?: st.refs
                withIssues(st.copy(refs = refs), st.values + preset.filterKeys { it in keys })
            }
        }
        saveDraft()
        _message.value = "Loaded \"$name\""
    }

    // ── missing models ──────────────────────────────────────────────────────────

    private val _downloads = MutableStateFlow<List<ModelDownload>>(emptyList())
    val downloads: StateFlow<List<ModelDownload>> = _downloads.asStateFlow()
    private var watching: Job? = null

    /** Asks the server's bridge to fetch [need], then follows it until every download settles. */
    fun download(need: ModelNeed) = viewModelScope.launch {
        val s = session ?: return@launch
        val url = need.url ?: return@launch
        val dir = need.directory ?: return@launch
        runCatching { s.client.downloadModel(url, dir, need.name.substringAfterLast('/')) }
            .onFailure { _message.value = "Couldn't start the download: ${it.message}"; return@launch }
        watchDownloads()
    }

    private fun watchDownloads() {
        if (watching?.isActive == true) return
        watching = viewModelScope.launch {
            val s = session ?: return@launch
            while (true) {
                val list = runCatching { s.client.modelDownloads() }.getOrNull() ?: break
                _downloads.value = list
                if (list.none { it.running }) break
                delay(1500)
            }
            // Whatever finished is now a choice the server offers: check again.
            val st = _state.value as? RunScreenState.Ready ?: return@launch
            if (_downloads.value.any { d -> d.state == "done" && st.missing.any { it.name.substringAfterLast('/') == d.name } }) {
                val oi = s.objectInfo(true)
                _state.update { cur -> if (cur is RunScreenState.Ready) withIssues(cur.copy(objectInfo = oi)) else cur }
            }
            _downloads.value.firstOrNull { it.state == "failed" && it.error != "cancelled" }?.let { _message.value = "${it.name}: ${it.error}" }
        }
    }

    fun cancel() = viewModelScope.launch { lastRun.value?.let { app.runs.cancel(it) } }

    fun consumeMessage() { _message.value = null }

    fun progressOf(map: Map<String, RunProgress>): RunProgress? = lastRun.value?.let { map[it] }

    companion object {
        /** Key under which the reference photos travel with the remembered values. */
        const val REFS = "__refs"
    }
}
