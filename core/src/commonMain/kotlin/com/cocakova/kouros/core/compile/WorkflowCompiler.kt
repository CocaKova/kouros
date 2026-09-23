package com.cocakova.kouros.core.compile

import com.cocakova.kouros.core.api.InputDef
import com.cocakova.kouros.core.api.NodeDef
import com.cocakova.kouros.core.api.ObjectInfo
import com.cocakova.kouros.core.graph.NodeMode
import com.cocakova.kouros.core.graph.SubgraphDef
import com.cocakova.kouros.core.graph.UiGraph
import com.cocakova.kouros.core.graph.UiNode
import com.cocakova.kouros.core.graph.UiWorkflow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Where one value lands in an API prompt: node (execution id) + input name. */
data class ApiRef(val nodeId: String, val input: String) {
    override fun toString() = "$nodeId.$input"
}

enum class Severity { INFO, WARNING, ERROR }

data class Diagnostic(val severity: Severity, val code: String, val nodeId: String?, val message: String) {
    companion object {
        const val MISSING_NODE = "missing_node"
        const val MISSING_SUBGRAPH = "missing_subgraph"
        const val UNKNOWN_WIDGET_TYPE = "unknown_widget_type"
        const val WIDGET_COUNT = "widget_count"
        const val WIDGET_DRIFT = "widget_drift"
        const val UNRESOLVED_LINK = "unresolved_link"
        const val RECURSION = "recursion"
        const val DYNAMIC_WIDGETS = "dynamic_widgets"
        const val NOT_REPLICABLE = "not_replicable"
        const val WIDGET_SHORTFALL = "widget_shortfall"
        const val EXTENSION_WIDGETS = "extension_widgets"
    }
}

/**
 * The result of compiling a UI workflow into the API format `/prompt` accepts.
 *
 * [widgetRefs] and [promoted] tell the form engine where each visible control's value lives in
 * [prompt], so the form edits the prompt directly and never needs to recompile.
 */
class CompiledWorkflow(
    val prompt: JsonObject,
    val diagnostics: List<Diagnostic>,
    /** "<execId>:<widget>" → the prompt input carrying that widget's value. */
    val widgetRefs: Map<String, ApiRef>,
    /** "<instanceExecId>:<subgraphInput>" → every inner input a promoted subgraph widget feeds. */
    val promoted: Map<String, List<ApiRef>>,
    /** control_after_generate mode for each seed-like widget, by where its value lands. */
    val seedControls: Map<ApiRef, String>,
) {
    /** True when nothing suggests the output could differ from what the desktop frontend sends. */
    val confident: Boolean
        get() = diagnostics.none {
            it.severity == Severity.ERROR ||
                it.code in setOf(
                    Diagnostic.UNKNOWN_WIDGET_TYPE, Diagnostic.WIDGET_COUNT,
                    Diagnostic.DYNAMIC_WIDGETS, Diagnostic.NOT_REPLICABLE,
                )
        }
}

/**
 * Compiles a UI workflow to an API prompt, following the ComfyUI frontend's own `graphToPrompt`
 * semantics: subgraphs flatten to `outer:inner` execution ids, muted nodes vanish, bypassed
 * nodes pass a same-typed input through, reroutes are followed, primitive nodes push their
 * value into their targets, and widget values are laid out from the server's node definitions.
 *
 * Everything node-specific comes from [objectInfo] (what the server says) and [adapters]
 * (frontend-only behavior, as data). There are no node names in this file.
 */
class WorkflowCompiler(
    private val objectInfo: ObjectInfo,
    private val adapters: NodeAdapters = NodeAdapters.DEFAULT,
) {
    fun compile(workflow: UiWorkflow): CompiledWorkflow = Run(workflow).compile()

    private sealed interface Resolved {
        data class Link(val originId: String, val slot: Int) : Resolved
        data class Value(val value: JsonElement, val promotedKey: String?) : Resolved
    }

    private class DtoInput(val name: String, val type: String, val link: Int?)

    private inner class Run(val wf: UiWorkflow) {
        val diags = mutableListOf<Diagnostic>()
        val dtos = LinkedHashMap<String, Dto>()
        /** PrimitiveNode pushes: (graph, nodeId, widget) → value. */
        val pushed = HashMap<Triple<UiGraph, String, String>, JsonElement>()
        val widgetCache = HashMap<String, Widgets>()
        val seedControls = LinkedHashMap<ApiRef, String>()

        inner class Dto(val node: UiNode, val graph: UiGraph, val path: List<String>, val parent: Dto?) {
            val id: String = (path + node.id).joinToString(":")
            val def: SubgraphDef? = wf.subgraphs[node.type]
            val classDef: NodeDef? = objectInfo[node.type]
            val isVirtual: Boolean = def != null ||
                (classDef == null && (node.type in adapters.virtualPassthrough || node.type in adapters.virtualInert))
            val mode: Int get() = node.mode

            /** Live inputs: for a subgraph instance, one per subgraph input, in definition order. */
            val inputs: List<DtoInput> = if (def != null) {
                // Saved instances may list only some inputs (the frontend compresses unlinked
                // widget inputs away), so match by name — never by position.
                def.inputs.map { port -> DtoInput(port.name, port.type, node.input(port.name)?.link) }
            } else node.inputs.map { DtoInput(it.name, it.type, it.link) }

            fun outputType(slot: Int): String =
                def?.outputs?.getOrNull(slot)?.type ?: node.outputs.getOrNull(slot)?.type ?: classDef?.outputs?.getOrNull(slot)?.type ?: "*"

            // ---- promoted widgets (subgraph instances only) ----

            /** Values of this instance's promoted widgets, by subgraph input index. */
            val promotedValues: Map<Int, JsonElement> by lazy { computePromoted() }

            private fun computePromoted(): Map<Int, JsonElement> {
                val d = def ?: return emptyMap()
                val out = LinkedHashMap<Int, JsonElement>()
                val fileValues = node.widgetsValues
                var valueIndex = 0
                d.inputs.forEachIndexed { k, port ->
                    val interior = interiorWidgetValue(d, port.linkIds) ?: return@forEachIndexed
                    var v = interior
                    if (fileValues != null && valueIndex < fileValues.size) v = fileValues[valueIndex]
                    out[k] = v
                    valueIndex++
                }
                return out
            }

            /** The current value of the inner widget a subgraph input is wired to, if any. */
            private fun interiorWidgetValue(d: SubgraphDef, linkIds: List<Int>): JsonElement? {
                for (linkId in linkIds) {
                    val link = d.graph.links[linkId] ?: continue
                    val target = d.graph.byId[link.targetId] ?: continue
                    val targetInput = target.inputs.firstOrNull { it.link == linkId } ?: continue
                    val innerDto = dtos[(path + node.id + target.id).joinToString(":")]
                    if (innerDto?.def != null) {
                        // Nested promotion: the target is itself a subgraph instance.
                        val k = innerDto.def.inputs.indexOfFirst { it.name == targetInput.name }
                        innerDto.promotedValues[k]?.let { return it }
                        continue
                    }
                    val widgetName = targetInput.widgetName ?: continue
                    val w = innerDto?.let { widgetsOf(it) } ?: continue
                    if (widgetName in w.all) return w.all[widgetName] ?: JsonNull
                }
                return null
            }

            // ---- resolution (mirrors ExecutableNodeDTO) ----

            fun resolveInput(input: DtoInput, visited: MutableSet<String>, type: String? = null): Resolved? {
                val slot = inputs.indexOf(input)
                val key = "${parent?.def?.id}:${node.id}[I]$slot"
                if (!visited.add(key)) { recursion(); return null }
                val linkId = input.link ?: return null
                val link = graph.links[linkId] ?: run {
                    diags += Diagnostic(Severity.WARNING, Diagnostic.UNRESOLVED_LINK, id, "Input '${input.name}' points at link $linkId, which does not exist")
                    return null
                }
                val p = parent
                if (p?.def != null && link.originId == p.def.inputNodeId) {
                    val instInput = p.inputs.getOrNull(link.originSlot) ?: return null
                    if (instInput.link == null) {
                        val v = p.promotedValues[link.originSlot] ?: return null
                        return Resolved.Value(v, "${p.id}:${instInput.name}")
                    }
                    return p.resolveInput(instInput, visited)
                }
                val origin = graph.byId[link.originId] ?: return null
                val originDto = dtos[(path + origin.id).joinToString(":")] ?: return null
                return originDto.resolveOutput(link.originSlot, type ?: input.type, visited)
            }

            fun resolveOutput(slot: Int, type: String, visited: MutableSet<String>): Resolved? {
                val key = "${parent?.def?.id}:${node.id}[O]$slot"
                if (!visited.add(key)) { recursion(); return null }
                if (mode == NodeMode.NEVER) return null
                if (mode == NodeMode.BYPASS) {
                    val idx = bypassSlot(slot, type)
                    if (idx == -1) return null
                    return resolveInput(inputs[idx], visited)
                }
                val d = def
                if (d != null) return resolveSubgraphOutput(d, slot, type, visited)
                if (isVirtual) {
                    if (node.type !in adapters.virtualPassthrough) return null
                    val input = inputs.getOrNull(slot) ?: return null
                    if (input.link == null) return null
                    return resolveInput(input, visited, type)
                }
                return Resolved.Link(id, slot)
            }

            private fun resolveSubgraphOutput(d: SubgraphDef, slot: Int, type: String, visited: MutableSet<String>): Resolved? {
                val port = d.outputs.getOrNull(slot) ?: return null
                val link = port.linkIds.asSequence().mapNotNull { d.graph.links[it] }
                    .firstOrNull { it.targetId == d.outputNodeId } ?: return null
                if (link.originId == d.inputNodeId) {
                    // An output wired straight to an input: pass through the instance's input.
                    val inp = inputs.getOrNull(link.originSlot) ?: return null
                    return if (inp.link != null) resolveInput(inp, visited)
                    else promotedValues[link.originSlot]?.let { Resolved.Value(it, "$id:${inp.name}") }
                }
                val inner = dtos[(path + node.id + link.originId).joinToString(":")] ?: return null
                return inner.resolveOutput(link.originSlot, type, visited)
            }

            private fun bypassSlot(slot: Int, type: String): Int {
                val outType = outputType(slot)
                if (type == "*" || type.isEmpty()) return if (inputs.size > slot) slot else 0
                val opposite = inputs.getOrNull(slot)
                if (opposite != null && validConnection(opposite.type, outType) && validConnection(opposite.type, type)) return slot
                val exact = inputs.indexOfFirst { it.type == type }
                if (exact != -1) return exact
                return inputs.indexOfFirst { validConnection(it.type, outType) && validConnection(it.type, type) }
            }

            private fun recursion() {
                diags += Diagnostic(Severity.ERROR, Diagnostic.RECURSION, id, "Circular link through node ${node.id}")
            }
        }

        fun compile(): CompiledWorkflow {
            // 1. Execution DTOs: root nodes, then (unless muted/bypassed) their subgraph interiors.
            for (node in wf.root.nodes) {
                val dto = Dto(node, wf.root, emptyList(), null)
                dtos[dto.id] = dto
                if (node.mode == NodeMode.NEVER || node.mode == NodeMode.BYPASS) continue
                expand(dto, mutableSetOf())
            }
            // 2. Primitive nodes push their value into the widgets they feed.
            applyPrimitives(wf.root)
            wf.subgraphs.values.forEach { applyPrimitives(it.graph) }

            // 3. Emit.
            val prompt = LinkedHashMap<String, JsonObject>()
            val widgetRefs = LinkedHashMap<String, ApiRef>()
            val promoted = LinkedHashMap<String, MutableList<ApiRef>>()
            for (dto in dtos.values) {
                if (dto.isVirtual || dto.mode == NodeMode.NEVER || dto.mode == NodeMode.BYPASS) continue
                if (dto.classDef == null) {
                    val code = if (dto.node.type.count { it == '-' } == 4) Diagnostic.MISSING_SUBGRAPH else Diagnostic.MISSING_NODE
                    diags += Diagnostic(Severity.ERROR, code, dto.id, "The server has no node type '${dto.node.type}'")
                }
                val inputs = LinkedHashMap<String, JsonElement>()
                val w = widgetsOf(dto)
                for ((name, v) in w.sent) {
                    inputs[name] = wrapArray(v)
                    widgetRefs["${dto.id}:$name"] = ApiRef(dto.id, name)
                }
                for ((name, mode) in w.controls) seedControls[ApiRef(dto.id, name)] = mode
                for (input in dto.inputs) {
                    if (input.link == null) continue
                    when (val r = dto.resolveInput(input, mutableSetOf())) {
                        null -> Unit
                        is Resolved.Value -> {
                            inputs[input.name] = wrapArray(r.value)
                            if (r.promotedKey != null) {
                                promoted.getOrPut(r.promotedKey) { mutableListOf() } += ApiRef(dto.id, input.name)
                                widgetRefs.remove("${dto.id}:${input.name}")
                            }
                        }
                        is Resolved.Link -> {
                            inputs[input.name] = JsonArray(listOf(JsonPrimitive(r.originId), JsonPrimitive(r.slot)))
                            widgetRefs.remove("${dto.id}:${input.name}")
                        }
                    }
                }
                prompt[dto.id] = buildJsonObject {
                    put("inputs", JsonObject(inputs))
                    put("class_type", dto.node.type)
                    put("_meta", buildJsonObject { put("title", dto.node.title ?: dto.classDef?.displayName ?: dto.node.type) })
                }
            }
            // 4. Drop links into nodes that were not emitted (muted, missing…).
            val cleaned = prompt.mapValues { (_, n) ->
                val ins = n["inputs"] as JsonObject
                val kept = ins.filterValues { v ->
                    !(v is JsonArray && v.size == 2 && v[0] is JsonPrimitive && (v[0] as JsonPrimitive).isString &&
                        (v[0] as JsonPrimitive).content !in prompt)
                }
                if (kept.size == ins.size) n else JsonObject(n + ("inputs" to JsonObject(kept)))
            }
            return CompiledWorkflow(JsonObject(cleaned), diags.distinct(), widgetRefs, promoted, seedControls)
        }

        fun expand(inst: Dto, stack: MutableSet<String>) {
            val d = inst.def ?: return
            if (!stack.add(d.id)) {
                diags += Diagnostic(Severity.ERROR, Diagnostic.RECURSION, inst.id, "Subgraph '${d.name}' contains itself")
                return
            }
            for (inner in d.graph.nodes) {
                val dto = Dto(inner, d.graph, inst.path + inst.node.id, inst)
                dtos[dto.id] = dto
                if (dto.def != null) expand(dto, stack.toMutableSet())
            }
        }

        fun applyPrimitives(graph: UiGraph) {
            for (node in graph.nodes) {
                if (node.type != "PrimitiveNode") continue
                val value = node.widgetsValues?.getOrNull(0) ?: continue
                for (out in node.outputs) for (linkId in out.links) {
                    val link = graph.links[linkId] ?: continue
                    val target = graph.byId[link.targetId] ?: continue
                    val widget = target.inputs.firstOrNull { it.link == linkId }?.widgetName ?: continue
                    pushed[Triple(graph, target.id, widget)] = value
                }
            }
        }

        // ---- widgets ----

        inner class Widgets(
            /** Every widget's value, including ones the prompt never sees (controls, upload). */
            val all: LinkedHashMap<String, JsonElement?>,
            /** Widgets whose value goes into the prompt, in frontend order. */
            val sent: LinkedHashMap<String, JsonElement>,
            val controls: LinkedHashMap<String, String>,
        )

        fun widgetsOf(dto: Dto): Widgets = widgetCache.getOrPut(dto.id) { layout(dto) }

        private fun layout(dto: Dto): Widgets {
            val all = LinkedHashMap<String, JsonElement?>()
            val sent = LinkedHashMap<String, JsonElement>()
            val controls = LinkedHashMap<String, String>()
            val def = dto.classDef ?: return Widgets(all, sent, controls)
            val src = dto.node.widgetsValues ?: JsonArray(emptyList())
            var i = 0
            fun next(default: JsonElement?): JsonElement {
                val v = if (i < src.size) src[i] else (default ?: JsonNull)
                i++
                return v
            }

            fun addExtras(after: String?) {
                adapters.extraWidgets[def.name]?.filter { it.after == after }?.forEach { extra ->
                    if (extra.repeats) {
                        var n = 1
                        while (i < src.size) {
                            val name = extra.name.replace("{n}", n++.toString())
                            val v = next(null); all[name] = v
                            if (extra.sendsValue) sent[name] = v
                        }
                    } else {
                        val v = next(null); all[extra.name] = v
                        if (extra.sendsValue) sent[extra.name] = v
                    }
                }
            }

            fun add(input: InputDef) {
                if (input.forceInput) return
                val wt = input.widgetType
                when {
                    wt == DYNAMIC_COMBO -> {
                        val options = (input.options["options"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                        val first = options.firstOrNull()?.get("key")
                        val v = next(first)
                        all[input.name] = v; sent[input.name] = v
                        val chosen = options.firstOrNull { (it["key"] as? JsonPrimitive)?.contentOrNull == (v as? JsonPrimitive)?.contentOrNull }
                        val sub = chosen?.get("inputs") as? JsonObject
                        for ((section, optional) in listOf("required" to false, "optional" to true)) {
                            val specs = sub?.get(section) as? JsonObject ?: continue
                            for ((k, spec) in specs) InputDef.parse("${input.name}.$k", spec, optional)?.let(::add)
                        }
                    }
                    input.isWidgetType -> {
                        val v = next(defaultOf(input))
                        all[input.name] = v; sent[input.name] = v
                        val control = input.controlAfterGenerate
                        if (control != null) {
                            val c = next(JsonPrimitive(control))
                            // The frontend names the control widget after the spec's string
                            // value when one is given ("fixed"), else "control_after_generate".
                            val spec = input.options["control_after_generate"] as? JsonPrimitive
                            all[if (spec != null && spec.isString) spec.content else "control_after_generate"] = c
                            controls[input.name] = (c as? JsonPrimitive)?.contentOrNull ?: control
                        }
                    }
                    wt in adapters.widgetTypes -> {
                        val rule = adapters.widgetTypes.getValue(wt)
                        val v = if (rule.slot) next(input.default ?: rule.default) else (rule.default ?: input.default ?: JsonNull)
                        all[input.name] = v
                        if (rule.sendsValue) sent[input.name] = v
                        if (!rule.replicable) diags += Diagnostic(
                            Severity.WARNING, Diagnostic.NOT_REPLICABLE, dto.id,
                            "${def.name}.${input.name}: the desktop computes this value when queueing",
                        )
                    }
                    input.type == AUTOGROW -> {
                        val template = ((input.options["template"] as? JsonObject)?.get("input") as? JsonObject)
                        val hasWidgets = template?.values?.any { sec ->
                            (sec as? JsonObject)?.values?.any { spec -> InputDef.parse("x", spec, false)?.isWidgetType == true } == true
                        } == true
                        if (hasWidgets) diags += Diagnostic(Severity.WARNING, Diagnostic.DYNAMIC_WIDGETS, dto.id, "Growing widget inputs on ${def.name}")
                    }
                    else -> Unit // a socket
                }
                addExtras(input.name)
            }

            def.inputs.forEach(::add)
            // The frontend adds an upload button after the first media combo; it holds a value slot.
            if (def.inputs.any { !it.optional && it.uploadKind != null && (it.uploadKind != "audio" || it.name == "audio") }) {
                all["upload"] = next(null)
            }
            addExtras(null)

            // Widgets a frontend extension added, which the server never described: newer
            // workflows name every saved value, so those can still be carried over faithfully.
            dto.node.widgetsValuesNamed?.let { named ->
                val unknown = named.keys.filter { it !in all && it != "control_after_generate" && it != "control_filter_list" }
                if (unknown.isNotEmpty()) {
                    unknown.forEach { k -> named[k]?.let { sent[k] = it; all[k] = it } }
                    if (i < src.size) i = src.size
                    diags += Diagnostic(Severity.INFO, Diagnostic.EXTENSION_WIDGETS, dto.id, "${def.name}: carried extension widgets ${unknown.joinToString()}")
                }
            }

            // PrimitiveNode pushes win over the node's own saved values.
            for ((name, _) in sent.toList()) {
                pushed[Triple(dto.graph, dto.node.id, name)]?.let { sent[name] = it; all[name] = it }
            }
            if (src.isNotEmpty() && i != src.size) {
                // Fewer saved values than widgets: the node gained inputs since the workflow was
                // saved, and the frontend fills the rest with defaults exactly as we do. More saved
                // values than widgets means widgets we cannot see — that one is not trusted.
                val surplus = src.size > i
                diags += Diagnostic(
                    if (surplus) Severity.WARNING else Severity.INFO,
                    if (surplus) Diagnostic.WIDGET_COUNT else Diagnostic.WIDGET_SHORTFALL, dto.id,
                    "${def.name}: expected $i widget values, workflow has ${src.size}",
                )
            }
            dto.node.widgetsValuesNamed?.let { named ->
                val drift = sent.filter { (k, v) -> k in named && named[k] != v }.keys
                if (drift.isNotEmpty()) diags += Diagnostic(
                    Severity.INFO, Diagnostic.WIDGET_DRIFT, dto.id,
                    "${def.name}: saved named values differ for ${drift.joinToString()}",
                )
            }
            return Widgets(all, sent, controls)
        }
    }

    companion object {
        const val DYNAMIC_COMBO = "COMFY_DYNAMICCOMBO_V3"
        const val AUTOGROW = "COMFY_AUTOGROW_V3"

        fun defaultOf(input: InputDef): JsonElement? = input.default ?: when (input.widgetType) {
            "COMBO" -> input.choices?.firstOrNull()
            "INT", "FLOAT" -> JsonPrimitive(input.min ?: 0.0)
            "STRING" -> JsonPrimitive("")
            "BOOLEAN" -> JsonPrimitive(false)
            else -> null
        }

        /** Array widget values are wrapped so the server does not read them as links. */
        fun wrapArray(v: JsonElement): JsonElement =
            if (v is JsonArray) buildJsonObject { put("__value__", v) } else v

        /** LiteGraph.isValidConnection: wildcards, case-insensitive, comma-separated unions. */
        fun validConnection(a: String, b: String): Boolean {
            if (a.isEmpty() || b.isEmpty() || a == "*" || b == "*" || a == "0" || b == "0") return true
            val x = a.lowercase(); val y = b.lowercase()
            if (x == y) return true
            val xs = x.split(','); val ys = y.split(',')
            return xs.any { it in ys }
        }
    }
}
