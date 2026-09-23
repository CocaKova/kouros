package com.cocakova.kouros.run

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.cocakova.kouros.app
import com.cocakova.kouros.media.Thumbs
import com.cocakova.kouros.notify.Notifier
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the process alive exactly while a run is active, so progress survives the app leaving
 * the screen. Type `dataSync`: it follows remote job state and fetches results. When Android
 * ends a dataSync service (its daily time budget), runs are handed to [ReconcileWorker], which
 * checks in periodically instead — slower, but nothing is lost.
 */
class RunService : LifecycleService() {
    private var lastBitmap: Bitmap? = null
    private var lastBitmapAt = 0L

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        lifecycleScope.launch {
            // Preview frames → a small bitmap, at most every few seconds (skipped on battery saver).
            app.runs.preview.collect { f ->
                val now = System.currentTimeMillis()
                if (now - lastBitmapAt < 4000 || powerSaving()) return@collect
                lastBitmapAt = now
                lastBitmap = Thumbs.decode(f.bytes, 256)
            }
        }
        lifecycleScope.launch {
            val names = app.db.runs().active().map { list -> list.associate { it.promptId to it.workflowName } }
            app.runs.progress
                .sample(1000) // the shade redraws at most once a second
                .combine(names) { p, n -> p to n }
                .distinctUntilChanged()
                .collectLatest { (progress, n) ->
                    if (progress.isEmpty()) { stopNow(); return@collectLatest }
                    if (app.notifier.canPost()) {
                        getSystemService(android.app.NotificationManager::class.java)
                            .notify(Notifier.LIVE_ID, app.notifier.live(progress.values.toList(), n, lastBitmap))
                    }
                }
        }
    }

    private fun startInForeground() {
        val n = app.notifier.live(app.runs.progress.value.values.toList(), emptyMap(), null)
        ServiceCompat.startForeground(
            this, Notifier.LIVE_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopNow()
        return START_STICKY
    }

    /** Android 15+: the dataSync budget ran out. Hand over to periodic checks and stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ReconcileWorker.schedule(this)
        stopNow()
    }

    private fun stopNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun powerSaving(): Boolean = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    companion object {
        private const val ACTION_STOP = "stop"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RunService::class.java)) }
                .onFailure { ReconcileWorker.schedule(context) } // not allowed from the background
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, RunService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
