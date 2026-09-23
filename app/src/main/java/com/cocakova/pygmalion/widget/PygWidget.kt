package com.cocakova.pygmalion.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.layout.ContentScale
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.cocakova.pygmalion.MainActivity
import com.cocakova.pygmalion.app
import com.cocakova.pygmalion.core.api.MediaKind
import com.cocakova.pygmalion.core.run.RunPhase
import com.cocakova.pygmalion.data.RunState
import com.cocakova.pygmalion.media.Thumbs
import com.cocakova.pygmalion.quick.QuickRunActivity
import com.cocakova.pygmalion.run.RunCoordinator
import kotlinx.coroutines.flow.first

/**
 * Home-screen widget: the latest result, or the run in progress with its percentage; tap to
 * open, "Again" to run the last workflow with fresh seeds.
 */
class PygWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val recent = app.db.runs().recent(20).first()
        val active = recent.firstOrNull { it.state in setOf(RunState.SUBMITTING, RunState.QUEUED, RunState.RUNNING) }
        val progress = active?.let { app.runs.progress.value[it.promptId] }
        val lastDone = recent.firstOrNull { it.state == RunState.SUCCEEDED }
        val picture: Bitmap? = lastDone?.let { r ->
            RunCoordinator.decodeOutputs(r.outputsJson).firstOrNull { it.kind == MediaKind.IMAGE || it.kind == MediaKind.VIDEO || it.kind == MediaKind.ANIMATED }
                ?.let { runCatching { Thumbs.load(context, r.serverId, it, 480) }.getOrNull() }
        }
        provideContent {
            val bone = ColorProvider(day = Color(0xFF2A2622), night = Color(0xFFE9E2D6))
            val dust = ColorProvider(day = Color(0xFF6B635A), night = Color(0xFF8C847A))
            val clay = ColorProvider(day = Color(0xFF9E4A2A), night = Color(0xFFE08A61))
            Box(
                GlanceModifier.fillMaxSize().cornerRadius(24.dp)
                    .background(ColorProvider(day = Color(0xFFF4F1EC), night = Color(0xFF12100E)))
                    .clickable(actionStartActivity(Intent(context, MainActivity::class.java).apply {
                        (active ?: lastDone)?.let { putExtra(MainActivity.EXTRA_RUN, it.promptId) }
                    })),
            ) {
                if (picture != null && active == null) {
                    Image(ImageProvider(picture), contentDescription = lastDone?.workflowName, contentScale = ContentScale.Crop, modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp))
                }
                Column(GlanceModifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.Bottom) {
                    if (active != null) {
                        Text(active.workflowName, style = TextStyle(color = bone, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                        Spacer(GlanceModifier.height(4.dp))
                        Text(
                            when (progress?.phase) {
                                RunPhase.RUNNING -> "${(progress.fraction * 100).toInt()}% · ${progress.currentTitle ?: "working"}"
                                else -> "Queued"
                            },
                            style = TextStyle(color = dust, fontSize = 12.sp), maxLines = 1,
                        )
                        Spacer(GlanceModifier.height(8.dp))
                        LinearProgressIndicator(progress?.fraction ?: 0f, GlanceModifier.fillMaxWidth().height(6.dp), color = clay, backgroundColor = dust)
                    } else {
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                lastDone?.workflowName ?: "Pygmalion",
                                style = TextStyle(color = if (picture != null) ColorProvider(Color.White, Color.White) else bone, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1, modifier = GlanceModifier.defaultWeight(),
                            )
                            if (lastDone != null) {
                                Spacer(GlanceModifier.width(8.dp))
                                Text(
                                    "Again",
                                    style = TextStyle(color = ColorProvider(Color(0xFF0B0A09), Color(0xFF0B0A09)), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                                    modifier = GlanceModifier.background(ColorProvider(Color(0xFFE08A61), Color(0xFFE08A61))).cornerRadius(12.dp)
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                        .clickable(actionStartActivity(Intent(context, QuickRunActivity::class.java))),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        suspend fun refresh(context: Context) = runCatching { PygWidget().updateAll(context) }
    }
}

class PygWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PygWidget()
}
