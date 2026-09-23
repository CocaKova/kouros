package com.cocakova.kouros.core

import com.cocakova.kouros.core.api.ComfyClient
import com.cocakova.kouros.core.api.ObjectInfo
import com.cocakova.kouros.core.api.ServerEndpoint
import com.cocakova.kouros.core.api.SubmitResult
import com.cocakova.kouros.core.compile.PromptValidator
import com.cocakova.kouros.core.compile.Templates
import com.cocakova.kouros.core.form.FormEngine
import com.cocakova.kouros.core.form.ModelNeeds
import com.cocakova.kouros.core.form.WorkflowTraits
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a newcomer's server looks like to the app: a plain ComfyUI with no models, no custom
 * nodes, no saved workflows and no bridge. Walks the first-run path — templates, adding one to
 * the workflows, compiling it, being told which models are missing (with links), and running
 * something that needs no model at all. Skipped unless KOUROS_STOCK_SERVER is set; it writes one
 * workflow file and deletes it again.
 */
class StockServerTest {
    @Test
    fun firstRunOnAPlainServer() = runBlocking {
        val server = System.getenv("KOUROS_STOCK_SERVER") ?: return@runBlocking
        val json = Json { ignoreUnknownKeys = true }
        val c = ComfyClient(HttpClient(OkHttp) { install(WebSockets) }, ServerEndpoint(server))
        val stats = c.systemStats()
        println("server: ComfyUI ${stats.comfyuiVersion} · ${stats.devices.firstOrNull()?.name}")

        assertNull(c.bridge(), "a stock server has no bridge")
        println("saved workflows: ${c.listUserdata("workflows").size}")

        val templates = c.templates()
        val local = templates.filter { it.runsLocally }
        println("templates: ${templates.size}, ${local.size} run locally, groups ${local.map { it.group }.distinct()}")
        assertTrue(local.size > 20)

        val oi = ObjectInfo.parse(json.parseToJsonElement(c.objectInfoText()) as JsonObject)

        // Every local template compiles, and what it lacks is explained, not just refused.
        var compiled = 0; var withLinks = 0; var unexplained = 0; var failed = 0
        for (t in local) {
            val text = runCatching { c.templateWorkflow(t) }.getOrNull() ?: continue
            val file = json.parseToJsonElement(text) as JsonObject
            val rt = runCatching { Templates.resolveLocal(file, oi) }.getOrElse { failed++; println("  compile failed: ${t.name}: ${it.message}"); continue }
            compiled++
            FormEngine(oi).build(rt.compiled, rt.workflow)
            WorkflowTraits.of(rt.prompt, oi)
            val issues = PromptValidator.validate(rt.prompt, oi).filter { it.kind != PromptValidator.Kind.MISSING_INPUT }
            val needs = ModelNeeds.missing(issues, rt.workflow?.raw?.let(ModelNeeds::links).orEmpty())
            if (needs.isNotEmpty() && needs.all { it.url != null && it.directory != null }) withLinks++
            val other = issues.filterNot { i -> i.kind == PromptValidator.Kind.BAD_CHOICE && needs.any { it.name == i.value } }
            if (other.isNotEmpty()) { unexplained++; if (unexplained <= 8) println("  ${t.name}: ${other.take(2).joinToString { it.message }}") }
        }
        println("compiled $compiled/${local.size} (failed $failed); every missing model has a link in $withLinks; other issues in $unexplained")
        assertEquals(0, failed)

        // Adding a template writes a workflow the desktop sees; then we clean up.
        val t = local.first()
        val path = "workflows/kouros-stock-test-${UUID.randomUUID()}.json"
        c.writeUserdata(path, c.templateWorkflow(t), overwrite = false)
        assertTrue(c.listUserdata("workflows").any { "workflows/" + it.path == path })
        c.deleteUserdata(path)

        // A model-free run: an empty image, saved. Proves queueing and history on this server.
        val prompt = buildJsonObject {
            putJsonObject("1") { put("class_type", "EmptyImage"); putJsonObject("inputs") { put("width", 64); put("height", 64); put("batch_size", 1); put("color", 0xC8663F) } }
            putJsonObject("2") { put("class_type", "SaveImage"); putJsonObject("inputs") { putJsonArray("images") { add(JsonPrimitive("1")); add(JsonPrimitive(0)) }; put("filename_prefix", "kouros_stock_test") } }
        }
        val id = UUID.randomUUID().toString()
        val r = c.submit(prompt, "kouros-stock-test", id)
        assertTrue(r is SubmitResult.Accepted, "rejected: $r")
        var entry = c.historyFor(id)
        repeat(60) { if (entry == null) { delay(500); entry = c.historyFor(id) } }
        println("model-free run: ${entry?.statusStr}, outputs ${entry?.outputs?.keys}")
        assertEquals("success", entry?.statusStr)
        c.deleteHistory(listOf(id))
    }
}
