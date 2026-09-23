package com.cocakova.pygmalion.core.compile

import com.cocakova.pygmalion.core.api.ObjectInfo
import com.cocakova.pygmalion.core.graph.WorkflowFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Small hand-built graphs for the rules the corpus exercises only incidentally. */
class CompilerUnitTest {
    private fun o(s: String) = Json.parseToJsonElement(s) as JsonObject

    private val oi = ObjectInfo.parse(
        o(
            """{
              "Src": {"input":{"required":{}},"output":["IMAGE"],"output_node":false},
              "Filter": {"input":{"required":{"image":["IMAGE",{}],"strength":["FLOAT",{"default":1.0}]}},"input_order":{"required":["image","strength"]},"output":["IMAGE"]},
              "Sampler": {"input":{"required":{"seed":["INT",{"default":0}],"steps":["INT",{"default":20}]}},"input_order":{"required":["seed","steps"]},"output":["LATENT"]},
              "Save": {"input":{"required":{"images":["IMAGE",{}],"prefix":["STRING",{"default":"x"}]}},"input_order":{"required":["images","prefix"]},"output":[],"output_node":true}
            }""",
        ),
    )

    private fun compile(wf: String) = WorkflowCompiler(oi).compile(WorkflowFormat.parse(o(wf)))

    @Test fun seedControlSlotIsSkippedAndRecorded() {
        val c = compile(
            """{"nodes":[{"id":1,"type":"Sampler","mode":0,"inputs":[],"outputs":[],"widgets_values":[42,"fixed",8]}],"links":[]}""",
        )
        val inputs = (c.prompt["1"] as JsonObject)["inputs"] as JsonObject
        assertEquals(JsonPrimitive(42), inputs["seed"])
        assertEquals(JsonPrimitive(8), inputs["steps"])
        assertEquals("fixed", c.seedControls[ApiRef("1", "seed")])
        assertTrue(c.confident)
    }

    @Test fun bypassPassesMatchingInputThrough() {
        val c = compile(
            """{"nodes":[
                {"id":1,"type":"Src","mode":0,"inputs":[],"outputs":[{"name":"IMAGE","type":"IMAGE","links":[1]}]},
                {"id":2,"type":"Filter","mode":4,"inputs":[{"name":"image","type":"IMAGE","link":1}],"outputs":[{"name":"IMAGE","type":"IMAGE","links":[2]}],"widgets_values":[0.5]},
                {"id":3,"type":"Save","mode":0,"inputs":[{"name":"images","type":"IMAGE","link":2}],"outputs":[],"widgets_values":["out"]}
               ],"links":[[1,1,0,2,0,"IMAGE"],[2,2,0,3,0,"IMAGE"]]}""",
        )
        assertFalse("2" in c.prompt)
        val save = (c.prompt["3"] as JsonObject)["inputs"] as JsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("1"), JsonPrimitive(0))), save["images"])
    }

    @Test fun mutedSourceDropsTheLink() {
        val c = compile(
            """{"nodes":[
                {"id":1,"type":"Src","mode":2,"inputs":[],"outputs":[{"name":"IMAGE","type":"IMAGE","links":[1]}]},
                {"id":3,"type":"Save","mode":0,"inputs":[{"name":"images","type":"IMAGE","link":1}],"outputs":[],"widgets_values":["out"]}
               ],"links":[[1,1,0,3,0,"IMAGE"]]}""",
        )
        val save = (c.prompt["3"] as JsonObject)["inputs"] as JsonObject
        assertFalse("images" in save)
    }

    @Test fun rerouteIsFollowed() {
        val c = compile(
            """{"nodes":[
                {"id":1,"type":"Src","mode":0,"inputs":[],"outputs":[{"name":"IMAGE","type":"IMAGE","links":[1]}]},
                {"id":5,"type":"Reroute","mode":0,"inputs":[{"name":"","type":"*","link":1}],"outputs":[{"name":"","type":"IMAGE","links":[2]}]},
                {"id":3,"type":"Save","mode":0,"inputs":[{"name":"images","type":"IMAGE","link":2}],"outputs":[],"widgets_values":["out"]}
               ],"links":[[1,1,0,5,0,"*"],[2,5,0,3,0,"IMAGE"]]}""",
        )
        val save = (c.prompt["3"] as JsonObject)["inputs"] as JsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("1"), JsonPrimitive(0))), save["images"])
        assertFalse("5" in c.prompt)
    }

    @Test fun promotedSubgraphWidgetOverridesInnerValue() {
        val c = compile(
            """{"nodes":[{"id":10,"type":"sg-1","mode":0,"inputs":[{"name":"seed","type":"INT","link":null,"widget":{"name":"seed"}}],"outputs":[],"widgets_values":[777]}],
               "links":[],
               "definitions":{"subgraphs":[{"id":"sg-1","name":"S","inputNode":{"id":-10},"outputNode":{"id":-20},
                  "inputs":[{"name":"seed","type":"INT","linkIds":[1]}],"outputs":[],
                  "nodes":[{"id":1,"type":"Sampler","mode":0,"inputs":[{"name":"seed","type":"INT","link":1,"widget":{"name":"seed"}}],"outputs":[],"widgets_values":[5,"randomize",20]}],
                  "links":[{"id":1,"origin_id":-10,"origin_slot":0,"target_id":1,"target_slot":0,"type":"INT"}]}]}}""",
        )
        val inner = (c.prompt["10:1"] as JsonObject)["inputs"] as JsonObject
        assertEquals(JsonPrimitive(777), inner["seed"])
        assertEquals(listOf(ApiRef("10:1", "seed")), c.promoted["10:seed"])
    }

    @Test fun missingNodeIsAnError() {
        val c = compile("""{"nodes":[{"id":1,"type":"NotInstalled","mode":0,"inputs":[],"outputs":[]}],"links":[]}""")
        assertFalse(c.confident)
        assertEquals(Diagnostic.MISSING_NODE, c.diagnostics.first().code)
    }
}
