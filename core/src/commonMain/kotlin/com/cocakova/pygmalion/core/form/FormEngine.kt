package com.cocakova.pygmalion.core.form

import com.cocakova.pygmalion.core.api.InputDef
import com.cocakova.pygmalion.core.api.ObjectInfo
import com.cocakova.pygmalion.core.compile.ApiRef
import com.cocakova.pygmalion.core.compile.CompiledWorkflow
import com.cocakova.pygmalion.core.graph.UiWorkflow
import com.cocakova.pygmalion.core.ws.bool
import com.cocakova.pygmalion.core.ws.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** What a field is for, which decides how the phone presents it. */
enum class FieldRole { PROMPT, NEGATIVE_PROMPT, MEDIA, SEED, DIMENSION, LENGTH, QUALITY, SAMPLER, MODEL, STRENGTH, TOGGLE, TEXT, NUMBER, CHOICE, OTHER }

/** How a field was chosen for the form, strongest first. */
enum class FieldOrigin { APP_MODE, SUBGRAPH, SCORED }

/**
 * One control on the phone form. It writes to every prompt input in [targets] (a promoted
 * subgraph widget can feed several inner nodes), so the form edits the API prompt directly.
 */
data class FormField(
    /** Stable across compiles of the same workflow: "<execPath>:<widget>". */
    val key: String,
    val label: String,
    val group: String,
    val role: FieldRole,
    val spec: InputDef,
    val targets: List<ApiRef>,
    val initial: JsonElement,
    val score: Int,
    val origin: FieldOrigin,
    /** control_after_generate for seed-like fields: fixed / increment / decrement / randomize. */
    val control: String? = null,
)

data class Form(val hero: List<FormField>, val advanced: List<FormField>, val outputNodes: List<String>) {
    val all: List<FormField> get() = hero + advanced
}

/**
 * Scoring rules, as data. Each rule matches on the widget's type, name or flags and assigns a
 * role and a weight. The defaults are generic (they know "a multiline string is a prompt", not
 * any node's name); a user or a rules feed can replace them.
 */
class FieldRules(val rules: List<Rule>) {
    data class Rule(
        val role: FieldRole,
        val score: Int,
        val types: Set<String>? = null,
        val names: Regex? = null,
        val context: Regex? = null,
        val multiline: Boolean? = null,
        val upload: Boolean? = null,
        val control: Boolean? = null,
        val fileChoices: Boolean? = null,
    )

    fun classify(spec: InputDef, context: String): Pair<FieldRole, Int> {
        for (r in rules) {
            if (r.types != null && spec.widgetType !in r.types) continue
            if (r.names != null && !r.names.containsMatchIn(spec.name.substringAfterLast('.'))) continue
            if (r.context != null && !r.context.containsMatchIn(context)) continue
            if (r.multiline != null && spec.multiline != r.multiline) continue
            if (r.upload != null && (spec.uploadKind != null) != r.upload) continue
            if (r.control != null && (spec.controlAfterGenerate != null) != r.control) continue
            if (r.fileChoices != null && looksLikeFiles(spec) != r.fileChoices) continue
            return r.role to r.score
        }
        return FieldRole.OTHER to 0
    }

    private fun looksLikeFiles(spec: InputDef): Boolean =
        spec.choices?.take(20)?.any { c -> (c as? JsonPrimitive)?.contentOrNull?.let { FILE_EXT.containsMatchIn(it) } == true } == true

    companion object {
        private val FILE_EXT = Regex("""\.(safetensors|ckpt|pt|pth|bin|gguf|sft|onnx)$""", RegexOption.IGNORE_CASE)
        private val NEG = Regex("""negative|neg\b|avoid""", RegexOption.IGNORE_CASE)
        private val CODE = Regex("""^(expression|code|script|json|template|regex|pattern|delimiter|formula)$|\b(expression|math|script|code|json|regex)\b""", RegexOption.IGNORE_CASE)
        private val SEED = Regex("""(^|[^a-z])(noise_)?seed([^a-z]|$)""", RegexOption.IGNORE_CASE)
        private val DIM = Regex("""^(width|height|aspect_ratio|megapixels|resolution|size)$""", RegexOption.IGNORE_CASE)
        private val DIM_CTX = Regex("""^(width|height|aspect|megapixels|resolution)\b""", RegexOption.IGNORE_CASE)
        private val LEN = Regex("""^(length|frames|frame_count|num_frames|frames_number|duration|seconds|max_duration|fps|frame_rate)$""", RegexOption.IGNORE_CASE)
        private val LEN_CTX = Regex("""^(length|frames?|duration|seconds|fps|frame rate)\b""", RegexOption.IGNORE_CASE)

        val DEFAULT = FieldRules(
            listOf(
                Rule(FieldRole.MEDIA, 95, upload = true),
                // Multiline strings that are code, not prose.
                Rule(FieldRole.TEXT, 20, types = setOf("STRING"), names = CODE),
                Rule(FieldRole.TEXT, 20, types = setOf("STRING"), context = CODE),
                Rule(FieldRole.NEGATIVE_PROMPT, 88, types = setOf("STRING"), multiline = true, names = NEG),
                Rule(FieldRole.NEGATIVE_PROMPT, 88, types = setOf("STRING"), multiline = true, context = NEG),
                Rule(FieldRole.PROMPT, 100, types = setOf("STRING"), multiline = true),
                Rule(FieldRole.SEED, 80, types = setOf("INT"), names = SEED),
                Rule(FieldRole.SEED, 80, types = setOf("INT"), context = SEED),
                Rule(FieldRole.DIMENSION, 70, types = setOf("INT", "FLOAT", "COMBO"), names = DIM),
                Rule(FieldRole.DIMENSION, 70, types = setOf("INT", "FLOAT", "COMBO"), context = DIM_CTX),
                Rule(FieldRole.LENGTH, 68, types = setOf("INT", "FLOAT"), names = LEN),
                Rule(FieldRole.LENGTH, 68, types = setOf("INT", "FLOAT"), context = LEN_CTX),
                Rule(FieldRole.QUALITY, 62, types = setOf("INT", "FLOAT"), names = Regex("""^(steps|cfg|denoise|guidance|shift)$""", RegexOption.IGNORE_CASE)),
                Rule(FieldRole.STRENGTH, 50, types = setOf("FLOAT"), names = Regex("""strength""", RegexOption.IGNORE_CASE)),
                Rule(FieldRole.SAMPLER, 40, types = setOf("COMBO"), names = Regex("""^(sampler_name|scheduler|sampler)$""", RegexOption.IGNORE_CASE)),
                Rule(FieldRole.MODEL, 30, types = setOf("COMBO"), fileChoices = true),
                Rule(FieldRole.TEXT, 35, types = setOf("STRING"), names = Regex("""^(text|prompt|caption|lyrics|tags)$""", RegexOption.IGNORE_CASE)),
                Rule(FieldRole.TOGGLE, 12, types = setOf("BOOLEAN")),
                Rule(FieldRole.NUMBER, 10, types = setOf("INT", "FLOAT")),
                Rule(FieldRole.CHOICE, 10, types = setOf("COMBO")),
                Rule(FieldRole.TEXT, 8, types = setOf("STRING")),
            ),
        )

        /** Plumbing that nobody wants on a phone form. */
        val HIDDEN_NAMES = Regex("""^(filename_prefix|format\..*|save_output|frame_rate_override|pingpong|crf|codec|save_metadata)$""")
    }
}

/**
 * Builds the phone form for a compiled workflow. Choice of fields, strongest source first:
 *  1. the workflow's own app mode (`extra.linearData`) — the desktop user's explicit pick;
 *  2. widgets promoted onto root-level subgraphs — the workflow author's curation;
 *  3. every other widget, scored by [FieldRules].
 * Hero fields are the best few; everything else goes to "Advanced", grouped by node.
 */
class FormEngine(private val objectInfo: ObjectInfo, private val rules: FieldRules = FieldRules.DEFAULT) {

    fun build(compiled: CompiledWorkflow, workflow: UiWorkflow?, heroCount: Int = 6): Form {
        val prompt = compiled.prompt
        val fields = LinkedHashMap<String, FormField>()
        val coveredTargets = HashSet<ApiRef>()

        fun specFor(ref: ApiRef): InputDef? {
            val node = prompt[ref.nodeId] as? JsonObject ?: return null
            val cls = node.str("class_type") ?: return null
            val def = objectInfo[cls] ?: return null
            return def.input(ref.input) ?: dynamicSpec(def.inputs, ref.input, node["inputs"] as? JsonObject)
        }
        fun titleOf(nodeId: String): String =
            ((prompt[nodeId] as? JsonObject)?.get("_meta") as? JsonObject)?.str("title")
                ?: (prompt[nodeId] as? JsonObject)?.str("class_type") ?: nodeId
        fun valueAt(ref: ApiRef): JsonElement? = ((prompt[ref.nodeId] as? JsonObject)?.get("inputs") as? JsonObject)?.get(ref.input)

        fun add(key: String, targets: List<ApiRef>, origin: FieldOrigin, label: String?, group: String?, boost: Int) {
            if (key in fields || targets.isEmpty()) return
            val first = targets.first()
            val spec = specFor(first) ?: return
            val v = valueAt(first) ?: return
            if (v is JsonArray) return // now a link: not a user value
            val shown = label ?: spec.displayName ?: humanize(spec.name)
            // Context starts with the visible label, so rules anchored at ^ read the label a
            // workflow author gave a promoted input ("Frames"), not the inner widget ("value").
            val context = shown + " · " + titleOf(first.nodeId)
            val (role, score) = rules.classify(spec, context)
            fields[key] = FormField(
                key = key,
                label = shown,
                group = group ?: titleOf(first.nodeId),
                role = role,
                spec = spec,
                targets = targets,
                initial = v,
                score = score + boost,
                origin = origin,
                control = compiled.seedControls[first],
            )
            coveredTargets += targets
        }

        // 1. App mode, as the desktop saved it.
        workflow?.linearInputs?.forEach { (locator, name) ->
            val key = linearKey(locator, name, workflow.id) ?: return@forEach
            val targets = compiled.promoted[key] ?: compiled.widgetRefs[key]?.let(::listOf) ?: return@forEach
            add(key, targets, FieldOrigin.APP_MODE, name?.let(::humanize), "App", 1000)
        }
        // 2. Subgraph promotions: root-level instances' exposed widgets, labelled as the author
        //    labelled the subgraph's inputs. A small boost: they are curated, but a model picker
        //    still should not push the prompt off the first screen.
        for ((key, targets) in compiled.promoted) {
            if (key.count { it == ':' } != 1) continue // only root-level instances
            val (instId, port) = key.split(':')
            val inst = workflow?.root?.byId?.get(instId)
            val def = inst?.let { workflow.subgraphs[it.type] }
            val label = inst?.input(port)?.label ?: def?.inputs?.firstOrNull { it.name == port }?.label
            add(key, targets, FieldOrigin.SUBGRAPH, humanize(label ?: port), def?.name, 20)
        }
        // 3. Everything else, scored.
        for ((key, ref) in compiled.widgetRefs) {
            if (ref in coveredTargets) continue
            val spec = specFor(ref) ?: continue
            if (spec.hidden || FieldRules.HIDDEN_NAMES.matches(spec.name)) continue
            add(key, listOf(ref), FieldOrigin.SCORED, null, null, 0)
        }

        val ranked = fields.values.sortedWith(compareByDescending<FormField> { it.score }.thenBy { it.key })
        val appMode = ranked.filter { it.origin == FieldOrigin.APP_MODE }
        val hero = if (appMode.isNotEmpty()) appMode
            else ranked.filter { it.score >= HERO_FLOOR }.take(heroCount)
        val advanced = ranked.filter { it !in hero }.sortedWith(compareBy<FormField> { it.group }.thenByDescending { it.score })
        val outputs = ((workflow?.extra?.get("linearData") as? JsonObject)?.get("outputs") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        return Form(hero, advanced, outputs)
    }

    /**
     * The desktop writes app-mode entries as `[locator, widget]`, where the locator is a node id
     * or `<graphId>:<nodePath…>:<widget>`. Normalize to our field key "<execPath>:<widget>".
     */
    private fun linearKey(locator: String, widget: String?, workflowId: String?): String? {
        var parts = locator.split(':')
        if (parts.size > 1 && (parts[0] == workflowId || UUID.matches(parts[0]))) parts = parts.drop(1)
        val w = widget ?: parts.lastOrNull() ?: return null
        if (parts.size > 1 && parts.last() == w) parts = parts.dropLast(1)
        if (parts.isEmpty()) return null
        return parts.joinToString(":") + ":" + w
    }

    private fun dynamicSpec(inputs: List<InputDef>, name: String, values: JsonObject?): InputDef? {
        if ('.' !in name) return null
        val head = name.substringBefore('.')
        val parent = inputs.firstOrNull { it.name == head } ?: return null
        val chosen = (values?.get(head) as? JsonPrimitive)?.contentOrNull ?: return null
        val option = (parent.options["options"] as? JsonArray)?.firstOrNull {
            ((it as? JsonObject)?.get("key") as? JsonPrimitive)?.contentOrNull == chosen
        } as? JsonObject ?: return null
        val sub = option["inputs"] as? JsonObject ?: return null
        val rest = name.substringAfter('.')
        for ((section, optional) in listOf("required" to false, "optional" to true)) {
            val specs = sub[section] as? JsonObject ?: continue
            specs[rest.substringBefore('.')]?.let { s ->
                val d = InputDef.parse("$head.${rest.substringBefore('.')}", s, optional) ?: return@let
                return if ('.' in rest) dynamicSpec(listOf(d), name, values) else d
            }
        }
        return null
    }

    companion object {
        const val HERO_FLOOR = 60
        private val UUID = Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""")

        fun humanize(name: String): String =
            name.substringAfterLast('.').replace('_', ' ').replace(Regex("""\s+\d+$"""), "").trim()
                .replaceFirstChar { it.uppercase() }

        /** Writes form values into a copy of [prompt]. Values are applied to every target. */
        fun apply(prompt: JsonObject, values: Map<FormField, JsonElement>): JsonObject {
            if (values.isEmpty()) return prompt
            val byNode = HashMap<String, MutableMap<String, JsonElement>>()
            for ((field, v) in values) for (t in field.targets) byNode.getOrPut(t.nodeId) { mutableMapOf() }[t.input] = v
            return JsonObject(
                prompt.mapValues { (id, n) ->
                    val patch = byNode[id] ?: return@mapValues n
                    val node = n as JsonObject
                    val inputs = (node["inputs"] as? JsonObject ?: JsonObject(emptyMap())) + patch
                    JsonObject(node + ("inputs" to JsonObject(inputs)))
                },
            )
        }

        /**
         * The frontend's seed control, applied after a queue: what the value becomes for the
         * *next* run. `fixed` leaves it; `randomize` draws within the spec's range.
         */
        fun nextSeed(current: JsonElement, control: String?, spec: InputDef, random: kotlin.random.Random = kotlin.random.Random): JsonElement {
            val v = (current as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return current
            val min = spec.min ?: 0.0
            val max = minOf(spec.max ?: 1125899906842624.0, 1125899906842624.0)
            val step = spec.step?.takeIf { it > 0 } ?: 1.0
            val next = when (control) {
                "increment" -> minOf(v + step, max)
                "increment-wrap" -> if (v + step > max) min else v + step
                "decrement" -> maxOf(v - step, min)
                "randomize" -> min + kotlin.math.floor(random.nextDouble() * ((max - min) / step + 1)) * step
                else -> return current
            }
            return if (spec.widgetType == "INT" || (current as JsonPrimitive).intOrNull != null || current.content.toLongOrNull() != null)
                JsonPrimitive(next.toLong()) else JsonPrimitive(next)
        }

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** Numeric helpers the UI shares with the engine. */
fun JsonElement.asDouble(): Double? = (this as? JsonPrimitive)?.doubleOrNull
fun InputDef.isMultilineText(): Boolean = widgetType == "STRING" && (multiline || options.bool("dynamicPrompts") == true)
