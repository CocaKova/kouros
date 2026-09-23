package com.cocakova.kouros.core.api

import com.cocakova.kouros.core.ws.dbl
import com.cocakova.kouros.core.ws.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * One workflow template from the server's own gallery (`/templates/index.json`, served by the
 * ComfyUI frontend package) — what the desktop's "Browse templates" shows.
 */
data class TemplateEntry(
    /** File stem: the workflow is `/templates/<name>.json`, its thumbnail `/templates/<name>-1.<ext>`. */
    val name: String,
    val title: String,
    val description: String,
    /** The gallery group it sits in ("Image", "Video", "Product & Ads"…). */
    val group: String,
    /** "image", "video", "audio", "3d", "llm"… from the group. */
    val kind: String,
    val tags: List<String>,
    val models: List<String>,
    /** Total download size of the models it needs, in bytes, when the index says. */
    val size: Long?,
    /** False for templates built on paid cloud API nodes, which need an account with Comfy. */
    val runsLocally: Boolean,
    val thumbnailExt: String,
    val thumbnailIsImage: Boolean,
    val date: String?,
    val usage: Long,
) {
    val workflowPath: String get() = "/templates/$name.json"
    val thumbnailPath: String get() = "/templates/$name-1.$thumbnailExt"
}

object TemplateCatalog {
    /** Parses the index. Groups keep the server's order; templates keep theirs within a group. */
    fun parse(index: JsonElement): List<TemplateEntry> {
        val groups = index as? JsonArray ?: return emptyList()
        return groups.flatMap { g ->
            val group = g as? JsonObject ?: return@flatMap emptyList()
            val title = group.str("title") ?: group.str("moduleName") ?: "Templates"
            val kind = group.str("type") ?: "image"
            (group["templates"] as? JsonArray).orEmpty().mapNotNull { e ->
                val t = e as? JsonObject ?: return@mapNotNull null
                val name = t.str("name") ?: return@mapNotNull null
                val subtype = t.str("mediaSubtype") ?: "webp"
                TemplateEntry(
                    name = name,
                    title = t.str("title") ?: name,
                    description = t.str("description") ?: "",
                    group = title,
                    kind = kind,
                    tags = t.strings("tags"),
                    models = t.strings("models"),
                    size = t.dbl("size")?.toLong()?.takeIf { it > 0 },
                    runsLocally = (t["openSource"] as? JsonPrimitive)?.booleanOrNull != false,
                    thumbnailExt = subtype,
                    thumbnailIsImage = (t.str("mediaType") ?: "image") == "image",
                    date = t.str("date"),
                    usage = t.dbl("usage")?.toLong() ?: 0,
                )
            }
        }.distinctBy { it.name }
    }

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
}
