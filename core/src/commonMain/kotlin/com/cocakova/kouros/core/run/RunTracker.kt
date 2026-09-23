package com.cocakova.kouros.core.run

import com.cocakova.kouros.core.api.HistoryEntry
import com.cocakova.kouros.core.api.OutputItem
import com.cocakova.kouros.core.api.Outputs
import com.cocakova.kouros.core.ws.WsEvent
import com.cocakova.kouros.core.ws.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class RunPhase { SUBMITTING, QUEUED, RUNNING, SUCCEEDED, FAILED, INTERRUPTED, LOST;
    val isTerminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == INTERRUPTED || this == LOST
}

data class RunError(val nodeId: String?, val nodeType: String?, val message: String, val type: String?)

/** Everything the UI, notification and widget show about one run, at one moment. */
data class RunProgress(
    val promptId: String,
    val phase: RunPhase,
    val queueAhead: Int? = null,
    /** Nodes expected to execute (reachable from outputs, minus cache hits). */
    val totalNodes: Int = 0,
    val doneNodes: Int = 0,
    val currentNode: String? = null,
    val currentTitle: String? = null,
    val step: Int = 0,
    val steps: Int = 0,
    val outputs: List<OutputItem> = emptyList(),
    val error: RunError? = null,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
) {
    /** 0..1 across the whole run: finished nodes plus the current node's own step progress. */
    val fraction: Float
        get() = when {
            phase == RunPhase.SUCCEEDED -> 1f
            totalNodes <= 0 -> 0f
            else -> ((doneNodes + if (steps > 0) step.toFloat() / steps else 0f) / totalNodes).coerceIn(0f, 1f)
        }
}

/**
 * Folds live socket events for one prompt into [RunProgress]. Pure and deterministic: the app
 * feeds it events, the notification and screen render what it returns. Reconciliation (after a
 * reconnect) replaces the state wholesale with [fromHistory] / [queued] — the socket is only an
 * accelerator; the server's REST view is the truth.
 */
class RunTracker(val promptId: String, prompt: JsonObject, outputClasses: Set<String>) {
    private val titles: Map<String, String> = prompt.mapValues { (id, n) ->
        ((n as? JsonObject)?.get("_meta") as? JsonObject)?.str("title") ?: (n as? JsonObject)?.str("class_type") ?: id
    }
    private val expected: Set<String> = reachable(prompt, outputClasses)
    private val executed = LinkedHashSet<String>()
    private val cached = HashSet<String>()

    var state = RunProgress(promptId, RunPhase.QUEUED, totalNodes = expected.size)
        private set

    fun titleOf(nodeId: String?): String? = nodeId?.let { titles[it] ?: titles[it.substringAfterLast(':')] }

    /** Returns the new state if [e] concerned this run, else null. */
    fun onEvent(e: WsEvent, nowMs: Long): RunProgress? {
        val s = state
        val next = when (e) {
            is WsEvent.Status -> if (s.phase == RunPhase.QUEUED) s.copy(queueAhead = e.queueRemaining?.let { (it - 1).coerceAtLeast(0) }) else null
            is WsEvent.ExecutionStart -> if (e.promptId != promptId) null
                else s.copy(phase = RunPhase.RUNNING, startedAtMs = s.startedAtMs ?: nowMs, queueAhead = 0)
            is WsEvent.ExecutionCached -> if (e.promptId != promptId) null else {
                cached += e.nodes
                s.copy(phase = RunPhase.RUNNING, totalNodes = (expected - cached).size.coerceAtLeast(executed.size))
            }
            is WsEvent.Executing -> when {
                e.promptId != null && e.promptId != promptId -> null
                e.node == null -> if (s.phase == RunPhase.RUNNING) s.copy(currentNode = null, currentTitle = null, step = 0, steps = 0) else null
                else -> {
                    s.currentNode?.let { executed += it }
                    s.copy(
                        phase = RunPhase.RUNNING,
                        startedAtMs = s.startedAtMs ?: nowMs,
                        currentNode = e.node,
                        currentTitle = titleOf(e.displayNode ?: e.node),
                        doneNodes = executed.count { it !in cached },
                        step = 0, steps = 0,
                    )
                }
            }
            is WsEvent.Progress -> if (e.promptId != null && e.promptId != promptId) null
                else if (e.promptId == null && s.phase != RunPhase.RUNNING) null
                else s.copy(step = e.value, steps = e.max)
            is WsEvent.Executed -> if (e.promptId != null && e.promptId != promptId) null else {
                executed += e.node
                s.copy(
                    outputs = s.outputs + Outputs.classify(e.node, e.output),
                    doneNodes = executed.count { it !in cached },
                )
            }
            is WsEvent.ExecutionSuccess -> if (e.promptId != promptId) null
                else s.copy(phase = RunPhase.SUCCEEDED, finishedAtMs = nowMs, currentNode = null, currentTitle = null, doneNodes = s.totalNodes)
            is WsEvent.ExecutionError -> if (e.promptId != promptId) null
                else s.copy(
                    phase = RunPhase.FAILED, finishedAtMs = nowMs,
                    error = RunError(e.nodeId, e.nodeType, e.exceptionMessage ?: e.exceptionType ?: "Failed", e.exceptionType),
                )
            is WsEvent.ExecutionInterrupted -> if (e.promptId != promptId) null
                else s.copy(phase = RunPhase.INTERRUPTED, finishedAtMs = nowMs)
            else -> null
        } ?: return null
        state = next
        return next
    }

    /** Authoritative state from `/history`, e.g. after reconnecting. */
    fun fromHistory(h: HistoryEntry): RunProgress {
        val err = h.error
        val phase = when {
            err != null -> RunPhase.FAILED
            h.messages.any { it.first == "execution_interrupted" } -> RunPhase.INTERRUPTED
            h.statusStr == "success" || h.completed == true -> RunPhase.SUCCEEDED
            else -> RunPhase.FAILED
        }
        state = state.copy(
            phase = phase,
            outputs = h.outputs.flatMap { (id, o) -> Outputs.classify(id, o) },
            error = err?.let {
                RunError(
                    it["node_id"]?.let { v -> (v as? JsonPrimitive)?.contentOrNull },
                    it.str("node_type"),
                    it.str("exception_message") ?: "Failed",
                    it.str("exception_type"),
                )
            },
            startedAtMs = h.startedAtMs ?: state.startedAtMs,
            finishedAtMs = h.endedAtMs ?: state.finishedAtMs,
            currentNode = null, currentTitle = null,
            doneNodes = if (phase == RunPhase.SUCCEEDED) state.totalNodes else state.doneNodes,
        )
        return state
    }

    /** Authoritative: still in the queue (running or pending). */
    fun queued(running: Boolean, ahead: Int): RunProgress {
        state = state.copy(phase = if (running) RunPhase.RUNNING else RunPhase.QUEUED, queueAhead = if (running) 0 else ahead)
        return state
    }

    /** Neither queued nor in history — the server lost it (restart, queue cleared). */
    fun lost(): RunProgress {
        state = state.copy(phase = RunPhase.LOST)
        return state
    }

    companion object {
        /** Nodes that feed an output node (inclusive): the ones the server will actually run. */
        fun reachable(prompt: JsonObject, outputClasses: Set<String>): Set<String> {
            val out = HashSet<String>()
            val stack = ArrayDeque(prompt.filter { (_, n) -> (n as? JsonObject)?.str("class_type") in outputClasses }.keys)
            if (stack.isEmpty()) return prompt.keys
            while (stack.isNotEmpty()) {
                val id = stack.removeLast()
                if (!out.add(id)) continue
                val inputs = (prompt[id] as? JsonObject)?.get("inputs") as? JsonObject ?: continue
                for (v in inputs.values) {
                    if (v is JsonArray && v.size == 2) (v[0] as? JsonPrimitive)?.contentOrNull?.let { if (it in prompt) stack += it }
                }
            }
            return out
        }
    }
}
