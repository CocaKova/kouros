package com.cocakova.kouros.data

import com.cocakova.kouros.KourosApp
import com.cocakova.kouros.core.api.ManagerQueue
import com.cocakova.kouros.core.apps.AppCatalog
import com.cocakova.kouros.core.apps.AppNeeds
import com.cocakova.kouros.core.apps.AppSpec
import com.cocakova.kouros.core.apps.NodePack
import com.cocakova.kouros.net.ServerSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An app on one server: the spec, and what that server still needs before it will run. */
data class AppStatus(val spec: AppSpec, val needs: AppNeeds, val ready: Boolean = needs.met)

/**
 * The apps this build ships, and how they meet a server.
 *
 * An app is a workflow like any other once it lands: [open] writes the spec's prompt into the
 * workflow table under the app's own key, with the spec's form arrangement, so the run screen,
 * the queue, the gallery and the notifications work on it unchanged. What is special is only
 * the reading beforehand — what the server is missing, and how to get it.
 */
class AppRepo(private val app: KourosApp) {

    /** The shipped catalog, read once from assets. */
    val catalog: List<AppSpec> by lazy {
        runCatching { AppCatalog.parse(app.assets.open(ASSET).bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())
    }

    fun spec(id: String): AppSpec? = catalog.firstOrNull { it.id == id }

    /** The app an existing workflow row is, when it is one. */
    fun specFor(w: WorkflowEntity): AppSpec? = w.appId?.let(::spec)

    /**
     * Every app measured against [session]: which node packs it is missing and which model
     * files. One `/object_info` and one listing per model folder covers the whole catalog.
     */
    suspend fun statuses(session: ServerSession): List<AppStatus> {
        val oi = runCatching { session.objectInfo() }.getOrNull() ?: return catalog.map { AppStatus(it, AppNeeds(emptyList(), emptyList()), ready = false) }
        val folders = catalog.flatMapTo(LinkedHashSet()) { it.modelFolders }
        val files = folders.associateWith { f ->
            runCatching { session.client.models(f).toSet() }.getOrDefault(emptySet())
        }
        return withContext(Dispatchers.Default) { catalog.map { AppStatus(it, it.needs(oi, files)) } }
    }

    suspend fun status(session: ServerSession, spec: AppSpec): AppStatus {
        val oi = session.objectInfo()
        val files = spec.modelFolders.associateWith { f -> runCatching { session.client.models(f).toSet() }.getOrDefault(emptySet()) }
        return AppStatus(spec, spec.needs(oi, files))
    }

    /**
     * The workflow key for [spec] on this server, writing it in the first time. The app's own
     * values survive across opens the way a workflow's do; reopening never overwrites them.
     */
    suspend fun open(session: ServerSession, spec: AppSpec): String {
        val key = WorkflowRepo.key(session.server.id, SOURCE_APP, spec.id)
        val existing = app.db.workflows().get(key)
        val row = existing?.copy(name = spec.title, json = spec.prompt.toString())
            ?: WorkflowEntity(
                key = key,
                serverId = session.server.id,
                source = SOURCE_APP,
                path = spec.id,
                name = spec.title,
                json = spec.prompt.toString(),
                formConfig = spec.layout.encode(),
                appId = spec.id,
                kind = spec.kind,
            )
        app.db.workflows().upsert(row.copy(appId = spec.id, lastOpenedAt = System.currentTimeMillis()))
        return key
    }

    // ---- setup ----

    /** Starts the model downloads an app is missing; returns the bridge's job ids. */
    suspend fun fetchModels(session: ServerSession, needs: AppNeeds): List<String> =
        needs.models.mapNotNull { m ->
            val url = m.url ?: return@mapNotNull null
            runCatching { session.client.downloadModel(url, m.directory, m.name) }.getOrNull()
        }

    /**
     * Asks the server's manager to install [packs]. Returns null when the server has no manager
     * — then the person installs the pack themselves and Kouros only says which one.
     */
    suspend fun installPacks(session: ServerSession, packs: List<NodePack>): Boolean? {
        if (session.client.managerVersion() == null) return null
        var all = packs.isNotEmpty()
        for (p in packs) {
            if (!runCatching { session.client.installNodePack(p.id, p.title, p.repository) }.getOrDefault(false)) all = false
        }
        return all
    }

    suspend fun installProgress(session: ServerSession): ManagerQueue? = session.client.managerQueue()

    /** Restarts the server so installed packs register, then re-reads what it can do. */
    suspend fun restart(session: ServerSession): Boolean {
        val asked = session.client.restartServer()
        if (asked) runCatching { session.objectInfo(refresh = true) }
        return asked
    }

    companion object {
        const val SOURCE_APP = "app"
        private const val ASSET = "apps.json"
    }
}
