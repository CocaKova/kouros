package com.cocakova.pygmalion.run

import android.content.Intent
import androidx.core.content.ContextCompat
import com.cocakova.pygmalion.PygmalionApp
import com.cocakova.pygmalion.core.api.ComfyHttpException
import com.cocakova.pygmalion.core.api.MediaKind
import com.cocakova.pygmalion.core.api.OutputItem
import com.cocakova.pygmalion.core.api.SubmitResult
import com.cocakova.pygmalion.core.run.RunPhase
import com.cocakova.pygmalion.core.run.RunProgress
import com.cocakova.pygmalion.core.run.RunTracker
import com.cocakova.pygmalion.core.ws.BinaryFrame
import com.cocakova.pygmalion.data.RunEntity
import com.cocakova.pygmalion.data.RunState
import com.cocakova.pygmalion.net.ServerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** A live preview frame for one run, as encoded bytes (decoding happens where it is drawn). */
class PreviewFrame(val promptId: String, val mime: String, val bytes: ByteArray, val atMs: Long)

/**
 * Owns every run the phone started: submits them, follows them, and records how they ended.
 *
 * Durability comes from the order of operations: the run is written to the database with a
 * prompt id we chose *before* the request goes out, so a crash, a lost response or a dead socket
 * never produces a run the app forgot or a duplicate it sent twice. Progress arrives from the
 * socket; truth arrives from REST reconciliation on every reconnect.
 */
class RunCoordinator(private val app: PygmalionApp) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val trackers = java.util.concurrent.ConcurrentHashMap<String, RunTracker>()
    private val serverOf = java.util.concurrent.ConcurrentHashMap<String, String>() // promptId → serverId
    private val attached = HashMap<String, Job>() // serverId → event collector
    private val mutex = Mutex()

    private val _progress = MutableStateFlow<Map<String, RunProgress>>(emptyMap())
    /** Live progress of every active run, by prompt id. */
    val progress: StateFlow<Map<String, RunProgress>> = _progress.asStateFlow()

    private val _preview = MutableSharedFlow<PreviewFrame>(replay = 1, extraBufferCapacity = 2)
    val preview: SharedFlow<PreviewFrame> = _preview.asSharedFlow()

    sealed interface Submitted {
        data class Ok(val promptId: String) : Submitted
        data class Rejected(val message: String, val details: String?, val nodeErrors: JsonObject) : Submitted
        data class Failed(val message: String) : Submitted
    }

    suspend fun submit(
        session: ServerSession,
        prompt: JsonObject,
        workflowKey: String?,
        workflowName: String,
        valuesJson: String?,
        workflowForHistory: JsonObject?,
    ): Submitted {
        val promptId = UUID.randomUUID().toString()
        val entity = RunEntity(
            promptId = promptId,
            serverId = session.server.id,
            workflowKey = workflowKey,
            workflowName = workflowName,
            promptJson = prompt.toString(),
            valuesJson = valuesJson,
            state = RunState.SUBMITTING,
        )
        app.db.runs().insert(entity)
        val objectInfo = runCatching { session.objectInfo() }.getOrNull()
        val outputClasses = objectInfo?.nodes?.filterValues { it.isOutputNode }?.keys ?: emptySet()
        mutex.withLock { trackers[promptId] = RunTracker(promptId, prompt, outputClasses) }
        serverOf[promptId] = session.server.id
        publish(promptId, RunProgress(promptId, RunPhase.SUBMITTING))
        attach(session)
        RunService.start(app)

        // Attach the UI workflow the way the desktop does, so the server's history (and the
        // metadata embedded in saved images) can reopen it in the desktop editor.
        val extra = workflowForHistory?.let { buildJsonObject { put("extra_pnginfo", buildJsonObject { put("workflow", it) }) } }
        return try {
            when (val r = session.client.submit(prompt, session.server.clientId, promptId, extra)) {
                is SubmitResult.Accepted -> {
                    app.db.runs().update(entity.copy(state = RunState.QUEUED))
                    trackers[promptId]?.let { publish(promptId, it.state) }
                    Submitted.Ok(promptId)
                }
                is SubmitResult.Rejected -> {
                    app.db.runs().update(entity.copy(state = RunState.REJECTED, error = r.value.message, finishedAt = now()))
                    drop(promptId)
                    Submitted.Rejected(r.value.message, r.value.details, r.value.nodeErrors)
                }
            }
        } catch (e: Exception) {
            // The request may or may not have reached the server. Keep the run: reconciliation
            // will find it in the queue/history, or mark it lost.
            if (e is ComfyHttpException) {
                app.db.runs().update(entity.copy(state = RunState.REJECTED, error = e.message, finishedAt = now()))
                drop(promptId)
            } else {
                app.appScope.launch { reconcile(session) }
            }
            Submitted.Failed(e.message ?: "Could not reach the server")
        }
    }

    suspend fun cancel(promptId: String) {
        val run = app.db.runs().get(promptId) ?: return
        val session = app.sessions.byId(run.serverId) ?: return
        val phase = _progress.value[promptId]?.phase
        runCatching {
            if (phase == RunPhase.RUNNING) session.client.interrupt(promptId)
            else session.client.deleteQueued(listOf(promptId))
        }
        if (phase != RunPhase.RUNNING) {
            app.db.runs().update(run.copy(state = RunState.INTERRUPTED, finishedAt = now()))
            drop(promptId)
        }
    }

    /**
     * Queues a finished run again, advancing every seed-like input the way the desktop would
     * (per the node's own control-after-generate). Returns a message for the user, or null.
     */
    suspend fun rerun(promptId: String): String? {
        val run = app.db.runs().get(promptId) ?: return "That run is gone"
        val session = app.sessions.byId(run.serverId) ?: return "Server removed"
        val prompt = runCatching { json.parseToJsonElement(run.promptJson).jsonObject }.getOrNull() ?: return "Unreadable run"
        val oi = runCatching { session.objectInfo() }.getOrNull()
        val next = Reseed.advance(prompt, oi)
        return when (val r = submit(session, next, run.workflowKey, run.workflowName, run.valuesJson, null)) {
            is Submitted.Ok -> null
            is Submitted.Rejected -> r.message
            is Submitted.Failed -> r.message
        }
    }

    /** After a process death: re-open trackers for runs the database still calls active. */
    fun resumeAfterProcessDeath() = app.appScope.launch {
        val active = app.db.runs().activeList()
        if (active.isEmpty()) return@launch
        for (run in active) {
            val session = app.sessions.byId(run.serverId) ?: continue
            val prompt = runCatching { json.parseToJsonElement(run.promptJson).jsonObject }.getOrNull() ?: continue
            val outputClasses = runCatching { session.objectInfo() }.getOrNull()?.nodes?.filterValues { it.isOutputNode }?.keys ?: emptySet()
            mutex.withLock { trackers.getOrPut(run.promptId) { RunTracker(run.promptId, prompt, outputClasses) } }
            serverOf[run.promptId] = run.serverId
            attach(session)
        }
        RunService.start(app)
    }

    val hasActive: Boolean get() = trackers.isNotEmpty()

    private fun attach(session: ServerSession): Unit = synchronized(attached) {
        if (attached[session.server.id]?.isActive == true) return
        session.acquire()
        attached[session.server.id] = app.appScope.launch {
            launch { session.reconnected.collect { reconcile(session) } }
            launch {
                session.previews.collect { f ->
                    val id = f.promptId ?: mutex.withLock { trackers.values.firstOrNull { it.state.phase == RunPhase.RUNNING }?.promptId } ?: return@collect
                    _preview.emit(PreviewFrame(id, f.mime, f.imageBytes(), now()))
                }
            }
            session.events.collect { e ->
                val changed = mutex.withLock { trackers.values.mapNotNull { t -> t.onEvent(e, now()) } }
                for (p in changed) {
                    publish(p.promptId, p)
                    if (p.phase.isTerminal) finish(p)
                    else if (p.phase == RunPhase.RUNNING) markRunning(p)
                }
            }
        }
        // A reconnect may already have happened before we subscribed.
        app.appScope.launch { reconcile(session) }
    }

    private fun detachIfIdle(serverId: String) {
        val stillUsed = trackers.keys.any { id -> serverOf[id] == serverId }
        if (stillUsed) return
        synchronized(attached) {
            attached.remove(serverId)?.cancel()
        }
        app.appScope.launch { app.sessions.byId(serverId)?.release() }
    }

    /**
     * The server's REST view is the truth: every active run of this server is either still
     * queued (fine), in history (finished — record the outcome), or neither (lost).
     */
    suspend fun reconcile(session: ServerSession) {
        val runs = app.db.runs().activeList().filter { it.serverId == session.server.id }
        if (runs.isEmpty()) return
        val queue = runCatching { session.client.queue() }.getOrNull() ?: return
        val pendingOrder = queue.pending.sortedBy { it.number ?: 0.0 }.map { it.promptId }
        for (run in runs) {
            val t = mutex.withLock { trackers[run.promptId] } ?: continue
            val running = queue.running.any { it.promptId == run.promptId }
            val pendingIdx = pendingOrder.indexOf(run.promptId)
            when {
                running -> publish(run.promptId, t.queued(true, 0))
                pendingIdx >= 0 -> publish(run.promptId, t.queued(false, pendingIdx + queue.running.size))
                else -> {
                    val h = runCatching { session.client.historyFor(run.promptId) }.getOrNull()
                    if (h != null) finish(t.fromHistory(h))
                    else if (run.state != RunState.SUBMITTING || now() - run.createdAt > 30_000) finish(t.lost())
                }
            }
        }
    }

    private suspend fun markRunning(p: RunProgress) {
        val run = app.db.runs().get(p.promptId) ?: return
        if (run.state != RunState.RUNNING) app.db.runs().update(run.copy(state = RunState.RUNNING, startedAt = p.startedAtMs ?: now()))
    }

    private suspend fun finish(p: RunProgress) {
        val run = app.db.runs().get(p.promptId) ?: return
        if (run.state in setOf(RunState.SUCCEEDED, RunState.FAILED, RunState.INTERRUPTED, RunState.LOST)) { drop(p.promptId); return }
        val state = when (p.phase) {
            RunPhase.SUCCEEDED -> RunState.SUCCEEDED
            RunPhase.INTERRUPTED -> RunState.INTERRUPTED
            RunPhase.LOST -> RunState.LOST
            else -> RunState.FAILED
        }
        // Outputs: the socket's list can miss nodes that ran while we were away; history can't.
        val outputs = if (state == RunState.SUCCEEDED && p.outputs.isEmpty()) {
            app.sessions.byId(run.serverId)?.let { s -> runCatching { s.client.historyFor(run.promptId) }.getOrNull() }
                ?.let { h -> mutex.withLock { trackers[run.promptId] }?.fromHistory(h)?.outputs } ?: p.outputs
        } else p.outputs
        val updated = run.copy(
            state = state,
            startedAt = run.startedAt ?: p.startedAtMs,
            finishedAt = p.finishedAtMs ?: now(),
            error = p.error?.let { e -> listOfNotNull(e.nodeType, e.message).joinToString(": ") } ?: if (state == RunState.LOST) "The server no longer knows this run" else null,
            outputsJson = encodeOutputs(outputs),
        )
        app.db.runs().update(updated)
        publish(p.promptId, p.copy(outputs = outputs))
        app.notifier.finished(updated, outputs)
        drop(p.promptId)
    }

    private suspend fun drop(promptId: String) {
        val serverId = serverOf.remove(promptId)
        mutex.withLock { trackers.remove(promptId) }
        _progress.update { it - promptId }
        if (serverId != null) detachIfIdle(serverId)
        if (!hasActive) RunService.stop(app)
    }

    private fun publish(promptId: String, p: RunProgress) {
        _progress.update { it + (promptId to p) }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        fun encodeOutputs(items: List<OutputItem>): String = buildJsonArray {
            for (o in items) add(
                buildJsonObject {
                    put("node", o.nodeId); put("kind", o.kind.name)
                    o.file?.let { put("filename", it.filename); put("subfolder", it.subfolder); put("type", it.type) }
                    o.text?.let { put("text", it) }
                },
            )
        }.toString()

        fun decodeOutputs(s: String?): List<OutputItem> {
            if (s.isNullOrBlank()) return emptyList()
            val arr = runCatching { Json.parseToJsonElement(s) as JsonArray }.getOrNull() ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                OutputItem(
                    nodeId = str("node") ?: "",
                    kind = runCatching { MediaKind.valueOf(str("kind") ?: "FILE") }.getOrDefault(MediaKind.FILE),
                    file = str("filename")?.let { com.cocakova.pygmalion.core.api.FileRef(it, str("subfolder") ?: "", str("type") ?: "output") },
                    text = str("text"),
                )
            }
        }
    }
}
