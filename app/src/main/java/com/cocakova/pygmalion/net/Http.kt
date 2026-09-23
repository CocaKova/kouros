package com.cocakova.pygmalion.net

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
            install(WebSockets) { maxFrameSize = 64L * 1024 * 1024 }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 120_000
            }
            expectSuccess = false
        }
    }
}
