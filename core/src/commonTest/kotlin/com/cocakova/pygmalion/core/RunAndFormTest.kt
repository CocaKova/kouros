package com.cocakova.pygmalion.core

import com.cocakova.pygmalion.core.api.ObjectInfo
import com.cocakova.pygmalion.core.compile.WorkflowCompiler
import com.cocakova.pygmalion.core.form.FieldOrigin
import com.cocakova.pygmalion.core.form.FieldRole
import com.cocakova.pygmalion.core.form.FormEngine
import com.cocakova.pygmalion.core.graph.WorkflowFormat
import com.cocakova.pygmalion.core.run.RunPhase
import com.cocakova.pygmalion.core.run.RunTracker
import com.cocakova.pygmalion.core.ws.WsEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RunAndFormTest {
    private fun o(s: String) = Json.parseToJsonElement(s) as JsonObject

    private val oi = ObjectInfo.parse(
        o(
            """{
              "Enc": {"input":{"required":{"text":["STRING",{"multiline":true}]}},"input_order":{"required":["text"]},"output":["CONDITIONING"]},
              "Sampler": {"input":{"required":{"positive":["CONDITIONING",{}],"negative":["CONDITIONING",{}],"seed":["INT",{"default":0,"min":0,"max":1000}],"steps":["INT",{"default":20}],"sampler_name":["COMBO",{"options":["euler","dpm"]}]}},
                          "input_order":{"required":["positive","negative","seed","steps","sampler_name"]},"output":["LATENT"]},
              "Save": {"input":{"required":{"samples":["LATENT",{}],"filename_prefix":["STRING",{"default":"x"}]}},"input_order":{"required":["samples","filename_prefix"]},"output":[],"output_node":true}
            }""",
        ),
    )

    private val wf = WorkflowFormat.parse(
        o(
            """{"nodes":[
              {"id":1,"type":"Enc","title":"Positive","mode":0,"inputs":[],"outputs":[{"name":"C","type":"CONDITIONING","links":[1]}],"widgets_values":["a cat"]},
              {"id":2,"type":"Enc","title":"Negative Prompt","mode":0,"inputs":[],"outputs":[{"name":"C","type":"CONDITIONING","links":[2]}],"widgets_values":["blurry"]},
              {"id":3,"type":"Sampler","mode":0,"inputs":[{"name":"positive","type":"CONDITIONING","link":1},{"name":"negative","type":"CONDITIONING","link":2}],"outputs":[{"name":"L","type":"LATENT","links":[3]}],"widgets_values":[5,"randomize",20,"euler"]},
              {"id":4,"type":"Save","mode":0,"inputs":[{"name":"samples","type":"LATENT","link":3}],"outputs":[],"widgets_values":["out"]}
            ],"links":[[1,1,0,3,0,"CONDITIONING"],[2,2,0,3,1,"CONDITIONING"],[3,3,0,4,0,"LATENT"]]}""",
        ),
    )

    @Test fun formPicksPromptsSeedAndHidesPlumbing() {
        val compiled = WorkflowCompiler(oi).compile(wf)
        val form = FormEngine(oi).build(compiled, wf)
        val roles = form.hero.associate { it.key to it.role }
        assertEquals(FieldRole.PROMPT, roles["1:text"])
        assertEquals(FieldRole.NEGATIVE_PROMPT, roles["2:text"])
        assertEquals(FieldRole.SEED, roles["3:seed"])
        assertTrue(form.all.none { it.key == "4:filename_prefix" })
        assertEquals("randomize", form.all.first { it.key == "3:seed" }.control)
        assertTrue(form.all.all { it.origin == FieldOrigin.SCORED })
    }

    @Test fun applyWritesEveryTarget() {
        val compiled = WorkflowCompiler(oi).compile(wf)
        val form = FormEngine(oi).build(compiled, wf)
        val prompt = FormEngine.apply(compiled.prompt, mapOf(form.all.first { it.key == "1:text" } to JsonPrimitive("a dog")))
        assertEquals(JsonPrimitive("a dog"), ((prompt["1"] as JsonObject)["inputs"] as JsonObject)["text"])
        assertEquals(JsonPrimitive("blurry"), ((prompt["2"] as JsonObject)["inputs"] as JsonObject)["text"])
    }

    @Test fun seedPolicy() {
        val spec = oi["Sampler"]!!.input("seed")!!
        assertEquals(JsonPrimitive(6L), FormEngine.nextSeed(JsonPrimitive(5), "increment", spec))
        assertEquals(JsonPrimitive(5), FormEngine.nextSeed(JsonPrimitive(5), "fixed", spec))
        val r = FormEngine.nextSeed(JsonPrimitive(5), "randomize", spec, Random(1))
        assertTrue((r as JsonPrimitive).content.toLong() in 0..1000)
        assertNotEquals(JsonPrimitive(5L), r)
    }

    @Test fun trackerFollowsARun() {
        val prompt = WorkflowCompiler(oi).compile(wf).prompt
        val t = RunTracker("p", prompt, setOf("Save"))
        assertEquals(4, t.state.totalNodes)
        t.onEvent(WsEvent.ExecutionStart("p"), 100)
        t.onEvent(WsEvent.ExecutionCached("p", listOf("1", "2")), 101)
        assertEquals(2, t.state.totalNodes)
        t.onEvent(WsEvent.Executing("p", "3", "3"), 102)
        t.onEvent(WsEvent.Progress("p", "3", 10, 20), 103)
        assertEquals(0.25f, t.state.fraction)
        t.onEvent(WsEvent.Executing("p", "4", "4"), 104)
        assertEquals(1, t.state.doneNodes)
        t.onEvent(WsEvent.Executed("p", "4", "4", o("""{"images":[{"filename":"a.png","subfolder":"","type":"output"}]}""")), 105)
        t.onEvent(WsEvent.ExecutionSuccess("p"), 106)
        assertEquals(RunPhase.SUCCEEDED, t.state.phase)
        assertEquals(1, t.state.outputs.size)
        assertEquals(1f, t.state.fraction)
    }

    @Test fun trackerIgnoresOtherPrompts() {
        val t = RunTracker("p", WorkflowCompiler(oi).compile(wf).prompt, setOf("Save"))
        assertEquals(null, t.onEvent(WsEvent.ExecutionStart("other"), 1))
        assertEquals(null, t.onEvent(WsEvent.ExecutionError("other", "3", "Sampler", null, "x", emptyList()), 2))
        assertEquals(RunPhase.QUEUED, t.state.phase)
    }
}
