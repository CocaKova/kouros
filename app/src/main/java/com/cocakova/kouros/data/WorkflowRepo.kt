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

    /**
     * Renames and/or moves a workflow. [newPath] is relative to the workflows folder
     * ("Portraits/Studio.json"). Server workflows move on the server, so the desktop sees it;
     * the phone's own copies just change here. Returns the new key.
     */
    suspend fun move(session: ServerSession?, w: WorkflowEntity, newPath: String): String {
        val path = newPath.trim().trim('/').let { if (it.endsWith(".json", ignoreCase = true)) it else "$it.json" }
        require(path.removeSuffix(".json").isNotBlank()) { "Give it a name" }
        if (path == w.path) return w.key
        val name = path.substringAfterLast('/').removeSuffix(".json")
        val newKey = key(w.serverId, w.source, path)
        if (w.source == SOURCE_USERDATA) {
            val s = session ?: error("Not connected")
            s.client.moveUserdata("workflows/${w.path}", "workflows/$path", overwrite = false)
        }
        dao.delete(w.key)
        dao.upsert(w.copy(key = newKey, path = path, name = name))
        app.db.runs().rekey(w.key, newKey, name)
        return newKey
    }

    /** A copy next to the original ("Name copy.json", numbered if taken). Returns the new key. */
    suspend fun duplicate(session: ServerSession?, w: WorkflowEntity): String {
        val body = (if (session != null) content(session, w.key) else w.json) ?: error("Couldn't read the workflow")
        val folder = w.path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val base = w.path.substringAfterLast('/').removeSuffix(".json")
        val taken = dao.forServerOnce(w.serverId).map { it.path }.toSet()
        val path = generateSequence(1) { it + 1 }.map { n -> "$folder$base copy${if (n == 1) "" else " $n"}.json" }.first { it !in taken }
        val newKey = key(w.serverId, w.source, path)
        if (w.source == SOURCE_USERDATA) {
            val s = session ?: error("Not connected")
            s.client.writeUserdata("workflows/$path", body, overwrite = false)
        }
        dao.upsert(WorkflowEntity(newKey, w.serverId, w.source, path, path.substringAfterLast('/').removeSuffix(".json"), body, modified = w.modified, kind = w.kind, inputs = w.inputs, models = w.models, traitsFor = w.traitsFor, formConfig = w.formConfig))
        return newKey
    }

    /** Deletes a workflow from the server (and so from the desktop), or the phone's own copy. */
    suspend fun delete(session: ServerSession?, w: WorkflowEntity) {
        if (w.source == SOURCE_USERDATA) {
            val s = session ?: error("Not connected")
            s.client.deleteUserdata("workflows/${w.path}")
        }
        dao.delete(w.key)
    }

    /**
     * Saves one of the server's templates into its workflows (so the desktop has it too), under
     * the template's title — numbered if the name is taken. Returns the new workflow's key.
     */
    suspend fun addFromTemplate(session: ServerSession, t: com.cocakova.kouros.core.api.TemplateEntry): String {
        val body = session.client.templateWorkflow(t)
        val base = t.title.replace(Regex("""[\\/:*?"<>|]+"""), " ").replace(Regex("""\s+"""), " ").trim().ifEmpty { t.name }
        val taken = dao.forServerOnce(session.server.id).map { it.path.lowercase() }.toSet()
        val path = generateSequence(1) { it + 1 }.map { n -> if (n == 1) "$base.json" else "$base $n.json" }.first { it.lowercase() !in taken }
        session.client.writeUserdata("workflows/$path", body, overwrite = false)
        val key = key(session.server.id, SOURCE_USERDATA, path)
        dao.upsert(WorkflowEntity(key, session.server.id, SOURCE_USERDATA, path, path.removeSuffix(".json"), body, lastOpenedAt = System.currentTimeMillis()))
        app.appScope.launch { runCatching { sync(session) } }
        return key
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
