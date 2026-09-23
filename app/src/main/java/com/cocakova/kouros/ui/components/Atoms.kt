package com.cocakova.kouros.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cocakova.kouros.net.ConnState
import com.cocakova.kouros.ui.theme.Accent
import com.cocakova.kouros.ui.theme.Atelier

/** Connection state as a small dot: clay pulsing while connecting, verdigris online, dust off. */
@Composable
fun StatusDot(state: ConnState?, size: Dp = 8.dp) {
    val c = Atelier.colors
    val color = when (state) {
        is ConnState.Online -> c.done
        is ConnState.Connecting -> c.running
        is ConnState.Degraded -> c.running
        is ConnState.Offline -> c.error
        else -> c.idle
    }
    val pulse = rememberInfiniteTransition(label = "dot")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "a",
    )
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(color.copy(alpha = if (state is ConnState.Connecting) alpha else 1f)),
    )
}

/** A screen's title: large serif, with an optional overline and trailing content. */
@Composable
fun ScreenHeader(
    title: String,
    overline: String? = null,
    modifier: Modifier = Modifier,
    /** Sits before the overline, e.g. the server's connection dot beside its name. */
    status: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            overline?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    status?.let { s -> s(); Spacer(Modifier.width(6.dp)) }
                    Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
            }
            Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** A quiet card: one-pixel line instead of a shadow, stone surface. */
@Composable
fun Slab(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/**
 * The run's progress as a chisel line: one segment per node the server will execute, filled as
 * they finish; the current segment fills with its own steps. Reads as "how much of the graph",
 * not just a number.
 */
@Composable
fun ChiselProgress(total: Int, done: Int, stepFraction: Float, modifier: Modifier = Modifier, active: Boolean = true) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = if (active) Atelier.colors.running else Atelier.colors.done
    val animated by animateFloatAsState(stepFraction.coerceIn(0f, 1f), tween(300), label = "step")
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val n = total.coerceIn(1, 64)
        val gap = if (n > 1) 3.dp.toPx() else 0f
        val w = (size.width - gap * (n - 1)) / n
        val r = CornerRadius(3.dp.toPx())
        for (i in 0 until n) {
            val x = i * (w + gap)
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), r)
            val f = when {
                i < done.coerceAtMost(n) -> 1f
                i == done && active -> animated
                else -> 0f
            }
            if (f > 0f) drawRoundRect(fill, Offset(x, 0f), Size(w * f, size.height), r)
        }
    }
}

/** An empty-state panel with a title, a line of explanation and an optional action. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlinthMark(Modifier.size(72.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.let { Spacer(Modifier.height(6.dp)); it() }
    }
}

/** The app's mark drawn in code: a figure rising from a plinth. */
@Composable
fun PlinthMark(modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRoundRect(base.copy(alpha = 0.55f), Offset(w * 0.18f, h * 0.84f), Size(w * 0.64f, h * 0.08f), CornerRadius(4f))
        drawRect(base.copy(alpha = 0.35f), Offset(w * 0.26f, h * 0.76f), Size(w * 0.48f, h * 0.08f))
        val flame = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, h * 0.06f)
            cubicTo(w * 0.72f, h * 0.3f, w * 0.72f, h * 0.5f, w * 0.72f, h * 0.52f)
            cubicTo(w * 0.72f, h * 0.66f, w * 0.62f, h * 0.74f, w * 0.5f, h * 0.74f)
            cubicTo(w * 0.38f, h * 0.74f, w * 0.28f, h * 0.66f, w * 0.28f, h * 0.52f)
            cubicTo(w * 0.28f, h * 0.5f, w * 0.28f, h * 0.3f, w * 0.5f, h * 0.06f)
            close()
        }
        drawPath(flame, Brush.verticalGradient(listOf(Accent.ember, Accent.clay, Accent.clayDeep), startY = 0f, endY = h * 0.74f))
    }
}

/** A small rounded tag. */
@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        maxLines = 1,
    )
}
