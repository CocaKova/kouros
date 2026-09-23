package com.cocakova.pygmalion.core.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ComfyClientTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun submitSendsClientPromptIdAndAuthHeaders() = runTest {
        var body = ""
        var auth: String? = null
        var url = ""
        val engine = MockEngine { req ->
            body = req.body.toByteArray().decodeToString()
            auth = req.headers["Authorization"]
            url = req.url.toString()
            respond("""{"prompt_id":"11111111-2222-3333-4444-555555555555","number":7,"node_errors":{}}""", HttpStatusCode.OK, json)
        }
        val c = ComfyClient(HttpClient(engine), ServerEndpoint("https://host.example/comfy/", mapOf("Authorization" to "Bearer t")))
        val r = c.submit(buildJsonObject { }, "client", "11111111-2222-3333-4444-555555555555")
        val ok = assertIs<SubmitResult.Accepted>(r)
        assertEquals(7.0, ok.value.number)
        assertEquals("Bearer t", auth)
        assertEquals("https://host.example/comfy/prompt", url)
        val sent = Json.parseToJsonElement(body) as JsonObject
        assertEquals(JsonPrimitive("11111111-2222-3333-4444-555555555555"), sent["prompt_id"])
        assertEquals(JsonPrimitive("client"), sent["client_id"])
    }

    @Test fun validationFailureIsARejectionNotACrash() = runTest {
        val engine = MockEngine {
            respond(
                """{"error":{"type":"prompt_outputs_failed_validation","message":"Prompt outputs failed validation","details":""},"node_errors":{"4":{"errors":[{"message":"Value not in list"}]}}}""",
                HttpStatusCode.BadRequest, json,
            )
        }
        val r = ComfyClient(HttpClient(engine), ServerEndpoint("http://h:8188")).submit(buildJsonObject { }, "c", null)
        val rej = assertIs<SubmitResult.Rejected>(r)
        assertEquals("Prompt outputs failed validation", rej.value.message)
        assertTrue("4" in rej.value.nodeErrors)
    }

    @Test fun queueAndHistoryParse() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/queue" -> respond("""{"queue_running":[[3,"run-1",{},{"client_id":"c"},["9"]]],"queue_pending":[[4,"run-2",{},{},["9"]]]}""", HttpStatusCode.OK, json)
                "/history/run-0" -> respond(
                    """{"run-0":{"prompt":[1,"run-0",{"9":{"class_type":"SaveImage","inputs":{}}},{"extra_pnginfo":{"workflow":{"nodes":[]}}},["9"]],
                       "outputs":{"9":{"images":[{"filename":"a.png","subfolder":"","type":"output"}]}},
                       "status":{"status_str":"success","completed":true,"messages":[["execution_start",{"prompt_id":"run-0","timestamp":10}],["execution_success",{"prompt_id":"run-0","timestamp":25}]]}}}""",
                    HttpStatusCode.OK, json,
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val c = ComfyClient(HttpClient(engine), ServerEndpoint("http://h:8188"))
        val q = c.queue()
        assertEquals(setOf("run-1", "run-2"), q.promptIds)
        val h = c.historyFor("run-0")!!
        assertEquals("success", h.statusStr)
        assertEquals(15L, h.endedAtMs!! - h.startedAtMs!!)
        assertTrue(h.workflow != null)
        assertEquals("SaveImage", (h.prompt!!["9"] as JsonObject)["class_type"].let { (it as JsonPrimitive).content })
    }

    @Test fun urlsEncodeUserdataAndView() {
        val c = ComfyClient(HttpClient(MockEngine { respond("") }), ServerEndpoint("https://h/p"))
        assertEquals(
            "https://h/p/view?filename=a%20b.png&subfolder=sub%2Fdir&type=output&preview=webp%3B80",
            c.viewUrl(FileRef("a b.png", "sub/dir", "output"), preview = "webp;80"),
        )
        assertEquals("wss://h/p/ws", ServerEndpoint("https://h/p").ws("/ws"))
    }
}
