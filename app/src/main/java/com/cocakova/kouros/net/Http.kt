package com.cocakova.kouros.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** One OkHttp stack for the app: REST, sockets and image loading share connections. */
object Http {
    val okhttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS) // keeps NATs and proxies from idling the socket out
            .retryOnConnectionFailure(true)
            .build()
    }

    val ktor: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine { preconfigured = okhttp }
            // No maxFrameSize: the OkHttp engine refuses the setting and fails every socket with
            // "Max frame size switch is not supported" (OkHttp has no frame limit to raise anyway).
            install(WebSockets)
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 120_000
            }
            expectSuccess = false
        }
    }

    /**
     * For answers that take a while to start and stream for a while: an agent may think and call
     * tools for a minute or more before the first word. Same connection pool, patient timeouts.
     */
    val patient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine { preconfigured = okhttp.newBuilder().readTimeout(5, TimeUnit.MINUTES).build() }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 600_000
                socketTimeoutMillis = 300_000
            }
            expectSuccess = false
        }
    }
}
