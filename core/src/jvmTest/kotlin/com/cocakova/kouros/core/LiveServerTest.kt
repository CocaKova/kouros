package com.cocakova.kouros.core

import com.cocakova.kouros.core.api.ComfyClient
import com.cocakova.kouros.core.api.ObjectInfo
import com.cocakova.kouros.core.api.ServerEndpoint
import com.cocakova.kouros.core.api.SocketMessage
import com.cocakova.kouros.core.api.SubmitResult
import com.cocakova.kouros.core.compile.TemplateSource
import com.cocakova.kouros.core.compile.Templates
import com.cocakova.kouros.core.form.FieldRole
import com.cocakova.kouros.core.form.FormEngine
import com.cocakova.kouros.core.run.RunPhase
import com.cocakova.kouros.core.run.RunTracker
import com.cocakova.kouros.core.ws.BinaryFrame
import com.cocakova.kouros.core.ws.WsEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End to end against a real ComfyUI, exactly the path the app takes: compile a saved workflow,
 * edit it through the form engine, submit with our own prompt id, follow the socket, reconcile
 * with history, fetch the result. Skipped unless KOUROS_LIVE_SERVER and KOUROS_LIVE_WORKFLOW are set.
 */
class LiveServerTest {
    @Test
    fun runsAWorkflowEndToEnd() = runBlocking {
        val server = System.getenv("KOUROS_LIVE_SERVER") ?: return@runBlocking
        val wfPath = System.getenv("KOUROS_LIVE_WORKFLOW") ?: return@runBlocking
        val log = StringBuilder()
        fun say(s: String) { println(s); log.appendLine(s) }

        val http = HttpClient(OkHttp) { install(WebSockets) }
        val c = ComfyClient(http, ServerEndpoint(server))
        val json = Json { ignoreUnknownKeys = true }
        val stats = c.systemStats()
        say("server: ComfyUI ${stats.comfyuiVersion} · ${stats.devices.firstOrNull()?.name}")

        val oi = ObjectInfo.parse(json.parseToJsonElement(c.objectInfoText()) as JsonObject)
        val file = json.parseToJsonElement(File(wfPath).readText()) as JsonObject
        val t = Templates.resolveLocal(file, oi)
        say("template: ${t.source}, ${t.prompt.size} nodes")
        assertEquals(TemplateSource.COMPILED, t.source)

        val form = FormEngine(oi).build(t.compiled, t.workflow)
        say("form hero: " + form.hero.joinToString { "${it.label}[${it.role}]" })
        val promptField = form.all.first { it.role == FieldRole.PROMPT }
        val text = System.getenv("KOUROS_LIVE_PROMPT") ?: "a small clay figure on a stone plinth in a sunlit sculptor's studio, soft light"
        val values = mutableMapOf(promptField to (JsonPrimitive(text) as kotlinx.serialization.json.JsonElement))
        form.all.firstOrNull { it.role == FieldRole.SEED }?.let { values[it] = JsonPrimitive(424242L) }
        val prompt = FormEngine.apply(t.prompt, values)

        val clientId = UUID.randomUUID().toString()
        val promptId = UUID.randomUUID().toString()
        val tracker = RunTracker(promptId, prompt, oi.nodes.filterValues { it.isOutputNode }.keys)
        val done = CompletableDeferred<Unit>()
        val opened = CompletableDeferred<Unit>()
        var previews = 0; var events = 0; var flags: JsonObject? = null
        val socket = launch {
            c.socket(clientId).collect { m ->
                when (m) {
                    SocketMessage.Open -> opened.complete(Unit)
                    is SocketMessage.Event -> {
                        events++
                        if (m.event is WsEvent.FeatureFlags) flags = (m.event as WsEvent.FeatureFlags).flags
                        tracker.onEvent(m.event, System.currentTimeMillis())?.let { p ->
                            if (p.phase.isTerminal) done.complete(Unit)
                        }
                    }
                    is SocketMessage.Binary -> if (m.frame is BinaryFrame.Preview && (m.frame as BinaryFrame.Preview).promptId in setOf(null, promptId)) previews++
                }
            }
        }
        withTimeout(15_000) { opened.await() }
        say("socket open; server feature flags: ${flags?.keys}")

        val t0 = System.currentTimeMillis()
        val r = c.submit(prompt, clientId, promptId, kotlinx.serialization.json.buildJsonObject { put("preview_method", JsonPrimitive("auto")) })
        assertTrue(r is SubmitResult.Accepted, "rejected: $r")
        assertEquals(promptId, (r as SubmitResult.Accepted).value.promptId)
        say("accepted as our own id $promptId")

        withTimeout(300_000) { done.await() }
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        val st = tracker.state
        say("finished: ${st.phase} in ${secs}s · $events events · $previews preview frames · outputs ${st.outputs.map { it.file?.filename }}")
        assertEquals(RunPhase.SUCCEEDED, st.phase)
        assertTrue(st.outputs.isNotEmpty())
        assertTrue(previews > 0, "asked for previews but none arrived")

        // Reconciliation must agree with what the socket told us.
        val h = c.historyFor(promptId)!!
        val fromHistory = RunTracker(promptId, prompt, emptySet()).fromHistory(h)
        assertEquals(RunPhase.SUCCEEDED, fromHistory.phase)
        assertEquals(st.outputs.map { it.file }, fromHistory.outputs.map { it.file })
        say("history agrees: ${fromHistory.outputs.size} outputs, ran ${(h.endedAtMs ?: 0) - (h.startedAtMs ?: 0)} ms")

        val out = st.outputs.first { it.file != null }.file!!
        val bytes = http.get(c.viewUrl(out)).readRawBytes()
        val thumb = http.get(c.viewUrl(out, preview = "webp;85")).readRawBytes()
        say("downloaded ${bytes.size} bytes (magic ${bytes.take(4).joinToString(" ") { "%02x".format(it) }}), thumbnail ${thumb.size} bytes")
        assertTrue(bytes.size > 1000)
        File("build/live-result.png").writeBytes(bytes)

        socket.cancelAndJoin()
        c.free(unloadModels = true, freeMemory = true)
        say("asked the server to unload models")
        File("build/live-report.txt").writeText(log.toString())
    }
}
