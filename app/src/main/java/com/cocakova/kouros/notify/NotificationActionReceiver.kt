package com.cocakova.kouros.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.cocakova.kouros.app
import kotlinx.coroutines.launch

/** Notification buttons: cancel a run, save its results, or run it again with fresh seeds. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_RUN) ?: return
        val pending = goAsync()
        app.appScope.launch {
            try {
                when (intent.action) {
                    CANCEL -> app.runs.cancel(id)
                    SAVE -> {
                        val n = app.media.saveRun(id)
                        toast(context, if (n > 0) "Saved $n to your gallery" else "Nothing to save")
                    }
                    RERUN -> {
                        app.notifier.dismiss(id)
                        val r = app.runs.rerun(id)
                        if (r != null) toast(context, r)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun toast(context: Context, text: String) {
        android.os.Handler(context.mainLooper).post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val EXTRA_RUN = "run"
        const val CANCEL = "com.cocakova.kouros.CANCEL"
        const val SAVE = "com.cocakova.kouros.SAVE"
        const val RERUN = "com.cocakova.kouros.RERUN"
    }
}
