package com.cocakova.pygmalion.net

import com.cocakova.pygmalion.PygmalionApp
import com.cocakova.pygmalion.data.Secrets
import com.cocakova.pygmalion.data.ServerEntity
import java.util.concurrent.ConcurrentHashMap

/** One [ServerSession] per configured server, rebuilt when its settings change. */
class Sessions(private val app: PygmalionApp) {
    private val live = ConcurrentHashMap<String, Pair<ServerEntity, ServerSession>>()

    fun get(server: ServerEntity): ServerSession {
        live[server.id]?.let { (cfg, s) -> if (cfg == server) return s else s.close() }
        val s = ServerSession(server, app.secrets.get(Secrets.serverSecret(server.id)), app.cacheDir)
        live[server.id] = server to s
        return s
    }

    suspend fun byId(id: String): ServerSession? = app.db.servers().get(id)?.let(::get)

    /** Credentials or URL changed: drop the old session so the next [get] builds a fresh one. */
    fun invalidate(id: String) { live.remove(id)?.second?.close() }

    fun kickAll() = live.values.forEach { it.second.kick() }
}
