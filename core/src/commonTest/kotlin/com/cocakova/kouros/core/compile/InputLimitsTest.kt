package com.cocakova.kouros.core.compile

import com.cocakova.kouros.core.api.ObjectInfo
import com.cocakova.kouros.core.form.FormEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A node that declares a range wider than its code accepts fails only at run time, on a value
 * the app was told was legal. The adapters narrow it once, where the server's definitions are read.
 */
class InputLimitsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val defs = """
        {"SUPIR_Upscale": {"input": {"required": {"seed": ["INT", {"default": 123, "min": 0, "max": 18446744073709551615}]}}, "output": []},
         "KSampler": {"input": {"required": {"seed": ["INT", {"default": 0, "min": 0, "max": 18446744073709551615}]}}, "output": []}}
    """.trimIndent()

    private fun parse(withAdapters: Boolean) =
        ObjectInfo.parse(json.parseToJsonElement(defs) as JsonObject, if (withAdapters) NodeAdapters.DEFAULT else null)

    @Test fun theNarrowedNodeGetsItsRealCeiling() {
        assertEquals(4294967295.0, parse(true)["SUPIR_Upscale"]!!.input("seed")!!.max)
    }

    @Test fun everyOtherNodeIsLeftAlone() {
        assertEquals(1.8446744073709552E19, parse(true)["KSampler"]!!.input("seed")!!.max)
        assertEquals(1.8446744073709552E19, parse(false)["SUPIR_Upscale"]!!.input("seed")!!.max)
    }

    @Test fun aNewSeedStaysInsideWhatTheNodeTakes() {
        val spec = parse(true)["SUPIR_Upscale"]!!.input("seed")!!
        val rng = Random(7)
        repeat(200) {
            val next = FormEngine.nextSeed(JsonPrimitive(123), "randomize", spec, rng)
            val v = (next as JsonPrimitive).content.toLong()
            assertTrue(v in 0..4294967295L, "drew $v")
        }
    }

    @Test fun aRememberedValueFromTheOldRangeComesBackInside() {
        val spec = parse(true)["SUPIR_Upscale"]!!.input("seed")!!
        assertEquals(JsonPrimitive(4294967295L), FormEngine.inRange(JsonPrimitive(1030661130530783L), spec))
        assertEquals(JsonPrimitive(1234L), FormEngine.inRange(JsonPrimitive(1234L), spec))
        // Text and choices are left exactly as they were.
        assertEquals(JsonPrimitive("a.png"), FormEngine.inRange(JsonPrimitive("a.png"), spec))
    }

    @Test fun rulesCanBeAddedWithoutTouchingTheApp() {
        val extra = NodeAdapters.parse("""{"inputLimits": {"OddNode": {"steps": {"min": 2, "max": 8}}}}""")
        val merged = NodeAdapters.DEFAULT.merge(extra)
        assertEquals(4294967295.0, merged.inputLimits["SUPIR_Upscale"]?.get("seed")?.max)
        assertEquals(8.0, merged.inputLimits["OddNode"]?.get("steps")?.max)
        assertEquals(2.0, merged.inputLimits["OddNode"]?.get("steps")?.min)
    }
}
