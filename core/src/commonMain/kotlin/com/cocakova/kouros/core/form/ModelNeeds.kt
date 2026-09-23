package com.cocakova.kouros.core.form

import com.cocakova.kouros.core.compile.PromptValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A model file a workflow asks for that the server doesn't have, and where to get it. */
data class ModelNeed(
    val name: String,
    /** The server's model folder it belongs in ("diffusion_models", "vae"…), when known. */
    val directory: String?,
    /** A download link, when the workflow carries one (official templates do). */
    val url: String?,
)

/**
 * Workflows saved by the frontend can carry, on each loader node, `properties.models`:
 * `[{name, url, directory}]` — the frontend's "missing models" dialog reads the same thing.
 * This pairs those links with the files the validator found missing.
 */
object ModelNeeds {
    data class Link(val url: String?, val directory: String?)

    /** Every model link in a saved workflow, root graph and subgraph definitions alike, by file name. */
    fun links(workflow: JsonObject): Map<String, Link> {
        val out = LinkedHashMap<String, Link>()
        fun scan(nodes: JsonArray?) = nodes?.forEach { n ->
            val models = ((n as? JsonObject)?.get("properties") as? JsonObject)?.get("models") as? JsonArray ?: return@forEach
            for (m in models) {
                val o = m as? JsonObject ?: continue
                val name = o.s("name") ?: continue
                val link = Link(o.s("url")?.takeIf { it.startsWith("https://") }, o.s("directory"))
                val key = baseName(name)
                out[key] = out[key]?.let { a -> Link(a.url ?: link.url, a.directory ?: link.directory) } ?: link
            }
        }
        scan(workflow["nodes"] as? JsonArray)
        ((workflow["definitions"] as? JsonObject)?.get("subgraphs") as? JsonArray)?.forEach { sg ->
            scan((sg as? JsonObject)?.get("nodes") as? JsonArray)
        }
        return out
    }

    /**
     * The model files among [issues] (values the server doesn't offer), with their links. A
     * missing value that the workflow never declared as a model is still listed when it looks
     * like a model file, so hand-made workflows get the list too, without links.
     */
    fun missing(issues: List<PromptValidator.Issue>, links: Map<String, Link>): List<ModelNeed> =
        issues.asSequence()
            .filter { it.kind == PromptValidator.Kind.BAD_CHOICE }
            .mapNotNull { it.value }
            .filter { baseName(it) in links || MODEL_FILE.containsMatchIn(it) }
            .distinctBy { baseName(it) }
            .map { v -> links[baseName(v)].let { l -> ModelNeed(v, l?.directory, l?.url) } }
            .toList()

    private fun baseName(path: String) = path.replace('\\', '/').substringAfterLast('/')
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private val MODEL_FILE = Regex("""\.(safetensors|ckpt|pt|pth|bin|gguf|sft|onnx)$""", RegexOption.IGNORE_CASE)
}
