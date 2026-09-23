package com.cocakova.kouros

import androidx.activity.SystemBarStyle
import androidx.compose.runtime.LaunchedEffect
import com.cocakova.kouros.ui.theme.isKourosDark
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.cocakova.kouros.ui.PendingOpen
import com.cocakova.kouros.ui.PendingShare
import com.cocakova.kouros.ui.SharedMedia
import com.cocakova.kouros.ui.KourosRoot
import com.cocakova.kouros.ui.theme.KourosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            val dark = isKourosDark()
            // Bar icons follow the app's theme, not the system's, when the two differ.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            KourosTheme(dark) { KourosRoot() }
        }
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
        intent ?: return
        intent.getStringExtra(EXTRA_RUN)?.let { PendingOpen.run.value = it }
        // Shared media: remember it until the person picks a workflow to put it into.
        val uris: List<android.net.Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE -> (
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                ).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) PendingShare.value = SharedMedia(uris, intent.type ?: "image/*")
    }

    companion object {
        const val EXTRA_RUN = "run"
    }
}
