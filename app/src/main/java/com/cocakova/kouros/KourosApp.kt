package com.cocakova.kouros

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.cocakova.kouros.data.KourosDb
import com.cocakova.kouros.data.Secrets
import com.cocakova.kouros.net.Http
import com.cocakova.kouros.net.Sessions
import com.cocakova.kouros.notify.Notifier
import com.cocakova.kouros.run.RunCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide singletons, created lazily so a cold start does nothing on the main thread beyond
 * what the first screen needs. No DI framework: the graph is small and explicit.
 */
class KourosApp : Application(), SingletonImageLoader.Factory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: KourosDb by lazy { KourosDb.open(this) }
    val secrets: Secrets by lazy { Secrets(this) }
    val sessions: Sessions by lazy { Sessions(this) }
    val notifier: Notifier by lazy { Notifier(this) }
    val runs: RunCoordinator by lazy { RunCoordinator(this) }
    val workflows: com.cocakova.kouros.data.WorkflowRepo by lazy { com.cocakova.kouros.data.WorkflowRepo(this) }
    val media: com.cocakova.kouros.media.MediaStoreSaver by lazy { com.cocakova.kouros.media.MediaStoreSaver(this) }
    val library: com.cocakova.kouros.data.MediaManager by lazy { com.cocakova.kouros.data.MediaManager(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Network came back: wake any session sleeping out a backoff.
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = sessions.kickAll()
        })
        // Runs that were in flight when the process died: re-attach (the service restarts itself).
        runs.resumeAfterProcessDeath()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { Http.okhttp }))
                add(AnimatedImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(512L * 1024 * 1024).build() }
            .crossfade(true)
            .build()

    companion object {
        lateinit var instance: KourosApp
            private set
    }
}

val app: KourosApp get() = KourosApp.instance
