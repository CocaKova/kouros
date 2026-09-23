package com.cocakova.kouros.core.assist

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Any OpenAI-compatible chat endpoint (`…/v1`): an agent gateway such as Hermes, which answers
 * with its own memory and tools behind it, or a plain model server (Ollama, LM Studio, vLLM,
 * llama.cpp, a hosted API). [model] null means "the first model the endpoint lists".
 */
data class AssistEndpoint(val baseUrl: String, val apiKey: String? = null, val model: String? = null) {
    val root: String = baseUrl.trimEnd('/')
}

/** What the assistant is told about the prompt it is rewriting. */
data class AssistRequest(
    val current: String,
    /** The person's nudge ("make it night", "use what you know about X"); may be blank. */
    val instruction: String,
    val negative: Boolean = false,
    val workflowName: String = "",
    /** image / video / audio, when the workflow's outputs say so. */
    val outputKind: String? = null,
    /** Model files the workflow loads (checkpoints, UNets, LoRAs) — hints at the prompt style that works. */
    val models: List<String> = emptyList(),
    /** The other prompt of the pair (the negative when enhancing the positive, and vice versa). */
    val counterpart: String? = null,
)

sealed interface AssistUpdate {
    /** Something the endpoint is doing before or between words: thinking, calling a tool. */
    data class Status(val text: String) : AssistUpdate
    /** The reply so far (whole text, not a delta). */
    data class Partial(val text: String) : AssistUpdate
    /** The finished reply and the prompt pulled out of it. */
    data class Done(val prompt: String, val raw: String) : AssistUpdate
}

class AssistException(message: String) : Exception(message)

class PromptAssist(private val http: HttpClient, val endpoint: AssistEndpoint) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        endpoint.apiKey?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    /** The model ids the endpoint offers (`GET /models`). */
    suspend fun models(): List<String> {
        val r = http.get("${endpoint.root}/models") { auth() }
        if (!r.status.isSuccess()) throw AssistException(describe(r.status.value, r.bodyAsText()))
        val data = (json.parseToJsonElement(r.bodyAsText()) as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        return data.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
    }

    /** Streams one rewrite of [req.current]. The flow ends with [AssistUpdate.Done] or throws. */
    fun enhance(req: AssistRequest, systemPrompt: String = DEFAULT_SYSTEM): Flow<AssistUpdate> = flow {
        val model = endpoint.model?.takeIf { it.isNotBlank() } ?: models().firstOrNull()
            ?: throw AssistException("The endpoint lists no models — set one in Settings")
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", systemPrompt) }
                addJsonObject { put("role", "user"); put("content", userMessage(req)) }
            }
        }
        emit(AssistUpdate.Status("Asking $model"))
        http.preparePost("${endpoint.root}/chat/completions") {
            auth()
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "text/event-stream")
            setBody(body.toString())
        }.execute { r ->
            if (!r.status.isSuccess()) throw AssistException(describe(r.status.value, r.bodyAsText()))
            val isStream = r.headers[HttpHeaders.ContentType]?.startsWith("text/event-stream") == true
            val raw = if (!isStream) {
                // Some servers ignore "stream": the whole reply arrives as one JSON object.
                SseParser.message(json, r.bodyAsText()) ?: ""
            } else {
                val parser = SseParser(json)
                val ch = r.bodyAsChannel()
                while (true) {
                    val line = ch.readUTF8Line() ?: break
                    parser.feed(line)?.let { emit(it) }
                    if (parser.finished) break
                }
                parser.text
            }
            if (raw.isBlank()) throw AssistException("The assistant answered with nothing")
            emit(AssistUpdate.Done(extract(raw), raw))
        }
    }

    /**
     * Server-sent events, one line at a time. Text comes from `choices[0].delta.content`;
     * reasoning deltas and named events (a gateway's tool progress) become status lines.
     */
    class SseParser(private val json: Json) {
        private val sb = StringBuilder()
        private var event: String? = null
        var finished = false
            private set
        val text: String get() = sb.toString()

        fun feed(line: String): AssistUpdate? {
            if (line.isEmpty()) { event = null; return null }
            if (line.startsWith("event:")) { event = line.removePrefix("event:").trim(); return null }
            if (!line.startsWith("data:")) return null
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") { finished = true; return null }
            val o = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
            val named = event?.takeIf { it != "message" }
            if (named != null) return statusOf(o)
            val delta = ((o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("delta") as? JsonObject ?: return null
            (delta["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                sb.append(it); return AssistUpdate.Partial(sb.toString())
            }
            val reasoning = (delta["reasoning_content"] ?: delta["reasoning"]) as? JsonPrimitive
            if (reasoning?.contentOrNull?.isNotBlank() == true && sb.isEmpty()) return AssistUpdate.Status("Thinking")
            return null
        }

        private fun statusOf(o: JsonObject): AssistUpdate.Status? {
            if ((o["status"] as? JsonPrimitive)?.contentOrNull == "completed") return null
            val label = listOf("label", "message", "text", "tool").firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
            return label?.let { AssistUpdate.Status(it.take(80)) }
        }

        companion object {
            /** The assistant text of a non-streamed chat completion. */
            fun message(json: Json, body: String): String? {
                val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
                val msg = ((o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
                return (msg?.get("content") as? JsonPrimitive)?.contentOrNull
            }
        }
    }

    companion object {
        val DEFAULT_SYSTEM = """
            You rewrite prompts for generative image, video and audio models.
            Keep the person's subject and intent. Make the prompt concrete: subject, setting, composition,
            lighting, lens or camera, materials, mood, style — whatever the medium calls for. Follow their
            instruction when they give one. If they mention people, places, projects or things you know
            about, use what you know to describe them accurately.
            Write in the plain descriptive register the named model expects; no preamble, no options,
            no markdown. Put the finished prompt, and nothing else, between <prompt> and </prompt>.
        """.trimIndent()

        fun userMessage(req: AssistRequest): String = buildString {
            val what = if (req.negative) "negative prompt (what the model should avoid)" else "prompt"
            append("Rewrite this $what")
            if (req.workflowName.isNotBlank()) append(" for the workflow \"${req.workflowName}\"")
            req.outputKind?.let { append(", which makes $it") }
            append(".\n")
            if (req.models.isNotEmpty()) append("Models it loads: ${req.models.joinToString(", ")}\n")
            append("\nCurrent $what:\n")
            append(req.current.ifBlank { "(empty — write one from the instruction)" })
            append('\n')
            req.counterpart?.takeIf { it.isNotBlank() }?.let {
                append("\nFor reference, the ${if (req.negative) "positive prompt" else "negative prompt"}:\n$it\n")
            }
            if (req.instruction.isNotBlank()) append("\nInstruction: ${req.instruction.trim()}\n")
        }

        /**
         * The prompt inside the last `<prompt>…</prompt>` pair; without tags, the reply minus
         * code fences and surrounding quotes. Agents sometimes narrate before answering, so the
         * last tagged block wins.
         */
        fun extract(raw: String): String {
            val tagged = Regex("<prompt>(.*?)</prompt>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(raw).lastOrNull()?.groupValues?.get(1)
            val open = raw.lastIndexOf("<prompt>", ignoreCase = true)
            val body = tagged ?: if (open >= 0) raw.substring(open + 8) else raw
            return body.trim()
                .removePrefix("```").removeSuffix("```").trim()
                .let { if (it.length > 1 && it.first() == '"' && it.last() == '"') it.substring(1, it.length - 1) else it }
                .trim()
        }

        private fun describe(status: Int, body: String): String {
            val detail = body.take(300).trim()
            return when (status) {
                401, 403 -> "The endpoint refused the API key ($status)"
                404 -> "No chat endpoint at that address (404) — the URL usually ends in /v1"
                else -> "The endpoint answered $status" + if (detail.isNotEmpty()) ": $detail" else ""
            }
        }
    }
}
