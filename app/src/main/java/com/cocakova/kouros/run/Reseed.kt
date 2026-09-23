package com.cocakova.kouros.run

import com.cocakova.kouros.core.api.InputDef
import com.cocakova.kouros.core.api.ObjectInfo
import com.cocakova.kouros.core.form.FormEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** "Run again" for a bare API prompt: every input with a seed control moves on, per its node's spec. */
object Reseed {
    fun advance(prompt: JsonObject, objectInfo: ObjectInfo?): JsonObject = JsonObject(
        prompt.mapValues { (_, n) ->
            val node = n as? JsonObject ?: return@mapValues n
            val cls = (node["class_type"] as? JsonPrimitive)?.contentOrNull
            val def = cls?.let { objectInfo?.get(it) }
            val inputs = node["inputs"] as? JsonObject ?: return@mapValues n
            var changed = false
            val next = inputs.mapValues { (name, v) ->
                if (v is JsonArray) return@mapValues v
                val spec: InputDef = def?.input(name) ?: return@mapValues v
                val control = spec.controlAfterGenerate ?: return@mapValues v
                if (control == "fixed") return@mapValues v
                changed = true
                FormEngine.nextSeed(v, control, spec)
            }
            if (changed) JsonObject(node + ("inputs" to JsonObject(next))) else node
        },
    )
}
