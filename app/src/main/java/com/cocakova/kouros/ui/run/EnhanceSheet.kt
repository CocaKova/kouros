package com.cocakova.kouros.ui.run

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cocakova.kouros.core.assist.AssistRequest
import com.cocakova.kouros.core.assist.AssistUpdate
import com.cocakova.kouros.core.assist.PromptAssist
import com.cocakova.kouros.data.Settings
import com.cocakova.kouros.net.Http
import com.cocakova.kouros.ui.theme.Accent
import com.cocakova.kouros.ui.theme.Atelier
import com.cocakova.kouros.ui.theme.Space
import com.cocakova.kouros.ui.theme.fieldColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * One round with the prompt assistant: an optional nudge, the rewrite streaming in, then
 * replace, append or discard. The assistant is whatever OpenAI-compatible endpoint the person set
 * up — an agent that knows their world, or a plain model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhanceSheet(request: AssistRequest, onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var instruction by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var streamed by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }

    fun start() {
        val endpoint = Settings.assistEndpoint() ?: run { error = "Set up an assistant in Settings first"; return }
        val system = Settings.assist.value.system.ifBlank { PromptAssist.DEFAULT_SYSTEM }
        error = null; result = null; streamed = ""; status = "Connecting"; running = true
        job = scope.launch {
            try {
                PromptAssist(Http.patient, endpoint).enhance(request.copy(instruction = instruction), system).collect { u ->
                    when (u) {
                        is AssistUpdate.Status -> status = u.text
                        is AssistUpdate.Partial -> { streamed = u.text; status = null }
                        is AssistUpdate.Done -> { result = u.prompt; haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "The assistant couldn't be reached"
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
            } finally {
                status = null; running = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { job?.cancel(); onDismiss() }, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.gutter).navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Accent.clay)
                Spacer(Modifier.width(Space.s))
                Text(if (request.negative) "Enhance negative prompt" else "Enhance prompt", style = MaterialTheme.typography.headlineSmall)
            }
            OutlinedTextField(
                instruction, { instruction = it },
                placeholder = { Text("Optional: what should change? It can use what it knows.") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                shape = MaterialTheme.shapes.medium, colors = fieldColors(), enabled = !running,
            )

            // What is happening, then what came back.
            AnimatedContent(
                targetState = when {
                    error != null -> "error"
                    result != null -> "result"
                    streamed.isNotEmpty() -> "stream"
                    running -> "status"
                    else -> "idle"
                },
                transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "assist",
            ) { phase ->
                when (phase) {
                    "error" -> Text(error ?: "", color = Atelier.colors.error, style = MaterialTheme.typography.bodyMedium)
                    "result" -> OutlinedTextField(
                        result ?: "", { result = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                        textStyle = MaterialTheme.typography.bodyLarge, shape = MaterialTheme.shapes.medium, colors = fieldColors(),
                    )
                    "stream" -> Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        status?.let { StatusLine(it) }
                        Text(
                            PromptAssist.extract(streamed).ifBlank { streamed },
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    "status" -> StatusLine(status ?: "Working")
                    else -> Text(
                        "Your prompt, the workflow and its models go to the assistant; the rewrite comes back here to review.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(bottom = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                val done = result
                if (done != null && !running) {
                    TextButton(onClick = { onDismiss() }) { Text("Discard") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { onResult(listOf(request.current.trimEnd(), done).filter { it.isNotBlank() }.joinToString(if (request.current.trimEnd().endsWith(",")) " " else ", ")) }) { Text("Append") }
                    Button(
                        onClick = { onResult(done) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent.clay, contentColor = MaterialTheme.colorScheme.background),
                    ) { Text("Replace") }
                } else {
                    if (running) TextButton(onClick = { job?.cancel(); job = null; status = null; running = false }) { Text("Stop") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { start() }, enabled = !running,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent.clay, contentColor = MaterialTheme.colorScheme.background),
                    ) {
                        if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.background)
                        else Text(if (error != null) "Try again" else "Enhance")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Atelier.colors.running)
        Spacer(Modifier.width(Space.s))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Atelier.colors.running, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
