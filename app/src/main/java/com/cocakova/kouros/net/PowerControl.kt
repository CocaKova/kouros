package com.cocakova.kouros.net

import com.cocakova.kouros.app
import com.cocakova.kouros.data.Secrets
import com.cocakova.kouros.data.ServerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Optional "power" buttons for a server: plain HTTP requests the user defines — to a launcher, a
 * home-automation hook, a cloud provider's API, anything that can start or stop ComfyUI. The app
 * knows nothing about what is on the other end; it sends the request and shows the answer.
 */
data class PowerControl(
    val startUrl: String?,
    val stopUrl: String?,
    val statusUrl: String?,
    /** Header carrying the secret (e.g. "Authorization" or "x-api-key"); the value lives in [Secrets]. */
    val headerName: String?,
) {
    val isEmpty: Boolean get() = startUrl.isNullOrBlank() && stopUrl.isNullOrBlank() && statusUrl.isNullOrBlank()

    fun toJson(): String = buildJsonObject {
        startUrl?.let { put("start", it) }; stopUrl?.let { put("stop", it) }
        statusUrl?.let { put("status", it) }; headerName?.let { put("header", it) }
    }.toString()

    enum class Action { START, STOP, STATUS }

    data class Result(val ok: Boolean, val code: Int, val text: String)

    suspend fun invoke(server: ServerEntity, action: Action): Result = withContext(Dispatchers.IO) {
        val url = when (action) { Action.START -> startUrl; Action.STOP -> stopUrl; Action.STATUS -> statusUrl }
            ?: return@withContext Result(false, 0, "Not configured")
        val secret = app.secrets.get(Secrets.powerSecret(server.id))
        val req = Request.Builder().url(url).apply {
            if (!headerName.isNullOrBlank() && secret != null) header(headerName, secret)
            if (action == Action.STATUS) get() else post("{}".toRequestBody("application/json".toMediaType()))
        }.build()
        runCatching {
            Http.okhttp.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                // Many endpoints answer {"message": …}; show that, else the raw body.
                val msg = runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.let { o ->
                    listOf("message", "error", "status").firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.contentOrNull }
                } }.getOrNull() ?: body
                Result(r.isSuccessful, r.code, msg.trim().take(300).ifEmpty { "HTTP ${r.code}" })
            }
        }.getOrElse { e -> Result(false, 0, e.message ?: "Request failed") }
    }

    companion object {
        fun parse(s: String?): PowerControl? {
            if (s.isNullOrBlank()) return null
            val o = runCatching { Json.parseToJsonElement(s) as JsonObject }.getOrNull() ?: return null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            return PowerControl(str("start"), str("stop"), str("status"), str("header")).takeIf { !it.isEmpty }
        }
    }
}
