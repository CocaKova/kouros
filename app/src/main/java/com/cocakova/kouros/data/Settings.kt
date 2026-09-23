package com.cocakova.kouros.data

import android.content.Context
import com.cocakova.kouros.app
import com.cocakova.kouros.core.assist.AssistEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    private val _theme by lazy { MutableStateFlow(runCatching { ThemeMode.valueOf(prefs.getString("theme", null)!!) }.getOrDefault(ThemeMode.SYSTEM)) }
    val theme: StateFlow<ThemeMode> get() = _theme.asStateFlow()
    fun setTheme(m: ThemeMode) { _theme.value = m; prefs.edit().putString("theme", m.name).apply() }

    /**
     * The prompt assistant: any OpenAI-compatible endpoint. The key lives in [Secrets]; the rest
     * here. A blank URL means no assistant (the spark buttons stay hidden).
     */
    data class Assist(val url: String, val model: String, val system: String) {
        val enabled: Boolean get() = url.isNotBlank()
    }

    private val _assist by lazy {
        MutableStateFlow(Assist(prefs.getString("assist_url", "") ?: "", prefs.getString("assist_model", "") ?: "", prefs.getString("assist_system", "") ?: ""))
    }
    val assist: StateFlow<Assist> get() = _assist.asStateFlow()

    fun setAssist(a: Assist, key: String?) {
        _assist.value = a
        prefs.edit().putString("assist_url", a.url.trim()).putString("assist_model", a.model.trim()).putString("assist_system", a.system).apply()
        if (key != null) app.secrets.put(ASSIST_KEY, key.trim())
    }

    fun assistEndpoint(): AssistEndpoint? = assist.value.takeIf { it.enabled }?.let {
        AssistEndpoint(it.url.trim(), app.secrets.get(ASSIST_KEY), it.model.trim().ifBlank { null })
    }

    const val ASSIST_KEY = "assist:key"
}
