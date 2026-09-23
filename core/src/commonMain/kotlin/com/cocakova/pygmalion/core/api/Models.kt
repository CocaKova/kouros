package com.cocakova.pygmalion.core.api

import com.cocakova.pygmalion.core.ws.asId
import com.cocakova.pygmalion.core.ws.bool
import com.cocakova.pygmalion.core.ws.dbl
import com.cocakova.pygmalion.core.ws.int
import com.cocakova.pygmalion.core.ws.obj
import com.cocakova.pygmalion.core.ws.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * ComfyUI REST shapes. Parsed by hand from [JsonObject] rather than by generated serializers:
 * the server's payloads grow new fields every release and custom nodes add their own, and a
 * runner must keep working when they do. Every parser here tolerates missing and extra keys.
 */

/** What POST /prompt said. [nodeErrors] is keyed by node id; empty on success. */
data class PromptAccepted(val promptId: String, val number: Double?, val nodeErrors: JsonObject)

/** POST /prompt refused the prompt (validation failed). The body explains why. */
data class PromptRejected(val message: String, val details: String?, val nodeErrors: JsonObject)

/** One entry of the queue: `[number, prompt_id, prompt, extra_data, outputs_to_execute, …]`. */
data class QueueItem(val number: Double?, val promptId: String, val prompt: JsonObject?, val extraData: JsonObject?)

data class QueueSnapshot(val running: List<QueueItem>, val pending: List<QueueItem>) {
    val promptIds: Set<String> get() = (running + pending).mapTo(mutableSetOf()) { it.promptId }

    companion object {
        fun parse(o: JsonObject) = QueueSnapshot(
            running = (o["queue_running"] as? JsonArray)?.mapNotNull(::item) ?: emptyList(),
            pending = (o["queue_pending"] as? JsonArray)?.mapNotNull(::item) ?: emptyList(),
        )

        private fun item(e: JsonElement): QueueItem? {
            val a = e as? JsonArray ?: return null
            val id = a.getOrNull(1)?.asId() ?: return null
            return QueueItem(
                number = (a.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
                promptId = id,
                prompt = a.getOrNull(2) as? JsonObject,
                extraData = a.getOrNull(3) as? JsonObject,
            )
        }
    }
}

/** A file the server produced or holds, addressable through GET /view. */
data class FileRef(val filename: String, val subfolder: String, val type: String) {
    companion object {
        fun parse(o: JsonObject): FileRef? {
            val name = o.str("filename") ?: return null
            return FileRef(name, o.str("subfolder") ?: "", o.str("type") ?: "output")
        }
    }
}

data class HistoryEntry(
    val promptId: String,
    /** The API prompt exactly as it ran — the most faithful template a workflow can have. */
    val prompt: JsonObject?,
    val extraData: JsonObject?,
    /** node id → the node's UI output object (see [Outputs.classify]). */
    val outputs: Map<String, JsonObject>,
    val statusStr: String?,
    val completed: Boolean?,
    /** `status.messages`: `[event, data]` pairs, including any execution_error. */
    val messages: List<Pair<String, JsonObject>>,
) {
    /** The UI workflow the frontend attached when it queued this, if any. */
    val workflow: JsonObject? get() = extraData?.obj("extra_pnginfo")?.obj("workflow")

    val error: JsonObject?
        get() = messages.firstOrNull { it.first == "execution_error" }?.second

    val startedAtMs: Long? get() = timestampOf("execution_start")
    val endedAtMs: Long?
        get() = timestampOf("execution_success") ?: timestampOf("execution_error") ?: timestampOf("execution_interrupted")

    private fun timestampOf(event: String): Long? =
        messages.firstOrNull { it.first == event }?.second?.get("timestamp")
            ?.let { (it as? JsonPrimitive)?.longOrNull }

    companion object {
        /** GET /history and /history/{id} both return `{prompt_id: entry, …}`. */
        fun parseMap(o: JsonObject): List<HistoryEntry> = o.mapNotNull { (id, v) -> parse(id, v as? JsonObject ?: return@mapNotNull null) }

        fun parse(id: String, o: JsonObject): HistoryEntry {
            val p = o["prompt"] as? JsonArray
            val status = o.obj("status")
            return HistoryEntry(
                promptId = id,
                prompt = p?.getOrNull(2) as? JsonObject,
                extraData = p?.getOrNull(3) as? JsonObject,
                outputs = (o["outputs"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }?.toMap() ?: emptyMap(),
                statusStr = status?.str("status_str"),
                completed = status?.bool("completed"),
                messages = (status?.get("messages") as? JsonArray)?.mapNotNull { m ->
                    val pair = m as? JsonArray ?: return@mapNotNull null
                    val ev = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    ev to (pair.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap()))
                } ?: emptyList(),
            )
        }
    }
}

data class DeviceStats(val name: String, val type: String, val vramTotal: Long?, val vramFree: Long?)

data class SystemStats(
    val comfyuiVersion: String?,
    val pythonVersion: String?,
    val os: String?,
    val ramTotal: Long?,
    val ramFree: Long?,
    val devices: List<DeviceStats>,
) {
    companion object {
        fun parse(o: JsonObject): SystemStats {
            val sys = o.obj("system")
            return SystemStats(
                comfyuiVersion = sys?.str("comfyui_version"),
                pythonVersion = sys?.str("python_version"),
                os = sys?.str("os"),
                ramTotal = sys?.dbl("ram_total")?.toLong(),
                ramFree = sys?.dbl("ram_free")?.toLong(),
                devices = (o["devices"] as? JsonArray)?.mapNotNull { d ->
                    val dev = d as? JsonObject ?: return@mapNotNull null
                    DeviceStats(
                        name = dev.str("name") ?: "device",
                        type = dev.str("type") ?: "",
                        vramTotal = dev.dbl("vram_total")?.toLong(),
                        vramFree = dev.dbl("vram_free")?.toLong(),
                    )
                } ?: emptyList(),
            )
        }
    }
}

/** A saved workflow in the server's userdata store (`GET /userdata?dir=workflows&full_info=true`). */
data class UserdataFile(val path: String, val size: Long?, val modified: Double?) {
    val displayName: String get() = path.substringAfterLast('/').removeSuffix(".json")
}

/** What a server can do, learned on connect — never assumed from its version string. */
data class ServerCapabilities(
    val features: JsonObject = JsonObject(emptyMap()),
    val hasJobsApi: Boolean = false,
    val supportsPreviewMetadata: Boolean = false,
    val acceptsClientPromptId: Boolean = true,
) {
    val maxUploadSize: Long? get() = (features["max_upload_size"] as? JsonPrimitive)?.longOrNull
}

internal fun JsonObject.intOrNull(key: String) = int(key)
