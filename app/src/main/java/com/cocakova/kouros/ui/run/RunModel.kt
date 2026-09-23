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
        val form: Form,
        val values: Map<String, JsonElement>,
        val issues: List<PromptValidator.Issue>,
        val objectInfo: ObjectInfo,
        /** Seed control per field key, starting from the workflow's own and changed by the user. */
        val controls: Map<String, String> = emptyMap(),
        /** What it makes and loads — context for the prompt assistant. */
        val traits: WorkflowTraits? = null,
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
        val form = withContext(Dispatchers.Default) { FormEngine(oi).build(template.compiled, template.workflow) }
        val traits = WorkflowTraits.of(template.prompt, oi)
        app.workflows.saveTraits(w, traits)
        val restored = restoreValues(w, form)
        val values = form.all.associate { it.key to (restored[it.key] ?: it.initial) }
        val controls = form.all.mapNotNull { f -> f.control?.let { f.key to it } }.toMap()
        return RunScreenState.Ready(w, template, form, values, validate(template, form, values, oi), oi, controls, traits)
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

    private suspend fun restoreValues(w: WorkflowEntity, form: Form): Map<String, JsonElement> {
        val source = remixRunId?.let { app.db.runs().get(it)?.valuesJson } ?: w.lastValues ?: return emptyMap()
        val saved = runCatching { json.parseToJsonElement(source) as JsonObject }.getOrNull() ?: return emptyMap()
        val keys = form.all.map { it.key }.toSet()
        return saved.filterKeys { it in keys }
    }

    private fun validate(t: RunTemplate, form: Form, values: Map<String, JsonElement>, oi: ObjectInfo): List<PromptValidator.Issue> =
        PromptValidator.validate(currentPrompt(t, form, values), oi).filter { it.kind != PromptValidator.Kind.MISSING_INPUT }

    private fun currentPrompt(t: RunTemplate, form: Form, values: Map<String, JsonElement>): JsonObject =
        FormEngine.apply(t.prompt, form.all.mapNotNull { f -> values[f.key]?.let { f to it } }.toMap())

    fun setControl(field: FormField, mode: String) = _state.update { st ->
        if (st !is RunScreenState.Ready) st else st.copy(controls = st.controls + (field.key to mode))
    }

    fun set(field: FormField, value: JsonElement) {
        _state.update { st ->
            if (st !is RunScreenState.Ready) st else {
                val v = st.values + (field.key to value)
                st.copy(values = v, issues = validate(st.template, st.form, v, st.objectInfo))
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
            app.db.workflows().saveValues(st.workflow.key, JsonObject(st.values).toString())
        }
    }

    override fun onCleared() {
        // Flush a pending draft (the screen's scope is already cancelled; the app's outlives it).
        if (draftPending) (_state.value as? RunScreenState.Ready)?.let { st ->
            app.appScope.launch { app.db.workflows().saveValues(st.workflow.key, JsonObject(st.values).toString()) }
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
        if (matching.isEmpty()) { _message.value = "This workflow has no ${shared.kind} input"; return }
        shared.uris.zip(matching).forEach { (uri, field) -> upload(field, uri) }
        if (shared.uris.size > matching.size) _message.value = "Used ${matching.size} of ${shared.uris.size} — the workflow has ${matching.size} inputs"
    }

    /** Uploads a picked file to the server's input folder and points the field at it. */
    fun upload(field: FormField, uri: Uri) = viewModelScope.launch {
        val s = session ?: return@launch
        _uploading.update { it + field.key }
        runCatching {
            val cr = app.contentResolver
            val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "upload"
            val mime = cr.getType(uri) ?: "application/octet-stream"
            val bytes = withContext(Dispatchers.IO) { cr.openInputStream(uri)!!.use { it.readBytes() } }
            val ref = s.client.upload(bytes, name, mime, subfolder = "kouros")
            set(field, JsonPrimitive(if (ref.subfolder.isNotEmpty()) "${ref.subfolder}/${ref.filename}" else ref.filename))
        }.onFailure { _message.value = "Upload failed: ${it.message}" }
        _uploading.update { it - field.key }
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
            val prompt = currentPrompt(st.template, st.form, values)
            val valuesJson = JsonObject(values).toString()
            val workflowJson = st.template.workflow?.raw
            when (val r = app.runs.submit(s, prompt, st.workflow.key, st.workflow.name, valuesJson, workflowJson)) {
                is RunCoordinator.Submitted.Ok -> {
                    _lastRun.value = r.promptId
                    app.db.workflows().saveValues(st.workflow.key, valuesJson)
                    values = advanceSeeds(st.form, values, (_state.value as? RunScreenState.Ready)?.controls ?: st.controls)
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
        app.db.workflows().saveValues(st.workflow.key, JsonObject(values).toString())
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

    fun cancel() = viewModelScope.launch { lastRun.value?.let { app.runs.cancel(it) } }

    fun consumeMessage() { _message.value = null }

    fun progressOf(map: Map<String, RunProgress>): RunProgress? = lastRun.value?.let { map[it] }
}
