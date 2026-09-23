package com.cocakova.kouros.core.form

import com.cocakova.kouros.core.api.InputDef
import com.cocakova.kouros.core.api.ObjectInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Reference images a workflow can take but that nothing feeds yet.
 *
 * A model that accepts a list of reference images declares it: a growable input of IMAGE slots
 * (`images.image_1…16`, `ref_images.ref_image_0…8`) or numbered optional IMAGE inputs
 * (`image1`, `image2`, `image3`). Saved workflows usually wire one or two of them. This reads
 * the rest off the server's node definitions, and [inject] wires photos from the phone into the
 * free ones by adding image loaders to the run's prompt, leaving the saved workflow untouched.
 *
 * Only nodes that consume images into something else (conditioning, latents) count; image
 * utilities that output IMAGE (batching, stitching) are not reference lists.
 */
class References(val slots: List<Slot>) {
    /** One free slot: [input] is the prompt key ("images.image_2"), [label] what the model calls it. */
    data class Slot(val nodeId: String, val classType: String, val input: String, val label: String, val ordinal: Int)

    /** Every node with free slots, each receiving the references in order (a positive/negative encoder pair both get them). */
    val byNode: Map<String, List<Slot>> get() = slots.groupBy { it.nodeId }

    /** How many photos can be added: the roomiest node's free slots. */
    val capacity: Int get() = byNode.values.maxOfOrNull { it.size } ?: 0

    /** The name the model gives the i-th added photo — what to write in the prompt ("image 2"). */
    fun labelFor(i: Int): String? = byNode.values.maxByOrNull { it.size }?.getOrNull(i)?.label

    /**
     * The prompt with [files] (server input paths, as an upload returns them) wired into the free
     * slots, one loader per photo shared by every node that takes references.
     */
    fun inject(prompt: JsonObject, files: List<String>, loaderClass: String = "LoadImage"): JsonObject {
        if (files.isEmpty() || slots.isEmpty()) return prompt
        val out = prompt.toMutableMap()
        val loaders = files.mapIndexed { i, f ->
            val id = "kouros_ref_${i + 1}"
            out[id] = buildJsonObject {
                put("class_type", loaderClass)
                put("inputs", buildJsonObject { put("image", f) })
            }
            id
        }
        for ((nodeId, free) in byNode) {
            val node = out[nodeId] as? JsonObject ?: continue
            val inputs = (node["inputs"] as? JsonObject)?.toMutableMap() ?: continue
            free.zip(loaders).forEach { (slot, loader) -> inputs[slot.input] = buildJsonArray { add(JsonPrimitive(loader)); add(JsonPrimitive(0)) } }
            out[nodeId] = JsonObject(node + ("inputs" to JsonObject(inputs)))
        }
        return JsonObject(out)
    }

    companion object {
        private val NUMBERED = Regex("""^(.*?)(\d+)$""")

        fun of(prompt: JsonObject, objectInfo: ObjectInfo): References {
            val slots = mutableListOf<Slot>()
            for ((id, v) in prompt) {
                val node = v as? JsonObject ?: continue
                val cls = (node["class_type"] as? JsonPrimitive)?.contentOrNull ?: continue
                val def = objectInfo[cls] ?: continue
                if (def.isOutputNode || def.outputs.any { it.type == "IMAGE" }) continue
                val used = (node["inputs"] as? JsonObject)?.keys ?: emptySet()
                var ordinal = 0
                for (input in def.inputs) {
                    growableImageNames(input)?.let { names ->
                        names.forEach { n ->
                            val key = "${input.name}.$n"
                            if (key !in used) slots += Slot(id, cls, key, humanize(n), ordinal)
                            ordinal++
                        }
                    }
                }
                // Numbered optional IMAGE inputs, two or more with the same stem: image1, image2, image3.
                val numbered = def.inputs.filter { it.type == "IMAGE" && NUMBERED.matches(it.name) }
                    .groupBy { NUMBERED.find(it.name)!!.groupValues[1] }
                    .filterValues { it.size >= 2 }
                for ((_, group) in numbered) {
                    group.sortedBy { NUMBERED.find(it.name)!!.groupValues[2].toInt() }.forEach { i ->
                        if (i.optional && i.name !in used) slots += Slot(id, cls, i.name, humanize(i.name), ordinal)
                        ordinal++
                    }
                }
            }
            return References(slots)
        }

        /** Slot names of a growable input whose template is a single IMAGE, else null. */
        private fun growableImageNames(input: InputDef): List<String>? {
            if (!input.type.startsWith("COMFY_AUTOGROW")) return null
            val template = input.options["template"] as? JsonObject ?: return null
            val tInputs = (template["input"] as? JsonObject)?.values?.flatMap { (it as? JsonObject)?.values ?: emptyList() } ?: return null
            val only = tInputs.singleOrNull() as? JsonArray ?: return null
            if ((only.firstOrNull() as? JsonPrimitive)?.contentOrNull != "IMAGE") return null
            (template["names"] as? JsonArray)?.let { arr -> return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
            val prefix = (template["prefix"] as? JsonPrimitive)?.contentOrNull ?: return null
            val max = (template["max"] as? JsonPrimitive)?.intOrNull ?: 10
            return (0 until max.coerceAtMost(100)).map { "$prefix$it" }
        }

        /** "image_2" → "image 2", "ref_image_0" → "ref image 0", "image3" → "image 3". */
        fun humanize(name: String): String =
            name.replace('_', ' ').replace(Regex("""(\D)(\d)"""), "$1 $2").replace(Regex("\\s+"), " ").trim()

        /** A loader class the server has: an image upload node that outputs IMAGE, LoadImage first. */
        fun loaderClass(objectInfo: ObjectInfo): String? =
            objectInfo["LoadImage"]?.let { "LoadImage" }
                ?: objectInfo.nodes.values.firstOrNull { d -> d.outputs.any { it.type == "IMAGE" } && d.inputs.any { it.uploadKind == "image" } }?.name
    }
}
