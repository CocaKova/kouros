package com.cocakova.kouros.data

import android.content.Context
import com.cocakova.kouros.app

/** Small per-device preferences. */
object Settings {
    private val prefs by lazy { app.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    /**
     * Live previews while sampling, requested per prompt (ComfyUI's `extra_data.preview_method`):
     * "auto" (best available), "latent2rgb" (fast, rough), "none", or "default" (the server's own).
     */
    var previewMethod: String
        get() = prefs.getString("preview_method", "auto") ?: "auto"
        set(v) = prefs.edit().putString("preview_method", v).apply()
}
