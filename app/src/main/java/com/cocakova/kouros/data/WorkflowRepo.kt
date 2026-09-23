package com.cocakova.kouros.data

import com.cocakova.kouros.core.compile.RunTemplate
import com.cocakova.kouros.core.compile.Templates
import com.cocakova.kouros.core.form.WorkflowTraits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import com.cocakova.kouros.KourosApp
import com.cocakova.kouros.net.ServerSession

/** Saved workflows: the server's userdata store (shared with the desktop) plus local imports. */
class WorkflowRepo(private val app: KourosApp) {
    private val dao get() = app.db.workflows()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val traitsLock = Mutex()

    /** Refreshes the list from the server; contents are fetched lazily when opened. */
    suspend fun sync(session: ServerSession) {
        val files = session.client.listUserdata("workflows").filter { it.path.endsWith(".json", ignoreCase = true) }
        val existing = files.associate { key(session.server.id, SOURCE_USERDATA, it.path) to it }
        val rows = existing.map { (k, f) ->
            val old = dao.get(k)
            // Drop the cached body when the desktop saved a newer version (traits go stale with it).
            old?.copy(name = f.displayName, path = f.path, modified = f.modified, json = old.json?.takeIf { old.modified == f.modified })
                ?: WorkflowEntity(key = k, serverId = session.server.id, source = SOURCE_USERDATA, path = f.path, name = f.displayName, modified = f.modified)
        }
        dao.upsertAll(rows)
        dao.pruneUserdata(session.server.id, existing.keys.toList().ifEmpty { listOf("") })
        app.appScope.launch { refreshTraits(session) }
    }

    /**
     * Reads what each workflow makes (image, video, audio…) so the list can file it. Uses the
     * local compiler only — never the frontend oracle — so it is quick and quiet; a workflow
     * whose compile is unsure still has its output nodes, which is all this needs.
     */
    suspend fun refreshTraits(session: ServerSession) = traitsLock.withLock {
        val todo = dao.needingTraits(session.server.id)
        if (todo.isEmpty()) return@withLock
        val oi = runCatching { session.objectInfo() }.getOrNull() ?: return@withLock
        for (w in todo) {
            val text = runCatching { content(session, w.key) }.getOrNull() ?: continue
            val prompt = withContext(Dispatchers.Default) {
                runCatching { Templates.resolveLocal(json.parseToJsonElement(text) as JsonObject, oi).prompt }.getOrNull()
            } ?: continue
            saveTraits(w, WorkflowTraits.of(prompt, oi))
        }
    }

    suspend fun saveTraits(w: WorkflowEntity, t: WorkflowTraits) =
        dao.saveTraits(w.key, t.primary?.name ?: "OTHER", t.inputs.joinToString(","), t.models.joinToString("\n"), w.modified ?: 0.0)

    // Compiled templates of workflows opened this process, so reopening a workflow (say, mid-run)
    // is instant and never re-runs the frontend compile. Keyed by what they were compiled from.
    private val templates = ConcurrentHashMap<String, Pair<String, RunTemplate>>()

    fun cachedTemplate(key: String, stamp: String): RunTemplate? = templates[key]?.takeIf { it.first == stamp }?.second
    fun cacheTemplate(key: String, stamp: String, t: RunTemplate) { templates[key] = stamp to t }

    /** The workflow's JSON, downloading it if it is not cached (or is stale). */
    suspend fun content(session: ServerSession, key: String): String? {
        val w = dao.get(key) ?: return null
        w.json?.let { return it }
        if (w.source != SOURCE_USERDATA) return null
        val body = session.client.readUserdata("workflows/${w.path}")
        dao.upsert(w.copy(json = body))
        return body
    }

    suspend fun importLocal(serverId: String, name: String, json: String): String {
        val k = key(serverId, SOURCE_LOCAL, "${System.currentTimeMillis()}-$name")
        dao.upsert(WorkflowEntity(k, serverId, SOURCE_LOCAL, name, name.removeSuffix(".json"), json, lastOpenedAt = System.currentTimeMillis()))
        return k
    }

    companion object {
        const val SOURCE_USERDATA = "userdata"
        const val SOURCE_LOCAL = "local"
        fun key(serverId: String, source: String, path: String) = "$serverId|$source|$path"
    }
}
