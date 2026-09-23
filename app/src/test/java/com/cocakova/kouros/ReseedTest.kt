package com.cocakova.kouros

import com.cocakova.kouros.core.api.ObjectInfo
import com.cocakova.kouros.run.Reseed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReseedTest {
    private fun o(s: String) = Json.parseToJsonElement(s) as JsonObject
    private val oi = ObjectInfo.parse(o("""{
        "K": {"input":{"required":{"seed":["INT",{"min":0,"max":100000}],"steps":["INT",{}]}},"output":[]},
        "P": {"input":{"required":{"value":["INT",{"control_after_generate":"fixed"}]}},"output":["INT"]}
    }"""))

    @Test fun randomizesSeedsKeepsTheRest() {
        val p = o("""{"1":{"class_type":"K","inputs":{"seed":5,"steps":20}},"2":{"class_type":"P","inputs":{"value":7}}}""")
        val n = Reseed.advance(p, oi)
        val k = (n["1"] as JsonObject)["inputs"] as JsonObject
        assertNotEquals(JsonPrimitive(5), k["seed"])
        assertEquals(JsonPrimitive(20), k["steps"])
        assertEquals(JsonPrimitive(7), ((n["2"] as JsonObject)["inputs"] as JsonObject)["value"])
    }
}
