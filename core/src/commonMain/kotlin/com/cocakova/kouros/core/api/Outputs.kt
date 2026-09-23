package com.cocakova.kouros.core.api

import com.cocakova.kouros.core.ws.bool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class MediaKind { IMAGE, ANIMATED, VIDEO, AUDIO, MODEL3D, TEXT, FILE }

/** One thing a node produced that a person would want to see, hear or save. */
data class OutputItem(
    val nodeId: String,
    val kind: MediaKind,
    val file: FileRef?,
    val text: String? = null,
    /** Temp outputs (PreviewImage) are overwritten by later runs; only "output" files are durable. */
    val isTemp: Boolean = file?.type == "temp",
)

/**
 * Turns a node's UI output object into [OutputItem]s — for any node, from any pack.
 *
 * Classification uses, in order: the key the node filed the entry under (`images`, `gifs`,
 * `video`, `audio`, `3d`, `text`…), the entry's own `format` / `animated` hints, then the file
 * extension. Unknown keys holding file refs still come out, as [MediaKind.FILE], so a node pack
 * nobody has heard of still shows its results.
 */
object Outputs {
    fun classify(nodeId: String, output: JsonObject): List<OutputItem> {
        val items = mutableListOf<OutputItem>()
        val animatedFlags = (output["animated"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull == "true" }
        for ((key, value) in output) {
            val list = value as? JsonArray ?: continue
            list.forEachIndexed { i, el ->
                when (el) {
                    is JsonObject -> {
                        val ref = FileRef.parse(el) ?: return@forEachIndexed
                        val kind = kindOf(key, el, ref.filename, animatedFlags?.getOrNull(i) == true)
                        items += OutputItem(nodeId, kind, ref)
                    }
                    is JsonPrimitive -> if (key == "text" || key == "string") {
                        el.contentOrNull?.let { items += OutputItem(nodeId, MediaKind.TEXT, null, text = it) }
                    }
                    else -> Unit
                }
            }
        }
        return items
    }

    /**
     * The order a person should meet a run's outputs in: the thing they asked for first.
     *
     * Some workflows hand back working notes beside the result — YuE2's ABC plan, a PreviewAny
     * dump — and filed in node order those can land in front of the music. Media leads, loose
     * files follow, text last. Leftover previews of an image already shown are dropped.
     * Callers that index into a run's outputs (the gallery's tiles, the viewer's pager) must all
     * go through this, or a tile opens the wrong page.
     */
    fun forViewing(items: List<OutputItem>): List<OutputItem> =
        items.filter { !it.isTemp || it.kind != MediaKind.IMAGE }.sortedBy { rank(it.kind) }

    private fun rank(kind: MediaKind) = when (kind) {
        MediaKind.IMAGE, MediaKind.ANIMATED, MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.MODEL3D -> 0
        MediaKind.FILE -> 1
        MediaKind.TEXT -> 2
    }

    fun kindOf(key: String, entry: JsonObject?, filename: String, animated: Boolean = false): MediaKind {
        val format = (entry?.get("format") as? JsonPrimitive)?.contentOrNull.orEmpty()
        byExtension(filename)?.let { ext ->
            if (ext == MediaKind.IMAGE && (animated || entry?.bool("animated") == true)) return MediaKind.ANIMATED
            return ext
        }
        if (format.startsWith("video/")) return MediaKind.VIDEO
        if (format.startsWith("audio/")) return MediaKind.AUDIO
        if (format.startsWith("image/")) return if (format.endsWith("gif") || format.endsWith("webp")) MediaKind.ANIMATED else MediaKind.IMAGE
        return when (key) {
            "images" -> if (animated) MediaKind.ANIMATED else MediaKind.IMAGE
            "gifs", "animated" -> MediaKind.ANIMATED
            "video", "videos" -> MediaKind.VIDEO
            "audio" -> MediaKind.AUDIO
            "3d", "mesh", "model_file" -> MediaKind.MODEL3D
            else -> MediaKind.FILE
        }
    }

    fun byExtension(filename: String): MediaKind? = when (filename.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "bmp", "tif", "tiff", "avif", "heic", "exr" -> MediaKind.IMAGE
        "gif", "apng" -> MediaKind.ANIMATED
        "webp" -> MediaKind.IMAGE
        "mp4", "webm", "mov", "mkv", "avi", "m4v" -> MediaKind.VIDEO
        "mp3", "wav", "flac", "ogg", "opus", "m4a", "aac" -> MediaKind.AUDIO
        "glb", "gltf", "obj", "fbx", "ply", "stl" -> MediaKind.MODEL3D
        "txt", "json", "md" -> MediaKind.TEXT
        else -> null
    }

    /**
     * One entry of the server's file listing (`/internal/files/output`): "sub/name.png [output]".
     * Only media a person would open; placeholders and stray files are skipped.
     */
    fun fromListing(entry: String): OutputItem? {
        val path = entry.substringBeforeLast(" [").trim().replace('\\', '/')
        val type = entry.substringAfterLast(" [", "output]").removeSuffix("]").ifBlank { "output" }
        val name = path.substringAfterLast('/')
        val kind = byExtension(name)?.takeIf { it != MediaKind.TEXT } ?: return null
        return OutputItem("", kind, FileRef(name, path.substringBeforeLast('/', ""), type))
    }

    fun mimeOf(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"
        "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "mov" -> "video/quicktime"; "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "flac" -> "audio/flac"; "ogg", "opus" -> "audio/ogg"; "m4a", "aac" -> "audio/mp4"
        "glb" -> "model/gltf-binary"; "json" -> "application/json"; "txt", "md" -> "text/plain"
        else -> "application/octet-stream"
    }
}
