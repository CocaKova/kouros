package com.cocakova.kouros.ui.run

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cocakova.kouros.core.api.FileRef
import com.cocakova.kouros.core.api.MediaKind
import com.cocakova.kouros.core.api.OutputItem
import com.cocakova.kouros.core.api.Outputs
import com.cocakova.kouros.media.Thumbs
import com.cocakova.kouros.net.ServerSession
import com.cocakova.kouros.ui.components.EmptyState
import com.cocakova.kouros.ui.theme.Space

/**
 * Picks something the server already has — what you made earlier — for a field that takes media.
 *
 * ComfyUI's loaders read an annotated name ("picture.png [output]") straight out of the output
 * folder, so nothing is downloaded to the phone and nothing is uploaded back. That is what makes
 * "upscale the thing I just made" one tap instead of a round trip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerMediaPicker(
    session: ServerSession,
    /** What the field takes: "image", "video", "audio"… as the server's spec names it. */
    accepts: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    val items by produceState<List<OutputItem>?>(null, session, accepts) {
        value = runCatching {
            val files = session.client.outputFiles()
                ?: session.client.history(150).flatMap { h -> h.outputs.flatMap { (id, o) -> Outputs.classify(id, o) } }
                    .filter { !it.isTemp }
            files.filter { it.file != null && wanted(it.kind, accepts) }
        }.getOrDefault(emptyList())
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                "Something you made",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Space.xl),
            )
            Text(
                "Straight from the server's output folder — nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.xl).padding(top = 2.dp, bottom = Space.s),
            )
            when (val list = items) {
                null -> Box(Modifier.fillMaxWidth().padding(Space.xxl), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }
                else -> if (list.isEmpty()) {
                    EmptyState("Nothing to choose yet", "What this server makes will show up here.")
                } else LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(Space.m),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    items(list, key = { it.file!!.let { f -> "${f.type}|${f.subfolder}|${f.filename}" } }) { item ->
                        val f = item.file!!
                        Box(
                            Modifier.aspectRatio(1f).clip(MaterialTheme.shapes.medium)
                                .clickable { onPick(annotate(f)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (item.kind == MediaKind.AUDIO) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Outlined.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.height(Space.xs))
                                    Text(f.filename, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                                }
                            } else {
                                AsyncImage(
                                    model = Thumbs.request(context, session, f, item.kind).build(),
                                    contentDescription = f.filename,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.aspectRatio(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The name a loader takes for a file that is not in the input folder. */
fun annotate(f: FileRef): String {
    val path = if (f.subfolder.isBlank()) f.filename else "${f.subfolder}/${f.filename}"
    return if (f.type == "input") path else "$path [${f.type}]"
}

/** Whether a field that takes [accepts] can be fed this kind of file. */
private fun wanted(kind: MediaKind, accepts: String?): Boolean = when (accepts) {
    "video" -> kind == MediaKind.VIDEO || kind == MediaKind.ANIMATED
    "audio" -> kind == MediaKind.AUDIO
    "animated_image" -> kind == MediaKind.ANIMATED || kind == MediaKind.IMAGE
    "mesh" -> kind == MediaKind.MODEL3D
    "image" -> kind == MediaKind.IMAGE || kind == MediaKind.ANIMATED
    // A field whose kind the server doesn't say: offer everything it could sensibly take.
    else -> kind != MediaKind.TEXT
}
