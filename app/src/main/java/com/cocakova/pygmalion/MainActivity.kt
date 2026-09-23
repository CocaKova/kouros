package com.cocakova.pygmalion

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.cocakova.pygmalion.ui.PendingOpen
import com.cocakova.pygmalion.ui.PygmalionRoot
import com.cocakova.pygmalion.ui.theme.PygmalionTheme

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 && !app.notifier.canPost()) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { PygmalionTheme { PygmalionRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        app.sessions.kickAll()
    }

    private fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA_RUN)?.let { PendingOpen.run.value = it }
    }

    companion object {
        const val EXTRA_RUN = "run"
    }
}
