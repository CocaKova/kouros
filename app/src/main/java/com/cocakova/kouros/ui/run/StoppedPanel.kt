package com.cocakova.kouros.ui.run

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cocakova.kouros.core.run.StopReport
import com.cocakova.kouros.ui.components.Slab
import com.cocakova.kouros.ui.theme.Atelier
import com.cocakova.kouros.ui.theme.Space

/**
 * What became of a run that ended with nothing to show. A run can stop for reasons that have
 * nothing to do with the phone — a memory guard, someone else's interrupt, a restart — and the
 * app saying nothing is what makes the app look broken. So it says who stopped it, where it had
 * got to, what the server had left, and, on request, the server's own last words.
 */
@Composable
fun StoppedPanel(report: StopReport, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    var open by remember(report) { mutableStateOf(false) }
    val tone = if (report.kind == StopReport.Kind.FAILED) Atelier.colors.error else Atelier.colors.running

    Slab(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(report.headline, style = MaterialTheme.typography.titleSmall, color = tone, modifier = Modifier.weight(1f))
            if (report.serverSaid.isNotEmpty()) {
                Icon(
                    if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (open) "Hide what the server said" else "Show what the server said",
                    modifier = Modifier.clickable { open = !open },
                )
            }
        }
        report.detail?.let {
            Spacer(Modifier.height(Space.xs))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = Space.s)) {
                Text("The server's last words", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Space.xs))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .horizontalScroll(rememberScrollState()).padding(Space.s),
                ) {
                    for (line in report.serverSaid) {
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2)
                    }
                }
            }
        }
        if (onRetry != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                TextButton(onClick = onRetry) { Text("Run it again") }
            }
        }
    }
}
