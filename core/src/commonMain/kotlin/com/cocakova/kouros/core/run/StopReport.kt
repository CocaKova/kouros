package com.cocakova.kouros.core.run

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Why a run stopped short, gathered from the server at the moment it happened.
 *
 * A run that ends with nothing to show is the worst thing an app can do quietly: the phone looks
 * broken when the server made a decision. So when a run does not succeed, Kouros writes down who
 * stopped it, where it had got to, what the server had left, and — verbatim — what the server's
 * own log said at that moment. Nothing here interprets the server: it repeats it.
 */
@Serializable
data class StopReport(
    val kind: Kind,
    /** The node it had reached, as the workflow names it. */
    val nodeTitle: String? = null,
    val nodeType: String? = null,
    /** The failure's own words, when there were any. */
    val message: String? = null,
    /** Memory the server could still hand out, when its bridge could say. */
    val availableMemory: Long? = null,
    /** The server's last log lines, as they were written. */
    val serverSaid: List<String> = emptyList(),
) {
    enum class Kind { BY_YOU, BY_SERVER, FAILED, LOST }

    /** One line for a notification or a row. */
    val headline: String
        get() = when (kind) {
            Kind.BY_YOU -> "You stopped this run"
            Kind.BY_SERVER -> "The server stopped this run" + (where?.let { " at $it" } ?: "")
            Kind.FAILED -> (where ?: "The run") + " failed"
            Kind.LOST -> "The server no longer knows this run"
        }

    /** The sentence under it: what it means, and what to do about it. */
    val detail: String?
        get() = when (kind) {
            Kind.BY_YOU -> null
            Kind.BY_SERVER -> buildString {
                append("Kouros didn't stop it and nothing is wrong with the app — something on the server ended the run before it could finish.")
                memoryLine?.let { append(" ") ; append(it) }
            }
            Kind.FAILED -> message
            Kind.LOST -> "It fell out of the server's history — usually a restart. If it had finished, the result may still be in the gallery."
        }

    /** What the server had left, in words, when it could be read. */
    val memoryLine: String?
        get() = availableMemory?.let { "It had ${gb(it)} of memory left." }

    private val where: String? get() = nodeTitle ?: nodeType

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun decode(text: String?): StopReport? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }

        /**
         * The log lines worth keeping: the tail, cleaned of terminal colour and of the frames a
         * preview leaves behind, newest last. The server may say nothing at all, which is itself
         * worth showing — it means the stop left no trace.
         */
        fun lastWords(lines: List<String>, limit: Int = 8): List<String> =
            lines.asSequence()
                .map { plain(it).trimEnd() }
                .filter { it.isNotBlank() && !BINARY.containsMatchIn(it) }
                .toList()
                .takeLast(limit)

        /** A log line without its terminal colour codes. */
        fun plain(line: String): String = ANSI.replace(line, "")

        private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        /** ComfyUI logs preview frames as "[512B blob data]"; they say nothing to a person. */
        private val BINARY = Regex("""^\[\d+(\.\d+)?[KMG]?B blob data]$""")

        private fun gb(bytes: Long): String =
            if (bytes >= 1_000_000_000L) "${(bytes / 100_000_000L) / 10.0} GB" else "${bytes / 1_000_000L} MB"
    }
}
