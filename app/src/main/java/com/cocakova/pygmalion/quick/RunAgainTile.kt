package com.cocakova.pygmalion.quick

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.cocakova.pygmalion.app
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Quick Settings: run the last workflow again, with fresh seeds, without opening the app. */
class RunAgainTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        app.appScope.launch {
            val last = app.db.runs().recent(1).first().firstOrNull()
            qsTile?.apply {
                state = if (last == null) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= 29) subtitle = last?.workflowName ?: "Nothing yet"
                updateTile()
            }
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, QuickRunActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
