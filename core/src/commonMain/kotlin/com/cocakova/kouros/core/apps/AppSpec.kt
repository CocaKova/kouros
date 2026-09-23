package com.cocakova.kouros.core.apps

import com.cocakova.kouros.core.api.ObjectInfo
import com.cocakova.kouros.core.form.FormLayout
import com.cocakova.kouros.core.form.ModelNeeds
import com.cocakova.kouros.core.graph.WorkflowFormat
import com.cocakova.kouros.core.ws.dbl
import com.cocakova.kouros.core.ws.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A one-purpose tool: a prompt the server can run, the handful of controls worth showing, and
 * what the server needs installed before it will run at all.
 *
 * An app is data. Nothing about any node pack is compiled in — a spec names the classes it
 * needs and the files they load, and the server's own `/object_info` and model folders decide
 * whether it is ready. The same shape describes a shipped app and one made here from a saved
 * workflow.
 */
data class AppSpec(
    val id: String,
    val title: String,
    val tagline: String,
    /** The label on the button that starts a run ("Upscale"). */
    val action: String,
    /** A name from the icon vocabulary the UI draws; an unknown one falls back to the app mark. */
    val icon: String,
    /** What it makes, as an [com.cocakova.kouros.core.form.OutputKind] name. */
    val kind: String,
    /** A paragraph for the app's own page: what it does, and what it costs to run. */
    val about: String?,
    /** The API prompt it runs, as `/prompt` takes it. */
    val prompt: JsonObject,
    /** Which fields sit up top, which plumbing is out of sight, what things are called. */
    val layout: FormLayout,
    val packs: List<NodePack>,
    val models: List<AppModel>,
) {
    /** The model links in the shape the run screen's missing-model panel already reads. */
    fun modelLinks(): Map<String, ModelNeeds.Link> =
        models.associate { it.name to ModelNeeds.Link(it.url, it.directory) }

    /**
     * What this server is still missing. [filesByFolder] is the server's own listing per model
     * folder ("checkpoints" → the files it offers); a folder that was not listed is treated as
     * unknown, and its models are not reported missing on a guess.
     */
    fun needs(objectInfo: ObjectInfo, filesByFolder: Map<String, Set<String>>): AppNeeds = AppNeeds(
        packs = packs.filter { pack -> pack.classes.any { objectInfo[it] == null } },
        models = models.filter { m ->
            val have = filesByFolder[m.directory] ?: return@filter false
            have.none { it.substringAfterLast('/').equals(m.name, ignoreCase = true) }
        },
    )

    /** The model folders [needs] wants listed. */
    val modelFolders: Set<String> get() = models.mapTo(LinkedHashSet()) { it.directory }

    companion object {
        fun parse(o: JsonObject): AppSpec? {
            val id = o.str("id")?.takeIf { ID.matches(it) } ?: return null
            val prompt = o["prompt"] as? JsonObject ?: return null
            if (!WorkflowFormat.isApiPrompt(prompt)) return null
            return AppSpec(
                id = id,
                title = o.str("title") ?: id,
                tagline = o.str("tagline") ?: "",
                action = o.str("action") ?: "Run",
                icon = o.str("icon") ?: "",
                kind = o.str("kind") ?: "IMAGE",
                about = o.str("about"),
                prompt = prompt,
                layout = (o["form"] as? JsonObject)?.let { FormLayout.decode(it.toString()) } ?: FormLayout(),
                packs = (o["packs"] as? JsonArray).orEmpty().mapNotNull { NodePack.parse(it as? JsonObject ?: return@mapNotNull null) },
                models = (o["models"] as? JsonArray).orEmpty().mapNotNull { AppModel.parse(it as? JsonObject ?: return@mapNotNull null) },
            )
        }

        /** Kept plain: an app's id is a file name, a workflow path and a database key. */
        private val ID = Regex("""^[a-z0-9][a-z0-9-]{0,62}$""")
    }
}

/**
 * A node pack an app needs, named the way the server's manager knows it. [classes] is how its
 * absence is detected — if the server can't describe them, the pack isn't installed.
 */
data class NodePack(
    val id: String,
    val title: String,
    val repository: String,
    val classes: List<String>,
) {
    companion object {
        fun parse(o: JsonObject): NodePack? {
            val id = o.str("id") ?: return null
            val repo = o.str("repository")?.takeIf { it.startsWith("https://") } ?: return null
            val classes = (o["classes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (classes.isEmpty()) return null
            return NodePack(id, o.str("title") ?: id, repo, classes)
        }
    }
}

/** A model file an app loads, and where the server can fetch it from. */
data class AppModel(
    val name: String,
    val directory: String,
    val url: String?,
    /** Download size in bytes, for the setup screen's arithmetic. */
    val size: Long?,
) {
    companion object {
        fun parse(o: JsonObject): AppModel? {
            val name = o.str("name")?.takeIf { '/' !in it && '\\' !in it } ?: return null
            val dir = o.str("directory") ?: return null
            return AppModel(name, dir, o.str("url")?.takeIf { it.startsWith("https://") }, o.dbl("size")?.toLong())
        }
    }
}

/** What a server is still missing before an app can run. */
data class AppNeeds(val packs: List<NodePack>, val models: List<AppModel>) {
    val met: Boolean get() = packs.isEmpty() && models.isEmpty()
    /** Bytes still to fetch, when every missing model declares its size. */
    val downloadSize: Long? get() = models.map { it.size ?: return null }.sum()
}

/** The apps this build ships, read from one file so adding an app is adding data. */
object AppCatalog {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): List<AppSpec> = runCatching {
        val root = json.parseToJsonElement(text)
        val list = (root as? JsonObject)?.get("apps") as? JsonArray ?: root as? JsonArray ?: return emptyList()
        list.mapNotNull { AppSpec.parse(it as? JsonObject ?: return@mapNotNull null) }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    /** The spec as it is stored for an app made here, so a made app reloads like a shipped one. */
    fun encode(spec: AppSpec): String = buildString {
        append('{')
        append(""""id":${q(spec.id)},"title":${q(spec.title)},"tagline":${q(spec.tagline)},""")
        append(""""action":${q(spec.action)},"icon":${q(spec.icon)},"kind":${q(spec.kind)},""")
        spec.about?.let { append(""""about":${q(it)},""") }
        append(""""form":${spec.layout.encode()},"prompt":${spec.prompt},""")
        append(""""packs":[${spec.packs.joinToString(",") { p ->
            """{"id":${q(p.id)},"title":${q(p.title)},"repository":${q(p.repository)},"classes":[${p.classes.joinToString(",") { q(it) }}]}"""
        }}],""")
        append(""""models":[${spec.models.joinToString(",") { m ->
            """{"name":${q(m.name)},"directory":${q(m.directory)}${m.url?.let { ""","url":${q(it)}""" } ?: ""}${m.size?.let { ""","size":$it""" } ?: ""}}"""
        }}]""")
        append('}')
    }

    private fun q(s: String): String = JsonPrimitive(s).toString()
}
