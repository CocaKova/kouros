package com.cocakova.kouros.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.cocakova.kouros.KourosApp
import com.cocakova.kouros.core.api.FileRef
import com.cocakova.kouros.core.api.MediaKind
import com.cocakova.kouros.core.api.OutputItem
import com.cocakova.kouros.core.api.Outputs
import com.cocakova.kouros.net.Http
import com.cocakova.kouros.net.ServerSession
import com.cocakova.kouros.run.RunCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.OutputStream

/**
 * Saves results to the phone's shared gallery (Pictures/, Movies/, Music/ → Kouros) by
 * streaming straight from the server — no storage permission on Android 10+ — and prepares
 * files for the share sheet.
 */
class MediaStoreSaver(private val app: KourosApp) {

    suspend fun saveRun(promptId: String): Int {
        val run = app.db.runs().get(promptId) ?: return 0
        val session = app.sessions.byId(run.serverId) ?: return 0
        val items = RunCoordinator.decodeOutputs(run.outputsJson).filter { it.file != null && it.kind != MediaKind.TEXT }
        return items.count { runCatching { save(session, it) }.getOrNull() != null }
    }

    suspend fun save(session: ServerSession, item: OutputItem): Uri? = withContext(Dispatchers.IO) {
        val ref = item.file ?: return@withContext null
        val mime = Outputs.mimeOf(ref.filename)
        val (collection, dir) = when (item.kind) {
            MediaKind.VIDEO -> collectionFor(MediaStore.Video.Media::class) to Environment.DIRECTORY_MOVIES
            MediaKind.AUDIO -> collectionFor(MediaStore.Audio.Media::class) to Environment.DIRECTORY_MUSIC
            MediaKind.IMAGE, MediaKind.ANIMATED -> collectionFor(MediaStore.Images.Media::class) to Environment.DIRECTORY_PICTURES
            else -> collectionFor(MediaStore.Downloads::class) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, ref.filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Kouros")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val folder = File(Environment.getExternalStoragePublicDirectory(dir), "Kouros").apply { mkdirs() }
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, File(folder, ref.filename).absolutePath)
            }
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(collection, values) ?: return@withContext null
        try {
            resolver.openOutputStream(uri)?.use { out -> download(session, ref, out) } ?: error("no stream")
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** A content:// URI for the share sheet, backed by a cached copy of the server file. */
    suspend fun shareable(session: ServerSession, ref: FileRef): Uri = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, ref.filename.replace('/', '_'))
        if (!file.isFile || file.length() == 0L) file.outputStream().use { download(session, ref, it) }
        FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    private fun download(session: ServerSession, ref: FileRef, out: OutputStream) {
        val req = Request.Builder().url(session.client.viewUrl(ref)).apply {
            session.client.endpoint.headers.forEach { (k, v) -> header(k, v) }
        }.build()
        Http.okhttp.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            r.body!!.byteStream().copyTo(out)
        }
    }

    private fun collectionFor(kind: kotlin.reflect.KClass<*>): Uri = when {
        Build.VERSION.SDK_INT >= 29 -> when (kind) {
            MediaStore.Video.Media::class -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaStore.Audio.Media::class -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaStore.Downloads::class -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        else -> when (kind) {
            MediaStore.Video.Media::class -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaStore.Audio.Media::class -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    }
}
