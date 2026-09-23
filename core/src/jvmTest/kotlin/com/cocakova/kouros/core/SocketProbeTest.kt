package com.cocakova.kouros.core

import com.cocakova.kouros.core.api.ComfyClient
import com.cocakova.kouros.core.api.ServerEndpoint
import com.cocakova.kouros.core.api.SocketMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * Opens the live socket with the app's exact client configuration (keep it in step with
 * app/.../net/Http.kt — a config difference here once hid a bug that broke every socket).
 * Opt-in: KOUROS_SOCKET_PROBE=url
 */
class SocketProbeTest {
    @Test
    fun probe() = runBlocking {
        val url = System.getenv("KOUROS_SOCKET_PROBE") ?: return@runBlocking
        val ok = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
        val variants = mapOf(
            "app config" to HttpClient(OkHttp) {
                engine { preconfigured = ok }
                install(WebSockets)
                install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 120_000 }
                expectSuccess = false
            },

        )
        for ((name, http) in variants) {
            val r = runCatching {
                withTimeout(15_000) { ComfyClient(http, ServerEndpoint(url)).socket("probe-${System.nanoTime()}").first { it == SocketMessage.Open } }
            }
            println("SOCKET PROBE [$name]: " + r.fold({ "OPEN" }, { e -> "FAILED ${e::class.qualifiedName}: ${e.message}" }))
            r.exceptionOrNull()?.let { var c = it.cause; while (c != null) { println("   cause ${c::class.qualifiedName}: ${c.message}"); c = c.cause } }
        }
    }
}
