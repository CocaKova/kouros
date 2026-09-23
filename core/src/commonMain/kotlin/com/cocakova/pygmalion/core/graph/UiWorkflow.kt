package com.cocakova.pygmalion.core.graph

import com.cocakova.pygmalion.core.ws.asId
import com.cocakova.pygmalion.core.ws.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** LiteGraph node modes that matter to execution. */
object NodeMode {
    const val ALWAYS = 0
    const val NEVER = 2 // "muted"
    const val BYPASS = 4
}

data class UiLink(
    val id: Int,
    val originId: String,
    val originSlot: Int,
    val targetId: String,
    val targetSlot: Int,
    val type: String,
)

data class UiInput(val name: String, val type: String, val link: Int?, val widgetName: String?, val label: String?)

data class UiOutput(val name: String, val type: String, val links: List<Int>)

class UiNode(
    val id: String,
    val type: String,
    val mode: Int,
    val title: String?,
    val inputs: List<UiInput>,
    val outputs: List<UiOutput>,
    /** Positional widget values, as the frontend restores them by default. */
    val widgetsValues: JsonArray?,
    /** Name → value (newer frontends also write this); used to detect drift. */
    val widgetsValuesNamed: JsonObject?,
    val properties: JsonObject,
    val raw: JsonObject,
) {
    fun input(name: String): UiInput? = inputs.firstOrNull { it.name == name }
}

class UiGraph(val nodes: List<UiNode>, val links: Map<Int, UiLink>) {
    val byId: Map<String, UiNode> = nodes.associateBy { it.id }
}

data class SubgraphPort(val name: String, val type: String, val linkIds: List<Int>, val label: String?)

class SubgraphDef(
    val id: String,
    val name: String,
    val graph: UiGraph,
    val inputs: List<SubgraphPort>,
    val outputs: List<SubgraphPort>,
    val inputNodeId: String,
    val outputNodeId: String,
)

/**
 * A workflow as the ComfyUI frontend saves it (the "UI" format: nodes, links, subgraph
 * definitions, layout). Parsed leniently — unknown fields are kept in [raw] and ignored.
 */
class UiWorkflow(
    val raw: JsonObject,
    val id: String?,
    val root: UiGraph,
    val subgraphs: Map<String, SubgraphDef>,
    val extra: JsonObject,
) {
    /** The desktop frontend's own "app mode" pick: `[[widgetKey, label], …]`, if saved. */
    val linearInputs: List<Pair<String, String?>>
        get() = ((extra["linearData"] as? JsonObject)?.get("inputs") as? JsonArray)?.mapNotNull { e ->
            val a = e as? JsonArray ?: return@mapNotNull null
            val key = (a.getOrNull(0) as? JsonPrimitive)?.content ?: return@mapNotNull null
            key to (a.getOrNull(1) as? JsonPrimitive)?.content
        } ?: emptyList()

    /** An API prompt some tools embed alongside the graph (`extra.prompt`). */
    val embeddedPrompt: JsonObject? get() = extra["prompt"] as? JsonObject
}

object WorkflowFormat {
    /** A saved UI workflow: has a `nodes` array. */
    fun isUiWorkflow(o: JsonObject): Boolean = o["nodes"] is JsonArray

    /** An API prompt: every value is an object with `class_type` and `inputs`. */
    fun isApiPrompt(o: JsonObject): Boolean =
        o.isNotEmpty() && o.values.all { v -> v is JsonObject && v["class_type"] is JsonPrimitive && v["inputs"] is JsonObject }

    fun parse(o: JsonObject): UiWorkflow {
        val rawDefs = ((o["definitions"] as? JsonObject)?.get("subgraphs") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val remaps = dedupeRemaps(o, rawDefs)
        val defs = rawDefs.map { parseSubgraph(it, remaps[it.str("id")] ?: emptyMap()) }.associateBy { it.id }
        return UiWorkflow(
            raw = o,
            id = o.str("id"),
            root = parseGraph(o),
            subgraphs = defs,
            extra = o["extra"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    /**
     * The frontend's load-time node-id deduplication (LiteGraph `deduplicateSubgraphNodeIds`):
     * walking subgraph definitions in order, any inner node whose id is already taken — by a root
     * node or by an earlier definition — is renumbered from `last_node_id` upward. Execution ids
     * (and therefore the ids the server reports while running) are built from the renumbered
     * ids, so the compiler must renumber identically.
     */
    private fun dedupeRemaps(root: JsonObject, defs: List<JsonObject>): Map<String, Map<String, String>> {
        if (defs.isEmpty()) return emptyMap()
        var last = maxOf(
            (root["last_node_id"] as? JsonPrimitive)?.intOrNull ?: 0,
            ((root["state"] as? JsonObject)?.get("lastNodeId") as? JsonPrimitive)?.intOrNull ?: 0,
        ).toLong()
        val usedNums = HashSet<Long>()
        val usedKeys = HashSet<String>()
        (root["nodes"] as? JsonArray)?.forEach { n ->
            val id = ((n as? JsonObject)?.get("id") as? JsonPrimitive) ?: return@forEach
            if (!id.isString) id.content.toLongOrNull()?.let { usedNums += it; usedKeys += it.toString() }
        }
        val out = HashMap<String, Map<String, String>>()
        for (def in defs) {
            val remap = HashMap<String, String>()
            (def["nodes"] as? JsonArray)?.forEach { n ->
                val key = (n as? JsonObject)?.get("id")?.asId() ?: return@forEach
                if (key in usedKeys) {
                    while (true) { last++; if (last !in usedNums) break }
                    remap[key] = last.toString()
                    usedNums += last; usedKeys += last.toString()
                } else {
                    usedKeys += key
                    key.toLongOrNull()?.takeIf { it.toString() == key }?.let { num ->
                        usedNums += num
                        if (num > last) last = num
                    }
                }
            }
            if (remap.isNotEmpty()) out[def.str("id") ?: ""] = remap
        }
        return out
    }

    private fun parseSubgraph(o: JsonObject, remap: Map<String, String>): SubgraphDef = SubgraphDef(
        id = o.str("id") ?: "",
        name = o.str("name") ?: "Subgraph",
        graph = parseGraph(o, remap),
        inputs = ports(o["inputs"]),
        outputs = ports(o["outputs"]),
        inputNodeId = (o["inputNode"] as? JsonObject)?.get("id")?.asId() ?: "-10",
        outputNodeId = (o["outputNode"] as? JsonObject)?.get("id")?.asId() ?: "-20",
    )

    private fun ports(e: JsonElement?): List<SubgraphPort> = (e as? JsonArray)?.mapNotNull { p ->
        val o = p as? JsonObject ?: return@mapNotNull null
        SubgraphPort(
            name = o.str("name") ?: return@mapNotNull null,
            type = o.str("type") ?: "*",
            linkIds = (o["linkIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList(),
            label = o.str("label"),
        )
    } ?: emptyList()

    private fun parseGraph(o: JsonObject, remap: Map<String, String> = emptyMap()): UiGraph {
        val nodes = (o["nodes"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { n -> parseNode(n, remap) } } ?: emptyList()
        val links = (o["links"] as? JsonArray)?.mapNotNull(::parseLink)
            ?.map { l -> if (remap.isEmpty()) l else l.copy(originId = remap[l.originId] ?: l.originId, targetId = remap[l.targetId] ?: l.targetId) }
            ?.associateBy { it.id } ?: emptyMap()
        return UiGraph(nodes, links)
    }

    private fun parseNode(o: JsonObject, remap: Map<String, String>): UiNode? {
        val rawId = o["id"]?.asId() ?: return null
        val id = remap[rawId] ?: rawId
        return UiNode(
            id = id,
            type = o.str("type") ?: "",
            mode = (o["mode"] as? JsonPrimitive)?.intOrNull ?: NodeMode.ALWAYS,
            title = o.str("title"),
            inputs = (o["inputs"] as? JsonArray)?.mapNotNull { i ->
                val io = i as? JsonObject ?: return@mapNotNull null
                UiInput(
                    name = io.str("name") ?: return@mapNotNull null,
                    type = io["type"].typeString(),
                    link = (io["link"] as? JsonPrimitive)?.intOrNull,
                    widgetName = (io["widget"] as? JsonObject)?.str("name"),
                    label = io.str("label"),
                )
            } ?: emptyList(),
            outputs = (o["outputs"] as? JsonArray)?.mapNotNull { out ->
                val oo = out as? JsonObject ?: return@mapNotNull null
                UiOutput(
                    name = oo.str("name") ?: "",
                    type = oo["type"].typeString(),
                    links = (oo["links"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList(),
                )
            } ?: emptyList(),
            widgetsValues = o["widgets_values"] as? JsonArray,
            widgetsValuesNamed = (o["widgets_values_named"] as? JsonObject)
                ?: (o["widgets_values"] as? JsonObject),
            properties = o["properties"] as? JsonObject ?: JsonObject(emptyMap()),
            raw = o,
        )
    }

    private fun parseLink(e: JsonElement): UiLink? = when (e) {
        is JsonArray -> {
            val id = (e.getOrNull(0) as? JsonPrimitive)?.intOrNull
            val oid = e.getOrNull(1)?.asId()
            val os = (e.getOrNull(2) as? JsonPrimitive)?.intOrNull
            val tid = e.getOrNull(3)?.asId()
            val ts = (e.getOrNull(4) as? JsonPrimitive)?.intOrNull
            if (id == null || oid == null || os == null || tid == null || ts == null) null
            else UiLink(id, oid, os, tid, ts, e.getOrNull(5).typeString())
        }
        is JsonObject -> {
            val id = (e["id"] as? JsonPrimitive)?.intOrNull
            val oid = e["origin_id"]?.asId()
            val os = (e["origin_slot"] as? JsonPrimitive)?.intOrNull
            val tid = e["target_id"]?.asId()
            val ts = (e["target_slot"] as? JsonPrimitive)?.intOrNull
            if (id == null || oid == null || os == null || tid == null || ts == null) null
            else UiLink(id, oid, os, tid, ts, e["type"].typeString())
        }
        else -> null
    }

    private fun JsonElement?.typeString(): String = when (this) {
        is JsonPrimitive -> content
        is JsonArray -> "COMBO"
        else -> "*"
    }
}
