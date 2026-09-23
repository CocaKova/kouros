package com.cocakova.kouros.core.assist

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in: KOUROS_LIVE_ASSIST=http://host:port/v1 (KOUROS_LIVE_ASSIST_KEY for a bearer key,
 * KOUROS_LIVE_ASSIST_ASK for the instruction). Prints every update so the stream can be read.
 */
class LiveAssistTest {
    @Test
    fun enhanceAgainstARealEndpoint() {
        val url = System.getenv("KOUROS_LIVE_ASSIST") ?: return
        val http = HttpClient(OkHttp) { install(HttpTimeout) { requestTimeoutMillis = 300_000; socketTimeoutMillis = 300_000 } }
        val assist = PromptAssist(http, AssistEndpoint(url, System.getenv("KOUROS_LIVE_ASSIST_KEY")))
        var done: AssistUpdate.Done? = null
        runBlocking {
            assist.enhance(
                AssistRequest(
                    current = "a phone app icon",
                    instruction = System.getenv("KOUROS_LIVE_ASSIST_ASK") ?: "keep it short",
                    workflowName = "Qwen-Image 2.1", outputKind = "images",
                ),
            ).collect { u ->
                when (u) {
                    is AssistUpdate.Partial -> Unit
                    is AssistUpdate.Done -> done = u
                    else -> println("· $u")
                }
            }
        }
        println("RAW: ${done?.raw}\nPROMPT: ${done?.prompt}")
        assertTrue(!done?.prompt.isNullOrBlank())
    }
}
