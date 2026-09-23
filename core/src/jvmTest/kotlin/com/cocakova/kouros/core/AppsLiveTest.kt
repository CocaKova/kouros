package com.cocakova.kouros.core

import com.cocakova.kouros.core.api.ComfyClient
import com.cocakova.kouros.core.api.ObjectInfo
import com.cocakova.kouros.core.api.ServerEndpoint
import com.cocakova.kouros.core.apps.AppCatalog
import com.cocakova.kouros.core.compile.PromptValidator
import com.cocakova.kouros.core.compile.Templates
import com.cocakova.kouros.core.form.FormEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shipped apps against a real server: every class understood, every arranged field still
 * there once the form engine has read the server's own definitions, and nothing missing but the
 * models the app declares. This is the check a unit test can't make — a pinned field whose
 * widget the server describes differently would simply vanish from the form.
 *
 * Skipped unless KOUROS_APPS_SERVER is set. It reads only; nothing is queued.
 */
class AppsLiveTest {
    @Test
    fun theShippedAppsFitTheServer() = runBlocking {
        val server = System.getenv("KOUROS_APPS_SERVER") ?: return@runBlocking
        val file = System.getenv("KOUROS_APPS_FILE") ?: "../app/src/main/assets/apps.json"
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val apps = AppCatalog.parse(File(file).readText())
        assertTrue(apps.isNotEmpty(), "no apps read from $file")

        val c = ComfyClient(HttpClient(OkHttp) { install(WebSockets) }, ServerEndpoint(server))
        val stats = c.systemStats()
        println("server: ComfyUI ${stats.comfyuiVersion}")
        val oi = ObjectInfo.parse(json.parseToJsonElement(c.objectInfoText()) as JsonObject)
        val folders = apps.flatMap { it.modelFolders }.distinct().associateWith { c.models(it).toSet() }

        for (a in apps) {
            val needs = a.needs(oi, folders)
            println("── ${a.id}: ${if (needs.met) "ready" else "needs packs ${needs.packs.map { it.id }} models ${needs.models.map { it.name }}"}")
            if (needs.packs.isNotEmpty()) {
                println("   (skipping the form: the node pack isn't installed here)")
                continue
            }

            val compiled = Templates.fromApiPrompt(a.prompt, oi)
            val base = FormEngine(oi).build(compiled, null)
            val form = a.layout.apply(base)

            // Every pinned field survived the engine, in the order the app asks for.
            assertEquals(a.layout.pinned, form.hero.map { it.key }, "${a.id}: the top of the form is not what the app arranged")
            // Nothing hidden leaked back into the form.
            assertTrue(form.all.none { it.key in a.layout.hidden }, "${a.id}: a hidden field is on screen")
            // Labels landed on the fields they were written for.
            for ((key, label) in a.layout.labels) {
                val f = form.all.firstOrNull { it.key == key }
                if (f != null) assertEquals(label, f.label, "${a.id}: $key is not labelled as the app asks")
            }
            println("   form: ${form.hero.map { it.label }} · advanced ${form.advanced.size}")

            // What the server would refuse, and why: only the models this app declares, if any.
            val issues = PromptValidator.validate(a.prompt, oi).filter { it.kind != PromptValidator.Kind.MISSING_INPUT }
            val declared = a.models.map { it.name }.toSet()
            val strays = issues.filterNot { it.value in declared }
            assertTrue(strays.isEmpty(), "${a.id}: the server refuses something the app never declared: ${strays.map { it.message }}")
            assertEquals(needs.models.map { it.name }.toSet(), issues.mapNotNull { it.value }.toSet(), "${a.id}: what the server lacks and what the app says it lacks disagree")
        }
    }
}
