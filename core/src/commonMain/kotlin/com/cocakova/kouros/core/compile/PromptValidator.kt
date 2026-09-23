package com.cocakova.kouros.core.compile

import com.cocakova.kouros.core.api.InputDef
import com.cocakova.kouros.core.api.ObjectInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Checks an API prompt against what a server says it can run — before it is sent, so a phone
 * can say "this needs flux1-dev.safetensors, which the server doesn't have" instead of relaying
 * a validation error after the fact. Pure: works on any prompt from any source.
 */
object PromptValidator {
    enum class Kind { MISSING_NODE, BAD_CHOICE, MISSING_INPUT }

    data class Issue(val kind: Kind, val nodeId: String, val input: String?, val value: String?, val message: String)

    fun validate(prompt: JsonObject, objectInfo: ObjectInfo): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((id, n) in prompt) {
            val node = n as? JsonObject ?: continue
            val cls = (node["class_type"] as? JsonPrimitive)?.contentOrNull ?: continue
            val def = objectInfo[cls]
            if (def == null) {
                issues += Issue(Kind.MISSING_NODE, id, null, cls, "Server has no node type $cls")
                continue
            }
            val inputs = node["inputs"] as? JsonObject ?: JsonObject(emptyMap())
            for (spec in expand(def.inputs, inputs)) {
                val v = inputs[spec.name]
                if (v == null) {
                    if (!spec.optional && spec.isWidgetType.not() && spec.widgetType !in DYNAMIC_ONLY) {
                        issues += Issue(Kind.MISSING_INPUT, id, spec.name, null, "$cls needs an input connected to '${spec.name}'")
                    }
                    continue
                }
                checkChoice(id, cls, spec, v)?.let { issues += it }
            }
        }
        return issues
    }

    /** One input's value against its allowed values; null when fine or not checkable. */
    fun checkChoice(id: String, cls: String, spec: InputDef, v: JsonElement): Issue? {
        if (spec.widgetType != "COMBO") return null
        val choices = spec.choices ?: return null
        if (choices.isEmpty() || v is JsonArray) return null // linked, or a dynamic list
        val s = (v as? JsonPrimitive)?.contentOrNull ?: return null
        val allowed = choices.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (s in allowed) return null
        // Media pickers accept annotated names ("x.png [output]") and freshly uploaded files.
        if (spec.uploadKind != null || spec.options["image_upload"] != null) return null
        return Issue(Kind.BAD_CHOICE, id, spec.name, s, "$cls.${spec.name}: '$s' is not available on this server")
    }

    /** Inputs including the sub-inputs of whichever dynamic-combo option the prompt selected. */
    private fun expand(specs: List<InputDef>, values: JsonObject): List<InputDef> = buildList {
        for (spec in specs) {
            add(spec)
            if (spec.widgetType != WorkflowCompiler.DYNAMIC_COMBO) continue
            val chosen = (values[spec.name] as? JsonPrimitive)?.contentOrNull ?: continue
            val option = (spec.options["options"] as? JsonArray)?.firstOrNull {
                ((it as? JsonObject)?.get("key") as? JsonPrimitive)?.contentOrNull == chosen
            } as? JsonObject ?: continue
            val sub = option["inputs"] as? JsonObject ?: continue
            val nested = listOf("required" to false, "optional" to true).flatMap { (section, optional) ->
                (sub[section] as? JsonObject)?.mapNotNull { (k, s) -> InputDef.parse("${spec.name}.$k", s, optional) } ?: emptyList()
            }
            addAll(expand(nested, values))
        }
    }

    private val DYNAMIC_ONLY = setOf(WorkflowCompiler.AUTOGROW, "COMFY_MATCHTYPE_V3")
}
