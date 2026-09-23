package com.cocakova.pygmalion.quick

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.cocakova.pygmalion.app
import com.cocakova.pygmalion.data.RunState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * An invisible trampoline for "run the last thing again" from the tile and the widget. Being a
 * visible activity for a moment is what lets it start the run service on modern Android.
 */
class QuickRunActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val last = app.db.runs().recent(20).first().firstOrNull { it.state == RunState.SUCCEEDED || it.state == RunState.FAILED || it.state == RunState.INTERRUPTED }
            val msg = if (last == null) "Nothing to run again yet" else app.runs.rerun(last.promptId) ?: "Running ${last.workflowName} again"
            Toast.makeText(this@QuickRunActivity, msg, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
