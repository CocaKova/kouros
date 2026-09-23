package com.cocakova.kouros.data

import com.cocakova.kouros.KourosApp
import com.cocakova.kouros.core.api.OutputItem
import com.cocakova.kouros.core.api.Outputs
import com.cocakova.kouros.run.RunCoordinator

/**
 * Deleting what runs made. The server keeps two things: the files in its output folder and the
 * history entry that lists them. Stock ComfyUI can only drop the history entry; with the Kouros
 * Bridge installed, the files go too. A run whose every output is deleted is forgotten entirely
 * (server history and this phone); one with outputs left keeps the rest.
 */
class MediaManager(private val app: KourosApp) {
    data class Target(val serverId: String, val promptId: String, val item: OutputItem)

    data class Outcome(val items: Int, val filesDeleted: Int, val filesKept: Int, val runsForgotten: Int, val errors: List<String>) {
        fun summary(): String = buildString {
            append(if (filesDeleted > 0) "Deleted $filesDeleted file${if (filesDeleted == 1) "" else "s"}" else "Removed $items item${if (items == 1) "" else "s"}")
            if (filesKept > 0) append(" — $filesKept file${if (filesKept == 1) "" else "s"} still on the server (install Kouros Bridge to delete files)")
            errors.firstOrNull()?.let { append(". $it") }
        }
    }

    /** Whether deleting on this server removes files, not just history. */
    suspend fun canDeleteFiles(serverId: String): Boolean =
        app.sessions.byId(serverId)?.let { runCatching { it.client.bridge()?.canDelete == true }.getOrDefault(false) } ?: false

    suspend fun delete(targets: List<Target>): Outcome {
        var deleted = 0; var kept = 0; var forgotten = 0
        val errors = mutableListOf<String>()
        for ((serverId, group) in targets.groupBy { it.serverId }) {
            val session = app.sessions.byId(serverId) ?: run { errors += "Server removed"; null } ?: continue
            val files = group.mapNotNull { it.item.file }.distinct()
            if (files.isNotEmpty()) {
                if (session.client.bridge()?.canDelete == true) {
                    runCatching { session.client.deleteOutputs(files) }
                        .onSuccess { r -> deleted += r.deleted.size + r.missing.size; kept += r.refused.size }
                        .onFailure { errors += "Couldn't delete files: ${it.message}"; kept += files.size }
                } else kept += files.size
            }
            for ((promptId, picked) in group.groupBy { it.promptId }) {
                if (promptId.startsWith(com.cocakova.kouros.ui.gallery.FileTile.PREFIX)) continue // a bare file: no run to forget
                val gone = picked.mapNotNull { it.item.file }.toSet()
                val local = app.db.runs().get(promptId)
                val all = local?.let { RunCoordinator.decodeOutputs(it.outputsJson) }
                    ?: runCatching { session.client.historyFor(promptId) }.getOrNull()?.outputs
                        ?.flatMap { (id, o) -> Outputs.classify(id, o) }.orEmpty()
                val remaining = all.filter { it.file != null && it.file !in gone && !it.isTemp }
                if (remaining.isEmpty()) {
                    runCatching { session.client.deleteHistory(listOf(promptId)) }
                    local?.let { app.db.runs().delete(promptId) }
                    forgotten++
                } else if (local != null) {
                    app.db.runs().update(local.copy(outputsJson = RunCoordinator.encodeOutputs(all.filter { it.file == null || it.file !in gone })))
                }
            }
        }
        com.cocakova.kouros.widget.KourosWidget.refresh(app)
        return Outcome(targets.size, deleted, kept, forgotten, errors)
    }

    /** Forgets whole runs: every output file (when the bridge allows) and the history entries. */
    suspend fun deleteRuns(serverId: String, promptIds: List<String>): Outcome {
        val session = app.sessions.byId(serverId) ?: return Outcome(0, 0, 0, 0, listOf("Server removed"))
        val targets = promptIds.flatMap { id ->
            val local = app.db.runs().get(id)
            val outs = local?.let { RunCoordinator.decodeOutputs(it.outputsJson) }
                ?: runCatching { session.client.historyFor(id) }.getOrNull()?.outputs?.flatMap { (n, o) -> Outputs.classify(n, o) }.orEmpty()
            val durable = outs.filter { it.file != null && !it.isTemp }
            if (durable.isEmpty()) {
                // Nothing on disk to delete (failed or text-only runs): just forget it.
                runCatching { session.client.deleteHistory(listOf(id)) }
                local?.let { app.db.runs().delete(id) }
                emptyList()
            } else durable.map { Target(serverId, id, it) }
        }
        val out = delete(targets)
        return out.copy(runsForgotten = out.runsForgotten + (promptIds.size - targets.map { it.promptId }.distinct().size))
    }
}
