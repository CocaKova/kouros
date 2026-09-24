package com.cocakova.kouros.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cocakova.kouros.MainActivity
import com.cocakova.kouros.KourosApp
import com.cocakova.kouros.R
import com.cocakova.kouros.core.api.MediaKind
import com.cocakova.kouros.core.api.OutputItem
import com.cocakova.kouros.core.run.RunPhase
import com.cocakova.kouros.core.run.RunProgress
import com.cocakova.kouros.data.RunEntity
import com.cocakova.kouros.core.run.StopReport
import com.cocakova.kouros.data.RunState
import com.cocakova.kouros.media.Thumbs
import kotlinx.coroutines.launch

/**
 * Every notification the app posts. Channels:
 *  - `runs_live`  (low, silent)  — the ongoing "making it" notification of the run service
 *  - `runs_done`  (default)      — a run finished, with its picture
 *  - `runs_error` (high)         — a run failed
 */
class Notifier(private val app: KourosApp) {
    private val nm = app.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_LIVE, "Runs in progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Live progress while a workflow runs"
                    setShowBadge(false)
                },
                NotificationChannel(CH_DONE, "Finished runs", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A workflow finished"
                    setSound(null, null)
                },
                NotificationChannel(CH_ERROR, "Failed runs", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A workflow failed or the server lost it"
                },
            ),
        )
    }

    fun canPost(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openIntent(promptId: String?): PendingIntent = PendingIntent.getActivity(
        app, promptId?.hashCode() ?: 0,
        Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            promptId?.let { putExtra(MainActivity.EXTRA_RUN, it) }
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(action: String, promptId: String): PendingIntent = PendingIntent.getBroadcast(
        app, (action + promptId).hashCode(),
        Intent(app, NotificationActionReceiver::class.java).setAction(action).putExtra(NotificationActionReceiver.EXTRA_RUN, promptId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The ongoing notification for the run service. On Android 16+ it is a ProgressStyle
     * notification that asks to be promoted (status-bar chip with the percentage, lock screen);
     * below that, a classic progress bar. [names] maps prompt ids to workflow names.
     */
    fun live(runs: List<RunProgress>, names: Map<String, String>, preview: Bitmap?): Notification {
        val lead = runs.firstOrNull { it.phase == RunPhase.RUNNING } ?: runs.firstOrNull()
        val name = lead?.let { names[it.promptId] } ?: "Workflow"
        val pct = lead?.let { (it.fraction * 100).toInt() } ?: 0
        val title = when {
            lead == null -> "Kouros"
            lead.phase == RunPhase.QUEUED || lead.phase == RunPhase.SUBMITTING -> "$name · queued" + (lead.queueAhead?.takeIf { it > 0 }?.let { " ($it ahead)" } ?: "")
            else -> "$name · $pct%"
        }
        val text = when {
            lead == null -> ""
            lead.phase != RunPhase.RUNNING -> "Waiting for the server"
            lead.steps > 0 -> "${lead.currentTitle ?: "Working"} · step ${lead.step}/${lead.steps}"
            else -> lead.currentTitle ?: "Working"
        } + if (runs.size > 1) " · ${runs.size - 1} more" else ""

        if (Build.VERSION.SDK_INT >= 36 && lead != null) {
            val style = Notification.ProgressStyle()
                .setProgress(pct)
                .setStyledByProgress(true)
                .setProgressTrackerIcon(Icon.createWithResource(app, R.drawable.ic_stat_kouros))
            if (lead.totalNodes > 0) {
                // One segment per expected node: the bar reads as "which part of the graph".
                val seg = (100 / lead.totalNodes).coerceAtLeast(1)
                style.setProgressSegments(List(lead.totalNodes.coerceAtMost(100 / seg)) { Notification.ProgressStyle.Segment(seg).setColor(CLAY) })
            }
            val b = Notification.Builder(app, CH_LIVE)
                .setSmallIcon(R.drawable.ic_stat_kouros)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(style)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openIntent(lead.promptId))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setColor(CLAY)
                .setShortCriticalText(if (lead.phase == RunPhase.RUNNING) "$pct%" else "queued")
                // Ask for promotion (status-bar chip, lock screen) — Notification.EXTRA_REQUEST_PROMOTED_ONGOING.
                .addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
                .addAction(Notification.Action.Builder(null, "Cancel", action(NotificationActionReceiver.CANCEL, lead.promptId)).build())
            if (preview != null) b.setLargeIcon(preview)
            return b.build()
        }
        val b = NotificationCompat.Builder(app, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_kouros)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setColor(CLAY)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openIntent(lead?.promptId))
            .setProgress(100, pct, lead == null || lead.phase != RunPhase.RUNNING)
        lead?.let { b.addAction(0, "Cancel", action(NotificationActionReceiver.CANCEL, it.promptId)) }
        preview?.let { b.setLargeIcon(it) }
        return b.build()
    }

    /** A run ended: picture and actions for success, the reason for failure. */
    fun finished(run: RunEntity, outputs: List<OutputItem>) {
        if (!canPost()) return
        val id = run.promptId.hashCode()
        if (run.state == RunState.SUCCEEDED) {
            val first = outputs.firstOrNull { it.kind == MediaKind.IMAGE || it.kind == MediaKind.ANIMATED || it.kind == MediaKind.VIDEO }
            val secs = run.startedAt?.let { s -> run.finishedAt?.let { f -> ((f - s) / 1000).coerceAtLeast(0) } }
            val b = NotificationCompat.Builder(app, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat_kouros)
                .setContentTitle(run.workflowName)
                .setContentText(
                    listOfNotNull(
                        "${outputs.size} result" + if (outputs.size == 1) "" else "s",
                        secs?.let { "in ${formatDuration(it)}" },
                    ).joinToString(" "),
                )
                .setColor(VERDIGRIS)
                .setAutoCancel(true)
                .setContentIntent(openIntent(run.promptId))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .addAction(0, "Save", action(NotificationActionReceiver.SAVE, run.promptId))
                .addAction(0, "Run again", action(NotificationActionReceiver.RERUN, run.promptId))
            nm.notify(id, b.build())
            if (first?.file != null) app.appScope.launch {
                // The picture arrives a moment later; the notification updates in place.
                val bmp = Thumbs.load(app, run.serverId, first, 720) ?: return@launch
                b.setLargeIcon(bmp).setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as Bitmap?))
                if (canPost()) nm.notify(id, b.build())
            }
        } else if (run.state in setOf(RunState.FAILED, RunState.LOST, RunState.INTERRUPTED)) {
            val report = StopReport.decode(run.stoppedJson)
            // A run this phone stopped needs no notification: whoever stopped it was looking.
            if (report?.kind == StopReport.Kind.BY_YOU) return
            val headline = report?.headline ?: run.error ?: "The run did not finish"
            val detail = listOfNotNull(report?.detail ?: run.error, report?.serverSaid?.lastOrNull()).joinToString("\n\n")
            val b = NotificationCompat.Builder(app, CH_ERROR)
                .setSmallIcon(R.drawable.ic_stat_kouros)
                .setContentTitle("${run.workflowName} — $headline")
                .setContentText(detail.ifBlank { headline })
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail.ifBlank { headline }))
                .setColor(if (report?.kind == StopReport.Kind.BY_SERVER) CLAY else ERROR)
                .setAutoCancel(true)
                .setContentIntent(openIntent(run.promptId))
                .addAction(0, "Try again", action(NotificationActionReceiver.RERUN, run.promptId))
            nm.notify(id, b.build())
        }
    }

    fun dismiss(promptId: String) = nm.cancel(promptId.hashCode())

    companion object {
        const val CH_LIVE = "runs_live"
        const val CH_DONE = "runs_done"
        const val CH_ERROR = "runs_error"
        const val LIVE_ID = 1
        // Shade colors: the shade follows the system theme, not ours — mid-tones that read on both.
        const val CLAY = 0xFFC8663F.toInt()
        const val VERDIGRIS = 0xFF3E8E7E.toInt()
        const val ERROR = 0xFFC0443A.toInt()

        fun formatDuration(s: Long): String = when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }
}
