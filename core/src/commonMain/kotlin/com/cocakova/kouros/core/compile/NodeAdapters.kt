package com.cocakova.kouros.core.compile

import com.cocakova.kouros.core.api.InputDef
import com.cocakova.kouros.core.ws.bool
import com.cocakova.kouros.core.ws.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What the compiler cannot learn from `/object_info`, expressed as data.
 *
 * The frontend runs JavaScript that node packs ship, and a few packs use it to add widgets or
 * virtual nodes the server never describes. Rather than code for any pack, the compiler reads
 * rules: the defaults below cover the ComfyUI frontend itself; users (or a future rules feed)
 * can add more without an app release. Nothing here is specific to one person's setup.
 */
class NodeAdapters(
    /** Input types (beyond INT/FLOAT/STRING/BOOLEAN/COMBO) that the frontend renders as a widget. */
    val widgetTypes: Map<String, WidgetTypeRule>,
    /** Node classes that exist only in the frontend and pass input N straight to output N. */
    val virtualPassthrough: Set<String>,
    /** Node classes that exist only in the frontend and take no part in execution. */
    val virtualInert: Set<String>,
    /** Extra frontend-only widgets for specific classes, inserted after a named input's widget. */
    val extraWidgets: Map<String, List<ExtraWidget>>,
    /**
     * How a model's prompt refers to its reference images, per node class: `{n}` is the slot's
     * number ("<image{n}>" → "<image2>" for slot image_2). Without a rule, the slot's name.
     */
    val referenceTokens: Map<String, String> = emptyMap(),
    /**
     * Ranges a node declares but its code cannot honour, by class and input name. A node that
     * says its seed goes to 2^64 and then hands it to a library that stops at 2^32 will fail on
     * a value it advertised as legal, and only at run time. Narrowing the spec here keeps every
     * reader — the form's bounds, the seed that moves on after a run, the validator — inside
     * what the node can actually take.
     */
    val inputLimits: Map<String, Map<String, Limit>> = emptyMap(),
) {
    /**
     * @param slot the widget holds a position in `widgets_values`
     * @param sendsValue its value goes into the prompt
     * @param replicable false when the frontend computes the value at queue time (uploads a
     *   painted mask, renders a 3D viewport…) — the compile is then not trusted on its own
     */
    data class WidgetTypeRule(
        val sendsValue: Boolean,
        val slot: Boolean = true,
        val replicable: Boolean = true,
        val default: kotlinx.serialization.json.JsonElement? = null,
    )

    /** A narrower range than a node declares. */
    data class Limit(val min: Double? = null, val max: Double? = null)

    /** A frontend-only widget. `{n}` in [name] makes it repeat for every remaining saved value. */
    data class ExtraWidget(val after: String?, val name: String, val sendsValue: Boolean) {
        val repeats: Boolean get() = "{n}" in name
    }

    fun merge(other: NodeAdapters) = NodeAdapters(
        widgetTypes = widgetTypes + other.widgetTypes,
        virtualPassthrough = virtualPassthrough + other.virtualPassthrough,
        virtualInert = virtualInert + other.virtualInert,
        extraWidgets = extraWidgets + other.extraWidgets,
        referenceTokens = referenceTokens + other.referenceTokens,
        inputLimits = inputLimits + other.inputLimits,
    )

    /** [spec] with this class's narrower bounds applied, when it has any. */
    fun limited(className: String, spec: InputDef): InputDef {
        val limit = inputLimits[className]?.get(spec.name) ?: return spec
        val patched = buildMap<String, kotlinx.serialization.json.JsonElement> {
            putAll(spec.options)
            limit.min?.let { put("min", JsonPrimitive(it)) }
            limit.max?.let { put("max", JsonPrimitive(it)) }
        }
        return spec.copy(options = JsonObject(patched))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Rules format:
         * ```
         * { "widgetTypes": {"COLOR": {"sendsValue": true}},
         *   "virtualPassthrough": ["Reroute"],
         *   "virtualInert": ["Note"],
         *   "extraWidgets": {"LoadAudio": [{"after": "audio", "name": "audioUI", "sendsValue": false}]},
         *   "referenceTokens": {"TextEncodeQwenImage21": "<image{n}>"} }
         * ```
         */
        fun parse(text: String): NodeAdapters {
            val o = json.parseToJsonElement(text) as JsonObject
            fun strings(key: String) = (o[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()
            return NodeAdapters(
                widgetTypes = (o["widgetTypes"] as? JsonObject)?.mapValues { (_, v) ->
                    val r = v as? JsonObject
                    WidgetTypeRule(
                        sendsValue = r?.bool("sendsValue") ?: true,
                        slot = r?.bool("slot") ?: true,
                        replicable = r?.bool("replicable") ?: true,
                        default = r?.get("default"),
                    )
                } ?: emptyMap(),
                virtualPassthrough = strings("virtualPassthrough"),
                virtualInert = strings("virtualInert"),
                extraWidgets = (o["extraWidgets"] as? JsonObject)?.mapValues { (_, v) ->
                    (v as? JsonArray)?.mapNotNull { w ->
                        val wo = w as? JsonObject ?: return@mapNotNull null
                        ExtraWidget(wo.str("after"), wo.str("name") ?: return@mapNotNull null, wo.bool("sendsValue") ?: false)
                    } ?: emptyList()
                } ?: emptyMap(),
                referenceTokens = (o["referenceTokens"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap() ?: emptyMap(),
                inputLimits = (o["inputLimits"] as? JsonObject)?.mapValues { (_, v) ->
                    (v as? JsonObject)?.mapValues { (_, l) ->
                        val lo = l as? JsonObject
                        Limit(
                            min = (lo?.get("min") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
                            max = (lo?.get("max") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
                        )
                    } ?: emptyMap()
                } ?: emptyMap(),
            )
        }

        /** The ComfyUI frontend's own widget registry and virtual nodes. */
        val DEFAULT: NodeAdapters = parse(
            """
            {
              "widgetTypes": {
                "MARKDOWN": {"sendsValue": true}, "COLOR": {"sendsValue": true},
                "IMAGECOMPARE": {"sendsValue": true, "slot": false, "default": ["", ""]},
                "BOUNDING_BOX": {"sendsValue": true},
                "CHART": {"sendsValue": true}, "GALLERIA": {"sendsValue": true},
                "PAINTER": {"sendsValue": true, "replicable": false},
                "COMPOSITOR": {"sendsValue": true, "replicable": false},
                "TEXTAREA": {"sendsValue": true}, "CURVE": {"sendsValue": true},
                "RANGE": {"sendsValue": true}, "VIDEO_EDIT": {"sendsValue": true, "replicable": false},
                "RESOLUTION_PREVIEW": {"sendsValue": false, "slot": false},
                "BOUNDING_BOXES": {"sendsValue": true},
                "COLORS": {"sendsValue": true},
                "LOAD_3D": {"sendsValue": true, "replicable": false}
              },
              "virtualPassthrough": ["Reroute"],
              "virtualInert": ["Note", "MarkdownNote", "PrimitiveNode"],
              "extraWidgets": {
                "SaveGLB": [{"after": null, "name": "image", "sendsValue": true}],
                "LoadAudio": [{"after": null, "name": "audioUI", "sendsValue": false}],
                "Preview3D": [{"after": null, "name": "image", "sendsValue": true}],
                "CustomCombo": [
                  {"after": "choice", "name": "index", "sendsValue": true},
                  {"after": "choice", "name": "option{n}", "sendsValue": true}
                ]
              },
              "referenceTokens": {
                "TextEncodeQwenImage21": "<image{n}>"
              },
              "inputLimits": {
                "SUPIR_Upscale": {"seed": {"max": 4294967295}},
                "SUPIR_sample": {"seed": {"max": 4294967295}}
              }
            }
            """.trimIndent(),
        )
    }
}
