package com.cocakova.kouros.data

import com.cocakova.kouros.KourosApp
import com.cocakova.kouros.net.ServerSession

/** Saved workflows: the server's userdata store (shared with the desktop) plus local imports. */
class WorkflowRepo(private val app: KourosApp) {
    private val dao get() = app.db.workflows()

    /** Refreshes the list from the server; contents are fetched lazily when opened. */
    suspend fun sync(session: ServerSession) {
        val files = session.client.listUserdata("workflows").filter { it.path.endsWith(".json", ignoreCase = true) }
        val existing = files.associate { key(session.server.id, SOURCE_USERDATA, it.path) to it }
        val rows = existing.map { (k, f) ->
            val old = dao.get(k)
            WorkflowEntity(
                key = k,
                serverId = session.server.id,
                source = SOURCE_USERDATA,
                path = f.path,
                name = f.displayName,
                // Drop the cached body when the desktop saved a newer version.
                json = old?.json?.takeIf { old.modified == f.modified },
                modified = f.modified,
                lastOpenedAt = old?.lastOpenedAt,
                pinned = old?.pinned ?: false,
                formConfig = old?.formConfig,
                lastValues = old?.lastValues,
            )
        }
        dao.upsertAll(rows)
        dao.pruneUserdata(session.server.id, existing.keys.toList().ifEmpty { listOf("") })
    }

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
