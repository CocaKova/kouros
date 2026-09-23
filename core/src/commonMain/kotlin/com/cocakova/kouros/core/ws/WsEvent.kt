package com.cocakova.kouros.core.ws

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One text message from ComfyUI's `/ws` socket, decoded.
 *
 * Every event the server emits is `{"type": ..., "data": {...}}`. The ones a runner cares about
 * get their own case; anything else (custom nodes emit their own types) arrives as [Other] with
 * the raw payload, so nothing is ever dropped silently.
 */
sealed interface WsEvent {
    /** Queue depth changed. `sid` is present on the first status after connecting. */
    data class Status(val queueRemaining: Int?, val sid: String?) : WsEvent

    /** The server's answer to our `feature_flags` hello: what it can do. */
    data class FeatureFlags(val flags: JsonObject) : WsEvent

    data class ExecutionStart(val promptId: String) : WsEvent

    /** Nodes whose outputs came from cache and will not execute. */
    data class ExecutionCached(val promptId: String, val nodes: List<String>) : WsEvent

    /** Node [node] began executing. `node == null` means the prompt is finished. */
    data class Executing(val promptId: String?, val node: String?, val displayNode: String?) : WsEvent

    /** Step progress inside one node (a sampler's steps, a decoder's tiles). */
    data class Progress(val promptId: String?, val node: String?, val value: Int, val max: Int) : WsEvent

    /** Per-node progress for every node that is not pending (newer servers). */
    data class ProgressState(val promptId: String, val nodes: Map<String, NodeProgress>) : WsEvent

    /** A node produced UI output (images, video, audio, text…) — raw, see [Outputs]. */
    data class Executed(
        val promptId: String?,
        val node: String,
        val displayNode: String?,
        val output: JsonObject,
    ) : WsEvent

    data class ExecutionSuccess(val promptId: String) : WsEvent

    data class ExecutionError(
        val promptId: String,
        val nodeId: String?,
        val nodeType: String?,
        val exceptionType: String?,
        val exceptionMessage: String?,
        val traceback: List<String>,
    ) : WsEvent

    data class ExecutionInterrupted(val promptId: String, val nodeId: String?, val nodeType: String?) : WsEvent

    data class Other(val type: String, val data: JsonElement) : WsEvent
}

data class NodeProgress(
    val value: Double,
    val max: Double,
    val state: String,
    val displayNodeId: String?,
    val parentNodeId: String?,
    val realNodeId: String?,
)

object WsTextParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses one text frame. Returns null only for frames that are not a JSON object at all. */
    fun parse(text: String): WsEvent? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val type = root.str("type") ?: return null
        val data = root["data"] as? JsonObject ?: JsonObject(emptyMap())
        return runCatching { decode(type, data) }.getOrElse { WsEvent.Other(type, data) }
    }

    private fun decode(type: String, d: JsonObject): WsEvent = when (type) {
        "status" -> WsEvent.Status(
            queueRemaining = d.obj("status")?.obj("exec_info")?.int("queue_remaining"),
            sid = d.str("sid"),
        )
        "feature_flags" -> WsEvent.FeatureFlags(d)
        "execution_start" -> WsEvent.ExecutionStart(d.str("prompt_id")!!)
        "execution_cached" -> WsEvent.ExecutionCached(
            d.str("prompt_id")!!,
            (d["nodes"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.asId() } ?: emptyList(),
        )
        "executing" -> WsEvent.Executing(d.str("prompt_id"), d["node"]?.asId(), d["display_node"]?.asId())
        "progress" -> WsEvent.Progress(
            d.str("prompt_id"), d["node"]?.asId(),
            d.int("value") ?: 0, d.int("max") ?: 0,
        )
        "progress_state" -> WsEvent.ProgressState(
            d.str("prompt_id")!!,
            (d["nodes"] as? JsonObject)?.mapValues { (_, v) ->
                val n = v.jsonObject
                NodeProgress(
                    value = n.dbl("value") ?: 0.0,
                    max = n.dbl("max") ?: 0.0,
                    state = n.str("state") ?: "",
                    displayNodeId = n["display_node_id"]?.asId(),
                    parentNodeId = n["parent_node_id"]?.asId(),
                    realNodeId = n["real_node_id"]?.asId(),
                )
            } ?: emptyMap(),
        )
        "executed" -> WsEvent.Executed(
            d.str("prompt_id"), d["node"]!!.asId()!!, d["display_node"]?.asId(),
            d["output"] as? JsonObject ?: JsonObject(emptyMap()),
        )
        "execution_success" -> WsEvent.ExecutionSuccess(d.str("prompt_id")!!)
        "execution_error" -> WsEvent.ExecutionError(
            promptId = d.str("prompt_id")!!,
            nodeId = d["node_id"]?.asId(),
            nodeType = d.str("node_type"),
            exceptionType = d.str("exception_type"),
            exceptionMessage = d.str("exception_message"),
            traceback = (d["traceback"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
        )
        "execution_interrupted" -> WsEvent.ExecutionInterrupted(
            d.str("prompt_id")!!, d["node_id"]?.asId(), d.str("node_type"),
        )
        else -> WsEvent.Other(type, d)
    }

    /** The hello a client sends first, so the server uses the richer preview frame. */
    fun featureFlagsHello(): String =
        """{"type":"feature_flags","data":{"supports_preview_metadata":true}}"""
}

/** Node ids arrive as strings or numbers depending on the server path; normalize to string. */
internal fun JsonElement.asId(): String? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> contentOrNull
    else -> null
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
