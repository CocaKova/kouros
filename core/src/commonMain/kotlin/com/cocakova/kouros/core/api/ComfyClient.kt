package com.cocakova.kouros.core.api

import com.cocakova.kouros.core.ws.BinaryFrame
import com.cocakova.kouros.core.ws.WsEvent
import com.cocakova.kouros.core.ws.WsTextParser
import com.cocakova.kouros.core.ws.dbl
import com.cocakova.kouros.core.ws.obj
import com.cocakova.kouros.core.ws.str
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Where a ComfyUI server lives and how to present ourselves to it.
 *
 * [baseUrl] may carry a path prefix (`https://host/comfy`), which reverse proxies commonly add;
 * every route is appended to it. [headers] are sent on every request and on the socket upgrade —
 * that is how bearer tokens, basic auth and custom proxy headers all work without the client
 * knowing which one is in use.
 */
data class ServerEndpoint(val baseUrl: String, val headers: Map<String, String> = emptyMap()) {
    val root: String = baseUrl.trimEnd('/')

    fun http(path: String): String = root + path

    fun ws(path: String): String = when {
        root.startsWith("https://") -> "wss://" + root.removePrefix("https://")
        root.startsWith("http://") -> "ws://" + root.removePrefix("http://")
        else -> root
    } + path
}

class ComfyHttpException(val status: Int, val body: String, message: String) : Exception(message)

sealed interface SubmitResult {
    data class Accepted(val value: PromptAccepted) : SubmitResult
    data class Rejected(val value: PromptRejected) : SubmitResult
}

sealed interface SocketMessage {
    data object Open : SocketMessage
    data class Event(val event: WsEvent) : SocketMessage
    data class Binary(val frame: BinaryFrame) : SocketMessage
}

/**
 * The whole ComfyUI HTTP + WebSocket surface a runner needs, over a caller-supplied Ktor client
 * (the app gives it an OkHttp engine with the WebSockets plugin installed; tests give it a mock).
 */
class ComfyClient(private val http: HttpClient, val endpoint: ServerEndpoint) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun HttpRequestBuilder.auth() {
        endpoint.headers.forEach { (k, v) -> header(k, v) }
    }

    private suspend fun getJson(path: String): JsonElement {
        val r = http.get(endpoint.http(path)) { auth() }
        return json.parseToJsonElement(r.okText(path))
    }

    private suspend fun postJson(path: String, body: JsonElement): HttpResponse =
        http.post(endpoint.http(path)) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

    private suspend fun HttpResponse.okText(what: String): String {
        val text = bodyAsText()
        if (!status.isSuccess()) throw ComfyHttpException(status.value, text, "$what → HTTP ${status.value}")
        return text
    }

    suspend fun systemStats(): SystemStats = SystemStats.parse(getJson("/system_stats") as JsonObject)

    /** `/features`; older servers without the route report nothing rather than failing. */
    suspend fun features(): JsonObject = runCatching { getJson("/features") as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    /** Raw `/object_info` text — callers cache it by hash and parse with [ObjectInfo.parse]. */
    suspend fun objectInfoText(): String = http.get(endpoint.http("/object_info")) { auth() }.okText("/object_info")

    suspend fun queue(): QueueSnapshot = QueueSnapshot.parse(getJson("/queue") as JsonObject)

    suspend fun history(maxItems: Int? = null): List<HistoryEntry> =
        HistoryEntry.parseMap(getJson("/history" + (maxItems?.let { "?max_items=$it" } ?: "")) as JsonObject)
            .asReversed() // the server returns oldest first

    suspend fun historyFor(promptId: String): HistoryEntry? =
        HistoryEntry.parseMap(getJson("/history/${promptId.encodeURLParameter()}") as JsonObject).firstOrNull()

    /** Whether `/api/jobs` exists (newer servers); probed, never assumed. */
    suspend fun hasJobsApi(): Boolean =
        runCatching { http.get(endpoint.http("/api/jobs?limit=1")) { auth() }.status.isSuccess() }.getOrDefault(false)

    /**
     * Queues [prompt]. [promptId] is chosen by the caller (a UUID) so the run can be recorded
     * before the request is sent: if the response is lost, reconciliation still finds it.
     */
    suspend fun submit(
        prompt: JsonObject,
        clientId: String,
        promptId: String?,
        extraData: JsonObject? = null,
        front: Boolean = false,
    ): SubmitResult {
        val body = buildJsonObject {
            put("prompt", prompt)
            put("client_id", clientId)
            if (promptId != null) put("prompt_id", promptId)
            if (extraData != null) put("extra_data", extraData)
            if (front) put("front", true)
        }
        val r = postJson("/prompt", body)
        val text = r.bodyAsText()
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (r.status.isSuccess() && o?.str("prompt_id") != null) {
            return SubmitResult.Accepted(
                PromptAccepted(
                    promptId = o.str("prompt_id")!!,
                    number = (o["number"] as? JsonPrimitive)?.doubleOrNull,
                    nodeErrors = o.obj("node_errors") ?: JsonObject(emptyMap()),
                ),
            )
        }
        if (o != null && (r.status.value == 400 || o.containsKey("error"))) {
            val err = o["error"]
            val (msg, details) = when (err) {
                is JsonObject -> (err.str("message") ?: "Prompt rejected") to err.str("details")
                is JsonPrimitive -> (err.contentOrNull ?: "Prompt rejected") to null
                else -> "Prompt rejected" to null
            }
            return SubmitResult.Rejected(PromptRejected(msg, details, o.obj("node_errors") ?: JsonObject(emptyMap())))
        }
        throw ComfyHttpException(r.status.value, text, "/prompt → HTTP ${r.status.value}")
    }

    /** Interrupts [promptId] only if it is the one running — never someone else's job. */
    suspend fun interrupt(promptId: String) {
        postJson("/interrupt", buildJsonObject { put("prompt_id", promptId) }).okText("/interrupt")
    }

    /** Interrupts whatever is running, whoever queued it (the desktop's "Cancel current"). */
    suspend fun interruptAny() {
        postJson("/interrupt", buildJsonObject { }).okText("/interrupt")
    }

    suspend fun deleteQueued(promptIds: List<String>) {
        postJson("/queue", buildJsonObject { putJsonArray("delete") { promptIds.forEach { add(JsonPrimitive(it)) } } }).okText("/queue")
    }

    suspend fun deleteHistory(promptIds: List<String>) {
        postJson("/history", buildJsonObject { putJsonArray("delete") { promptIds.forEach { add(JsonPrimitive(it)) } } }).okText("/history")
    }

    /** Asks the server to unload models and free memory after the current job. */
    suspend fun free(unloadModels: Boolean = true, freeMemory: Boolean = true) {
        postJson("/free", buildJsonObject { put("unload_models", unloadModels); put("free_memory", freeMemory) }).okText("/free")
    }

    suspend fun clearQueue() {
        postJson("/queue", buildJsonObject { put("clear", true) }).okText("/queue")
    }

    suspend fun clearHistory() {
        postJson("/history", buildJsonObject { put("clear", true) }).okText("/history")
    }

    /** The Kouros Bridge extension, when the server has it (`/kouros/bridge`); null on a stock server. */
    suspend fun bridge(): Bridge? = runCatching {
        val r = http.get(endpoint.http("/kouros/bridge")) { auth() }
        if (!r.status.isSuccess()) return null
        val o = json.parseToJsonElement(r.bodyAsText()) as? JsonObject ?: return null
        Bridge(
            version = (o["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
            features = (o["features"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet(),
        )
    }.getOrNull()

    /** Deletes generated files through the bridge. Stock servers have no way to do this. */
    suspend fun deleteOutputs(files: List<FileRef>): DeleteReport {
        val body = buildJsonObject {
            putJsonArray("files") {
                files.forEach { f -> add(buildJsonObject { put("filename", f.filename); put("subfolder", f.subfolder); put("type", f.type) }) }
            }
        }
        val o = json.parseToJsonElement(postJson("/kouros/outputs/delete", body).okText("/kouros/outputs/delete")) as JsonObject
        fun refs(k: String) = (o[k] as? JsonArray)?.mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            FileRef(x.str("filename") ?: return@mapNotNull null, x.str("subfolder") ?: "", x.str("type") ?: "output")
        } ?: emptyList()
        return DeleteReport(refs("deleted"), refs("missing"), refs("refused"))
    }

    /** What memory the OS can still hand out and which models are loaded (bridge only). */
    suspend fun memory(): BridgeMemory? = runCatching {
        val o = getJson("/kouros/memory") as JsonObject
        BridgeMemory(
            total = o.dbl("total")?.toLong(),
            available = o.dbl("available")?.toLong(),
            models = (o["models"] as? JsonArray)?.mapNotNull { e ->
                val m = e as? JsonObject ?: return@mapNotNull null
                LoadedModel(m.str("name") ?: "model", m.dbl("size")?.toLong() ?: 0, m.dbl("loaded")?.toLong() ?: 0)
            } ?: emptyList(),
        )
    }.getOrNull()

    /** The server's recent console output (`/internal/logs/raw`), oldest first. */
    suspend fun logs(): List<LogLine> {
        val o = getJson("/internal/logs/raw") as? JsonObject ?: return emptyList()
        return (o["entries"] as? JsonArray)?.mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            LogLine(x.str("t") ?: "", x.str("m") ?: "")
        } ?: emptyList()
    }

    /** Model folder names (`/models`), and the files in one (`/models/{folder}`). */
    suspend fun modelFolders(): List<String> =
        (getJson("/models") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    suspend fun models(folder: String): List<String> =
        (getJson("/models/${folder.encodeURLParameter()}") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    suspend fun listUserdata(dir: String = "workflows"): List<UserdataFile> {
        val el = runCatching { getJson("/userdata?dir=${dir.encodeURLParameter()}&recurse=true&full_info=true") }
            .getOrElse { e -> if (e is ComfyHttpException && e.status == 404) return emptyList() else throw e }
        return (el as? JsonArray)?.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            UserdataFile(
                path = o.str("path") ?: return@mapNotNull null,
                size = (o["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
                modified = (o["modified"] as? JsonPrimitive)?.doubleOrNull,
            )
        } ?: emptyList()
    }

    /** Userdata paths are one URL segment: `workflows/a b.json` → `workflows%2Fa%20b.json`. */
    suspend fun readUserdata(path: String): String =
        http.get(endpoint.http("/userdata/" + path.encodeURLParameter())) { auth() }.okText("/userdata")

    suspend fun deleteUserdata(path: String) {
        http.delete(endpoint.http("/userdata/" + path.encodeURLParameter())) { auth() }.okText("/userdata delete")
    }

    /** Renames or moves a userdata file; fails (409) rather than overwrite unless asked. */
    suspend fun moveUserdata(from: String, to: String, overwrite: Boolean = false) {
        http.post(endpoint.http("/userdata/" + from.encodeURLParameter() + "/move/" + to.encodeURLParameter() + "?overwrite=$overwrite")) { auth() }
            .okText("/userdata move")
    }

    suspend fun writeUserdata(path: String, content: String, overwrite: Boolean) {
        http.post(endpoint.http("/userdata/" + path.encodeURLParameter() + "?overwrite=$overwrite")) {
            auth()
            contentType(ContentType.Application.Json)
            setBody(content)
        }.okText("/userdata")
    }

    /**
     * Uploads a file into the server's input folder (images, and — on current servers — video
     * and audio too, through the same route). Returns where it landed; the name may differ
     * from [filename] when [overwrite] is false and the name was taken.
     */
    suspend fun upload(
        bytes: ByteArray,
        filename: String,
        mime: String,
        subfolder: String = "",
        overwrite: Boolean = false,
        type: String = "input",
    ): FileRef {
        val r = http.post(endpoint.http("/upload/image")) {
            auth()
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("image", bytes, Headers.build {
                            append(HttpHeaders.ContentType, mime)
                            append(HttpHeaders.ContentDisposition, "filename=\"${filename.replace("\"", "")}\"")
                        })
                        append("type", type)
                        append("subfolder", subfolder)
                        append("overwrite", overwrite.toString())
                    },
                ),
            )
        }
        val o = json.parseToJsonElement(r.okText("/upload/image")) as JsonObject
        return FileRef(o.str("name") ?: filename, o.str("subfolder") ?: subfolder, o.str("type") ?: type)
    }

    /**
     * The URL of a file. [preview] asks the server for a re-encoded thumbnail (e.g. "webp;80"),
     * which it only does for images; [channel] "rgb" drops alpha.
     */
    fun viewUrl(ref: FileRef, preview: String? = null, channel: String? = null): String = buildString {
        append(endpoint.http("/view?filename=")).append(ref.filename.encodeURLParameter())
        append("&subfolder=").append(ref.subfolder.encodeURLParameter())
        append("&type=").append(ref.type.encodeURLParameter())
        if (preview != null) append("&preview=").append(preview.encodeURLParameter())
        if (channel != null) append("&channel=").append(channel)
    }

    /**
     * The live socket. Sends the `feature_flags` hello first (so the server uses metadata-rich
     * previews), then emits everything it receives until the socket closes; the caller owns
     * reconnect policy. Collecting it again reconnects.
     */
    fun socket(clientId: String): Flow<SocketMessage> = flow {
        http.webSocket(
            urlString = endpoint.ws("/ws?clientId=${clientId.encodeURLParameter()}"),
            request = { auth() },
        ) {
            send(Frame.Text(WsTextParser.featureFlagsHello()))
            emit(SocketMessage.Open)
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> WsTextParser.parse(frame.readText())?.let { emit(SocketMessage.Event(it)) }
                    is Frame.Binary -> emit(SocketMessage.Binary(BinaryFrame.decode(frame.readBytes())))
                    else -> Unit
                }
            }
        }
    }
}
