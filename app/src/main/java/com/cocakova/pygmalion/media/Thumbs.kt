package com.cocakova.pygmalion.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.cocakova.pygmalion.app
import com.cocakova.pygmalion.core.api.FileRef
import com.cocakova.pygmalion.core.api.MediaKind
import com.cocakova.pygmalion.core.api.OutputItem
import com.cocakova.pygmalion.net.ServerSession

/** Image requests for server files, with the server's auth headers and a thumbnail-sized fetch. */
object Thumbs {
    /** Images are fetched as server-side webp previews; video/animated as the original file (Coil takes a frame). */
    fun url(session: ServerSession, ref: FileRef, kind: MediaKind, thumbnail: Boolean): String =
        if (thumbnail && kind == MediaKind.IMAGE) session.client.viewUrl(ref, preview = "webp;85")
        else session.client.viewUrl(ref)

    fun headers(session: ServerSession): NetworkHeaders =
        NetworkHeaders.Builder().apply { session.client.endpoint.headers.forEach { (k, v) -> set(k, v) } }.build()

    fun request(context: Context, session: ServerSession, ref: FileRef, kind: MediaKind, thumbnail: Boolean = true): ImageRequest.Builder =
        ImageRequest.Builder(context)
            .data(url(session, ref, kind, thumbnail))
            .httpHeaders(headers(session))
            .memoryCacheKey("${session.server.id}|${ref.type}|${ref.subfolder}|${ref.filename}|$thumbnail")
            .diskCacheKey("${session.server.id}|${ref.type}|${ref.subfolder}|${ref.filename}|$thumbnail")

    /** A bitmap for a notification or widget (software-backed, so it can be parceled). */
    suspend fun load(context: Context, serverId: String, item: OutputItem, size: Int): Bitmap? {
        val ref = item.file ?: return null
        val session = app.sessions.byId(serverId) ?: return null
        val req = request(context, session, ref, item.kind).size(size).allowHardware(false).build()
        val r = SingletonImageLoader.get(context).execute(req) as? SuccessResult ?: return null
        return r.image.toBitmap()
    }

    /** Decodes an encoded preview frame, downsampled so its longest side is about [maxSide]. */
    fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
