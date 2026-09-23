package com.cocakova.kouros.core.assist

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PromptAssistTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun extractTakesTheLastTaggedBlock() {
        val raw = "Let me check memory.\n<prompt>draft</prompt>\nBetter:\n<prompt>\n  a red fox in snow, 85mm \n</prompt>"
        assertEquals("a red fox in snow, 85mm", PromptAssist.extract(raw))
    }

    @Test
    fun extractWithoutTagsStripsFencesAndQuotes() {
        assertEquals("a lighthouse at dusk", PromptAssist.extract("\"a lighthouse at dusk\""))
        assertEquals("a lighthouse", PromptAssist.extract("```\na lighthouse\n```"))
        // An unclosed tag (stream cut short) still yields what came after it.
        assertEquals("half a prompt", PromptAssist.extract("ok <prompt>half a prompt"))
    }

    @Test
    fun sseCollectsContentAndSurfacesToolProgress() {
        val p = PromptAssist.SseParser(json)
        val updates = listOf(
            """data: {"choices":[{"delta":{"reasoning_content":"hmm"}}]}""",
            "",
            "event: hermes.tool.progress",
            """data: {"tool":"memory_search","label":"memory_search: Salt Creek","status":"running"}""",
            "",
            "event: hermes.tool.progress",
            """data: {"tool":"memory_search","status":"completed"}""",
            "",
            """data: {"choices":[{"delta":{"content":"<prompt>a sign"}}]}""",
            """data: {"choices":[{"delta":{"content":" at dawn</prompt>"}}]}""",
            "data: [DONE]",
        ).mapNotNull { p.feed(it) }
        assertEquals(AssistUpdate.Status("Thinking"), updates[0])
        assertEquals(AssistUpdate.Status("memory_search: Salt Creek"), updates[1])
        assertEquals(AssistUpdate.Partial("<prompt>a sign at dawn</prompt>"), updates.last())
        assertTrue(p.finished)
    }

    @Test
    fun userMessageCarriesContext() {
        val m = PromptAssist.userMessage(
            AssistRequest("a cat", "make it moody", workflowName = "Flux T2I", outputKind = "images", models = listOf("flux.safetensors"), counterpart = "blurry"),
        )
        assertTrue("Flux T2I" in m && "flux.safetensors" in m && "make it moody" in m && "blurry" in m && "a cat" in m)
    }

    @Test
    fun streamsEndToEndOverMock() = runTest {
        val sse = listOf(
            """data: {"choices":[{"delta":{"content":"<prompt>"}}]}""",
            """data: {"choices":[{"delta":{"content":"neon rain</prompt>"}}]}""",
            "data: [DONE]", "",
        ).joinToString("\n")
        val engine = MockEngine { req ->
            if (req.url.encodedPath.endsWith("/models")) respond("""{"data":[{"id":"agent"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            else respond(sse, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val out = PromptAssist(HttpClient(engine), AssistEndpoint("http://x/v1/")).enhance(AssistRequest("rain", "")).toList()
        val done = assertIs<AssistUpdate.Done>(out.last())
        assertEquals("neon rain", done.prompt)
    }

    @Test
    fun explainsAuthFailures() = runTest {
        val engine = MockEngine { respond("nope", HttpStatusCode.Unauthorized) }
        val e = assertFailsWith<AssistException> {
            PromptAssist(HttpClient(engine), AssistEndpoint("http://x/v1", model = "m")).enhance(AssistRequest("a", "")).toList()
        }
        assertTrue("API key" in e.message!!)
    }
}
