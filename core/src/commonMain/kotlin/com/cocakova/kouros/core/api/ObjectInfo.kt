package com.cocakova.kouros.core.api

import com.cocakova.kouros.core.ws.bool
import com.cocakova.kouros.core.ws.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * `/object_info`: the server's own description of every node class it can run. This is the
 * single source of truth the compiler and the form engine read — nothing about any particular
 * node pack is hard-coded in the app. A node that exists on the server is understood through
 * what the server says about it.
 */
class ObjectInfo(val nodes: Map<String, NodeDef>) {
    operator fun get(classType: String): NodeDef? = nodes[classType]

    companion object {
        fun parse(o: JsonObject): ObjectInfo =
            ObjectInfo(o.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to NodeDef.parse(k, it) } }.toMap())
    }
}

class NodeDef(
    val name: String,
    val displayName: String?,
    val category: String?,
    val isOutputNode: Boolean,
    /** Inputs in the order the frontend builds them: required first, then optional. */
    val inputs: List<InputDef>,
    val outputs: List<OutputDef>,
    val deprecated: Boolean,
) {
    fun input(name: String): InputDef? = inputs.firstOrNull { it.name == name }

    companion object {
        fun parse(name: String, o: JsonObject): NodeDef {
            val input = o["input"] as? JsonObject
            val order = o["input_order"] as? JsonObject
            val required = ordered(input?.get("required") as? JsonObject, order?.get("required") as? JsonArray, optional = false)
            val optional = ordered(input?.get("optional") as? JsonObject, order?.get("optional") as? JsonArray, optional = true)

            val outTypes = (o["output"] as? JsonArray)?.map { it.typeName() } ?: emptyList()
            val outNames = (o["output_name"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull }
            val outList = (o["output_is_list"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull == "true" }
            return NodeDef(
                name = name,
                displayName = o.str("display_name"),
                category = o.str("category"),
                isOutputNode = o.bool("output_node") == true,
                inputs = required + optional,
                outputs = outTypes.mapIndexed { i, t ->
                    OutputDef(outNames?.getOrNull(i) ?: t, t, outList?.getOrNull(i) == true)
                },
                deprecated = o.bool("deprecated") == true,
            )
        }

        /** Mirrors the frontend's getOrderedInputSpecs: `input_order` when given, else key order. */
        private fun ordered(specs: JsonObject?, order: JsonArray?, optional: Boolean): List<InputDef> {
            specs ?: return emptyList()
            val names = order?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.filter { it in specs } ?: specs.keys.toList()
            return names.mapNotNull { n -> InputDef.parse(n, specs[n] ?: return@mapNotNull null, optional) }
        }
    }
}

data class OutputDef(val name: String, val type: String, val isList: Boolean)

/**
 * One input as the server declares it: `[TYPE, {options}]`, where TYPE is a string, or — the
 * legacy combo form — a list of allowed values.
 */
data class InputDef(
    val name: String,
    /** Normalized type: "INT", "FLOAT", "STRING", "BOOLEAN", "COMBO", or a socket type ("IMAGE", "MODEL"…). */
    val type: String,
    val optional: Boolean,
    val options: JsonObject,
    /** Allowed values for a combo, from either form. */
    val choices: List<JsonElement>?,
) {
    /** The widget the frontend builds: `widgetType` overrides the socket type ("FLOAT,INT" → FLOAT). */
    val widgetType: String get() = options.str("widgetType") ?: type
    val isWidgetType: Boolean get() = widgetType in WIDGET_TYPES
    val socketless: Boolean get() = options.bool("socketless") == true
    val forceInput: Boolean get() = options.bool("forceInput") == true
    val multiline: Boolean get() = options.bool("multiline") == true
    val default: JsonElement? get() = options["default"]
    val min: Double? get() = (options["min"] as? JsonPrimitive)?.doubleOrNull
    val max: Double? get() = (options["max"] as? JsonPrimitive)?.doubleOrNull
    val step: Double? get() = (options["step"] as? JsonPrimitive)?.doubleOrNull
    val tooltip: String? get() = options.str("tooltip")
    val displayName: String? get() = options.str("display_name")
    val advanced: Boolean get() = options.bool("advanced") == true
    val hidden: Boolean get() = options.bool("hidden") == true

    /** `control_after_generate`: an explicit flag/default on the spec, or the seed-name convention. */
    val controlAfterGenerate: String?
        get() {
            val raw = options["control_after_generate"] as? JsonPrimitive
            if (raw != null) {
                if (raw.contentOrNull == "false") return null
                if (raw.isString) return raw.content
                if (raw.contentOrNull == "true") return "randomize"
            }
            return if ((widgetType == "INT" || widgetType == "FLOAT") && name in SEED_NAMES) "randomize" else null
        }

    /** A media combo the frontend pairs with an upload button (an extra widget slot). */
    val uploadKind: String?
        get() = if (type != "COMBO") null
        else UPLOAD_FLAGS.firstOrNull { options.bool(it) == true }?.removeSuffix("_upload")

    companion object {
        val WIDGET_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN", "COMBO")
        val SEED_NAMES = setOf("seed", "noise_seed")
        private val UPLOAD_FLAGS = listOf("image_upload", "video_upload", "audio_upload", "animated_image_upload", "mesh_upload")

        fun parse(name: String, spec: JsonElement, optional: Boolean): InputDef? {
            val arr = spec as? JsonArray ?: return null
            val head = arr.getOrNull(0) ?: return null
            val opts = arr.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap())
            return if (head is JsonArray) {
                InputDef(name, "COMBO", optional, opts, head.toList())
            } else {
                val t = head.typeName()
                val choices = if (t == "COMBO") (opts["options"] as? JsonArray)?.toList() else null
                InputDef(name, t, optional, opts, choices)
            }
        }
    }
}

private fun JsonElement.typeName(): String = when (this) {
    is JsonPrimitive -> contentOrNull ?: "*"
    is JsonArray -> "COMBO"
    else -> "*"
}
