package com.cocakova.kouros.net

import android.util.Base64
import com.cocakova.kouros.core.api.ComfyClient
import com.cocakova.kouros.core.api.ObjectInfo
import com.cocakova.kouros.core.api.ServerCapabilities
import com.cocakova.kouros.core.api.ServerEndpoint
import com.cocakova.kouros.core.api.SocketMessage
import com.cocakova.kouros.core.api.SystemStats
import com.cocakova.kouros.core.ws.WsEvent
import com.cocakova.kouros.data.AuthKind
import com.cocakova.kouros.data.ServerEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

sealed interface ConnState {
    data object Idle : ConnState
    data object Connecting : ConnState
    data class Online(val stats: SystemStats?) : ConnState
    /** Reachable over REST but the live socket keeps failing (proxy without websocket support…). */
    data class Degraded(val reason: String) : ConnState
    data class Offline(val reason: String, val retryInMs: Long) : ConnState
}

/**
 * One ComfyUI server: its REST client, its live socket and what we know about it.
 *
 * The socket runs while someone wants it ([acquire]/[release] — the run service while runs are
 * active, the UI while it is visible), reconnects with jittered exponential backoff, and every
 * (re)connect fires [reconnected] so the run coordinator re-reads the server's REST state.
 * Events from the socket are only an accelerator; nothing depends on having seen them all.
 */
class ServerSession(
    val server: ServerEntity,
    secret: String?,
    private val cacheDir: File,
) {
    val client = ComfyClient(Http.ktor, ServerEndpoint(server.baseUrl, authHeaders(server, secret)))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<WsEvent> = _events.asSharedFlow()

    private val _previews = MutableSharedFlow<com.cocakova.kouros.core.ws.BinaryFrame.Preview>(extraBufferCapacity = 4)
    /** Live preview frames; a slow collector simply misses frames (only the newest matters). */
    val previews: SharedFlow<com.cocakova.kouros.core.ws.BinaryFrame.Preview> = _previews.asSharedFlow()

    private val _reconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val reconnected: SharedFlow<Unit> = _reconnected.asSharedFlow()

    @Volatile var capabilities = ServerCapabilities()
        private set

    private var holders = 0
    private var loop: Job? = null
    private val lock = Mutex()

    fun acquire() = synchronized(this) {
        holders++
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
    }

    fun release() = synchronized(this) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) {
            loop?.cancel(); loop = null
            _state.value = ConnState.Idle
        }
    }

    /** Wake the loop now (network came back, app resumed) instead of waiting out the backoff. */
    fun kick() = synchronized(this) {
        if (holders > 0 && _state.value is ConnState.Offline) {
            loop?.cancel(); loop = scope.launch { runLoop() }
        }
    }

    fun close() { scope.coroutineContext[Job]?.cancel() }

    private suspend fun runLoop() {
        var attempt = 0
        while (scope.isActive) {
            _state.value = ConnState.Connecting
            val stats = runCatching { client.systemStats() }.getOrElse { e ->
                val wait = backoff(attempt++)
                _state.value = ConnState.Offline(e.message ?: e::class.simpleName ?: "unreachable", wait)
                delay(wait); continue
            }
            if (capabilities.features.isEmpty()) probeCapabilities()
            var opened = false
            val started = System.currentTimeMillis()
            runCatching {
                client.socket(server.clientId).collect { m ->
                    when (m) {
                        SocketMessage.Open -> {
                            opened = true
                            _state.value = ConnState.Online(stats)
                            _reconnected.tryEmit(Unit)
                        }
                        is SocketMessage.Event -> {
                            val e = m.event
                            if (e is WsEvent.FeatureFlags) capabilities = capabilities.copy(
                                features = e.flags,
                                supportsPreviewMetadata = true,
                            )
                            _events.emit(e)
                        }
                        is SocketMessage.Binary -> (m.frame as? com.cocakova.kouros.core.ws.BinaryFrame.Preview)?.let { _previews.tryEmit(it) }
                    }
                }
            }.onFailure { e ->
                if (!opened) {
                    // REST works but the socket does not: still useful (polling), but say why.
                    _state.value = ConnState.Degraded(e.message ?: "live updates unavailable")
                }
            }
            if (System.currentTimeMillis() - started > 60_000) attempt = 0
            val wait = backoff(attempt++)
            if (_state.value !is ConnState.Degraded) _state.value = ConnState.Offline("connection closed", wait)
            delay(wait)
        }
    }

    private suspend fun probeCapabilities() {
        val features = runCatching { client.features() }.getOrDefault(JsonObject(emptyMap()))
        val jobs = client.hasJobsApi()
        capabilities = capabilities.copy(features = features, hasJobsApi = jobs)
    }

    // ---- object_info, cached per server by content hash ----

    @Volatile private var objectInfo: ObjectInfo? = null

    /** The server's node definitions. Cached on disk; [refresh] re-downloads (after installs). */
    suspend fun objectInfo(refresh: Boolean = false): ObjectInfo = lock.withLock {
        objectInfo?.takeIf { !refresh }?.let { return it }
        val file = File(cacheDir, "object_info_${server.id}.json")
        val text = withContext(Dispatchers.IO) {
            runCatching { client.objectInfoText() }
                .onSuccess { t -> runCatching { file.writeText(t) } }
                .getOrElse { e -> if (file.isFile && !refresh) file.readText() else throw e }
        }
        val parsed = withContext(Dispatchers.Default) { ObjectInfo.parse(json.parseToJsonElement(text) as JsonObject) }
        objectInfoHash = sha1(text)
        objectInfo = parsed
        parsed
    }

    @Volatile var objectInfoHash: String? = null
        private set

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun backoff(attempt: Int): Long {
            val base = (500L shl attempt.coerceAtMost(6)).coerceAtMost(30_000L)
            return (base * (0.8 + Random.nextDouble() * 0.4)).toLong()
        }

        fun authHeaders(s: ServerEntity, secret: String?): Map<String, String> = when (s.authKind) {
            AuthKind.NONE -> emptyMap()
            AuthKind.BEARER -> secret?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
            AuthKind.BASIC -> secret?.let {
                mapOf("Authorization" to "Basic " + Base64.encodeToString("${s.authUser ?: ""}:$it".toByteArray(), Base64.NO_WRAP))
            } ?: emptyMap()
            AuthKind.HEADER -> if (s.authUser.isNullOrBlank() || secret == null) emptyMap() else mapOf(s.authUser to secret)
        }

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
