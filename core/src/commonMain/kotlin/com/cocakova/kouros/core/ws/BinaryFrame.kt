package com.cocakova.kouros.core.ws

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * One binary message from `/ws`. Layout (ComfyUI `protocol.py` / `server.py`): a 4-byte
 * big-endian event type, then a type-specific body.
 *
 *  - 1 PREVIEW_IMAGE: u32 image format (1 = JPEG, 2 = PNG), then the encoded image.
 *  - 3 TEXT: u32 node-id length, node id (UTF-8), then UTF-8 text.
 *  - 4 PREVIEW_IMAGE_WITH_METADATA: u32 metadata length, metadata JSON (carries `image_type`,
 *    `node_id`, `prompt_id`…), then the encoded image. Sent to clients that announced
 *    `supports_preview_metadata`.
 *
 * The image bytes are returned as-is (no decoding here — :core has no bitmap type). Offsets are
 * kept instead of copies so a multi-megabyte frame is not duplicated just to be parsed.
 */
sealed interface BinaryFrame {
    class Preview(
        val mime: String,
        val frame: ByteArray,
        val imageOffset: Int,
        val promptId: String?,
        val nodeId: String?,
        val displayNodeId: String?,
    ) : BinaryFrame {
        val imageLength: Int get() = frame.size - imageOffset
        fun imageBytes(): ByteArray = frame.copyOfRange(imageOffset, frame.size)
    }

    data class Text(val nodeId: String, val text: String) : BinaryFrame

    data class Unknown(val eventType: Int, val size: Int) : BinaryFrame

    companion object {
        const val PREVIEW_IMAGE = 1
        const val TEXT = 3
        const val PREVIEW_IMAGE_WITH_METADATA = 4

        private val json = Json { ignoreUnknownKeys = true }

        fun decode(frame: ByteArray): BinaryFrame {
            if (frame.size < 4) return Unknown(-1, frame.size)
            return when (val type = u32(frame, 0)) {
                PREVIEW_IMAGE -> {
                    if (frame.size < 8) return Unknown(type, frame.size)
                    val mime = if (u32(frame, 4) == 2) "image/png" else "image/jpeg"
                    Preview(mime, frame, 8, null, null, null)
                }
                PREVIEW_IMAGE_WITH_METADATA -> {
                    if (frame.size < 8) return Unknown(type, frame.size)
                    val metaLen = u32(frame, 4)
                    if (metaLen < 0 || 8 + metaLen > frame.size) return Unknown(type, frame.size)
                    val meta = runCatching {
                        json.parseToJsonElement(frame.decodeToString(8, 8 + metaLen)) as JsonObject
                    }.getOrNull()
                    Preview(
                        mime = meta?.str("image_type") ?: "image/jpeg",
                        frame = frame,
                        imageOffset = 8 + metaLen,
                        promptId = meta?.str("prompt_id"),
                        nodeId = meta?.get("node_id")?.asId(),
                        displayNodeId = meta?.get("display_node_id")?.asId(),
                    )
                }
                TEXT -> {
                    if (frame.size < 8) return Unknown(type, frame.size)
                    val idLen = u32(frame, 4)
                    if (idLen < 0 || 8 + idLen > frame.size) return Unknown(type, frame.size)
                    Text(frame.decodeToString(8, 8 + idLen), frame.decodeToString(8 + idLen, frame.size))
                }
                else -> Unknown(type, frame.size)
            }
        }

        private fun u32(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
    }
}
