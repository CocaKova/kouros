package com.cocakova.kouros.core.form

import com.cocakova.kouros.core.api.ObjectInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** In precedence order: a workflow that makes several things is filed under the first. */
enum class OutputKind(val noun: String, val label: String) {
    VIDEO("video", "Video"), AUDIO("audio", "Audio"), MODEL3D("3D models", "3D"), IMAGE("images", "Image"), TEXT("text", "Text"),
}

/**
 * What a workflow makes and what it takes, read off its API prompt and the server's node
 * definitions — never off node names in a list, so custom packs classify like core nodes do.
 */
data class WorkflowTraits(
    val outputs: Set<OutputKind>,
    /** Media the workflow asks for: "image", "video", "audio". */
    val inputs: Set<String>,
    /** Model files it loads, in prompt order (checkpoints, UNets, LoRAs, encoders). */
    val models: List<String>,
) {
    /** The one kind to file it under: moving pictures, sound and meshes outrank stills, stills outrank text. */
    val primary: OutputKind? get() = OutputKind.entries.firstOrNull { it in outputs }

    companion object {
        private val MODEL_FILE = Regex("""\.(safetensors|sft|gguf|ckpt|pt|pth|bin|onnx)$""", RegexOption.IGNORE_CASE)
        private val VIDEO_HINTS = listOf("frame_rate", "fps", "framerate")

        fun of(prompt: JsonObject, objectInfo: ObjectInfo): WorkflowTraits {
            val outputs = LinkedHashSet<OutputKind>()
            val inputs = LinkedHashSet<String>()
            val models = LinkedHashSet<String>()
            for ((_, v) in prompt) {
                val node = v as? JsonObject ?: continue
                val cls = (node["class_type"] as? JsonPrimitive)?.contentOrNull ?: continue
                val def = objectInfo[cls]
                val values = node["inputs"] as? JsonObject
                values?.values?.forEach { x -> (x as? JsonPrimitive)?.contentOrNull?.takeIf { MODEL_FILE.containsMatchIn(it) }?.let(models::add) }
                def ?: continue
                def.inputs.forEach { i -> i.uploadKind?.let(inputs::add) }
                if (!def.isOutputNode) continue
                val types = def.inputs.map { it.type }.toSet()
                val videoish = def.inputs.any { it.name in VIDEO_HINTS } || cls.contains("video", ignoreCase = true)
                when {
                    "VIDEO" in types || ("IMAGE" in types && videoish) -> outputs += OutputKind.VIDEO
                    "AUDIO" in types -> outputs += OutputKind.AUDIO
                    "IMAGE" in types || "LATENT" in types -> outputs += OutputKind.IMAGE
                    types.any { it.contains("MESH") || it.contains("3D") || it == "GLB" } -> outputs += OutputKind.MODEL3D
                    "STRING" in types || "*" in types -> outputs += OutputKind.TEXT
                }
            }
            return WorkflowTraits(outputs, inputs, models.toList())
        }
    }
}
