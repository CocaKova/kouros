package com.cocakova.kouros.ui

import android.content.Context
import com.cocakova.kouros.app
import com.cocakova.kouros.data.ServerEntity
import com.cocakova.kouros.net.ServerSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The server the screens are looking at. Remembered across launches (a convenience, not state). */
object CurrentServer {
    private val prefs by lazy { app.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    private val selected = MutableStateFlow<String?>(null)

    val servers: StateFlow<List<ServerEntity>> by lazy {
        app.db.servers().all().stateIn(app.appScope, SharingStarted.Eagerly, emptyList())
    }

    /** The chosen server, or the first one when the choice is gone or never made. */
    val server: StateFlow<ServerEntity?> by lazy {
        if (selected.value == null) selected.value = prefs.getString("server", null)
        combine(servers, selected) { list, id -> list.firstOrNull { it.id == id } ?: list.firstOrNull() }
            .stateIn(app.appScope, SharingStarted.Eagerly, null)
    }

    fun select(id: String) {
        selected.value = id
        prefs.edit().putString("server", id).apply()
    }

    fun session(s: ServerEntity): ServerSession = app.sessions.get(s)
}
