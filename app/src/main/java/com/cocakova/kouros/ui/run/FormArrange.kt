package com.cocakova.kouros.ui.run

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cocakova.kouros.core.api.ModelDownload
import com.cocakova.kouros.core.form.FormField
import com.cocakova.kouros.core.form.FormLayout
import com.cocakova.kouros.core.form.ModelNeed
import com.cocakova.kouros.ui.components.Slab
import com.cocakova.kouros.ui.theme.Accent
import com.cocakova.kouros.ui.theme.Atelier
import com.cocakova.kouros.ui.theme.fieldColors
import kotlinx.coroutines.launch

/**
 * The form in arranging mode: every field as a row with its controls — pin it to the top (and
 * move it there), rename it, hide it. Hidden fields are listed last so they can come back.
 */
@Composable
fun ArrangeFields(st: RunScreenState.Ready, onEdit: ((FormLayout, com.cocakova.kouros.core.form.Form) -> FormLayout) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var renaming by remember { mutableStateOf<FormField?>(null) }
    val pinned = st.form.hero.map { it.key }
    Column(modifier) {
        Text(
            "Pin what you change most to the top and put it in order. Hidden fields still run with their values.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Section("On top")
        st.form.hero.forEachIndexed { i, f ->
            ArrangeRow(
                f, pinned = true,
                onPin = { onEdit { l, form -> l.unpin(f.key, form) } },
                onUp = if (i > 0) ({ onEdit { l, form -> l.move(f.key, -1, form) } }) else null,
                onDown = if (i < pinned.lastIndex) ({ onEdit { l, form -> l.move(f.key, 1, form) } }) else null,
                onRename = { renaming = f },
                onHide = { onEdit { l, form -> l.hide(f.key, form) } },
            )
        }
        if (st.form.advanced.isNotEmpty()) Section("Advanced")
        st.form.advanced.forEach { f ->
            ArrangeRow(
                f, pinned = false,
                onPin = { onEdit { l, form -> l.pin(f.key, form) } },
                onUp = null, onDown = null,
                onRename = { renaming = f },
                onHide = { onEdit { l, form -> l.hide(f.key, form) } },
            )
        }
        val hidden = st.layout.hiddenFields(st.baseForm)
        if (hidden.isNotEmpty()) {
            Section("Hidden")
            hidden.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(st.layout.labels[f.key] ?: f.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(f.group, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onEdit { l, _ -> l.show(f.key) } }) { Icon(Icons.Outlined.Visibility, "Show ${f.label}") }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (st.layout.customized) OutlinedButton(onClick = { onEdit { l, _ -> l.reset() } }) { Text("Reset") }
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.Button(onClick = onDone) { Text("Done") }
        }
    }
    renaming?.let { f ->
        var text by remember(f.key) { mutableStateOf(f.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename field") },
            text = {
                OutlinedTextField(
                    text, { text = it }, singleLine = true, colors = fieldColors(),
                    supportingText = { Text("In the workflow: ${f.spec.displayName ?: f.spec.name} · ${f.group}") },
                )
            },
            confirmButton = { TextButton(onClick = { onEdit { l, _ -> l.rename(f.key, text) }; renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { onEdit { l, _ -> l.rename(f.key, null) }; renaming = null }) { Text("Use original") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun ArrangeRow(f: FormField, pinned: Boolean, onPin: () -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?, onRename: () -> Unit, onHide: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPin) {
            Icon(Icons.Outlined.PushPin, if (pinned) "Unpin ${f.label}" else "Pin ${f.label} to the top", tint = if (pinned) Accent.clay else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(f.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(f.group, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (pinned) {
            IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) { Icon(Icons.Outlined.ArrowUpward, "Move ${f.label} up") }
            IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) { Icon(Icons.Outlined.ArrowDownward, "Move ${f.label} down") }
        }
        IconButton(onClick = onRename) { Icon(Icons.Outlined.Edit, "Rename ${f.label}") }
        IconButton(onClick = onHide) { Icon(Icons.Outlined.VisibilityOff, "Hide ${f.label}") }
    }
}

/** Saved sets of values: tap to load, long-press to delete, + to save the current ones. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PresetRow(presets: Set<String>, onApply: (String) -> Unit, onSave: (String) -> Unit, onDelete: (String) -> Unit, modifier: Modifier = Modifier) {
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        items(presets.sorted(), key = { it }) { name ->
            Surface(
                shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.combinedClickable(onClickLabel = "Load preset", onLongClickLabel = "Delete preset", onClick = { onApply(name) }, onLongClick = { deleting = name }),
            ) {
                Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        item(key = "+") {
            Surface(onClick = { saving = true }, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Add, null, Modifier.height(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (presets.isEmpty()) "Save as preset" else "Save", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    if (saving) PresetNameDialog(onSave = { onSave(it); saving = false }, onDismiss = { saving = false })
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"$name\"?") },
            confirmButton = { TextButton(onClick = { onDelete(name); deleting = null }) { Text("Delete", color = Atelier.colors.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

@Composable
fun PresetNameDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as preset") },
        text = {
            OutlinedTextField(
                name, { name = it }, singleLine = true, placeholder = { Text("Portrait, 16:9 draft…") }, colors = fieldColors(),
                supportingText = { Text("Keeps every value on this form, reference photos included.") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The models this workflow needs that the server doesn't have: where each goes, a link to get
 * it, and — when the server has Kouros Bridge — a button that makes the server fetch it.
 */
@Composable
fun MissingModels(needs: List<ModelNeed>, downloads: List<ModelDownload>, canDownload: Boolean, onDownload: (ModelNeed) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Slab(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, null, tint = Atelier.colors.running)
            Spacer(Modifier.width(8.dp))
            Text(if (needs.size == 1) "The server is missing a model" else "The server is missing ${needs.size} models", style = MaterialTheme.typography.titleSmall)
        }
        needs.forEach { n ->
            val file = n.name.substringAfterLast('/')
            val d = downloads.firstOrNull { it.name == file && (n.directory == null || it.directory == n.directory) }
            Spacer(Modifier.height(10.dp))
            Text(file, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                n.directory?.let { "Goes in models/$it" } ?: "Put it in the right models folder on the server",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                d?.running == true -> {
                    Spacer(Modifier.height(6.dp))
                    val f = d.fraction
                    if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Downloading on the server · ${gb(d.done)}" + (d.total?.let { " of ${gb(it)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                d?.state == "done" -> Text("Downloaded", style = MaterialTheme.typography.labelMedium, color = Atelier.colors.done)
                else -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (canDownload && n.url != null && n.directory != null) TextButton(onClick = { onDownload(n) }) {
                        Icon(Icons.Outlined.CloudDownload, null); Spacer(Modifier.width(6.dp)); Text(if (d?.state == "failed") "Try again" else "Download to server")
                    }
                    n.url?.let { url ->
                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Text("Open link") }
                        IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Model link", url))) } }) {
                            Icon(Icons.Outlined.ContentCopy, "Copy link")
                        }
                    }
                }
            }
            d?.takeIf { it.state == "failed" }?.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Atelier.colors.error) }
        }
        if (!canDownload && needs.any { it.url != null }) {
            Spacer(Modifier.height(8.dp))
            Text(
                "With Kouros Bridge on the server, the server can fetch these itself.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun gb(bytes: Long): String =
    if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1_073_741_824.0) else "%.0f MB".format(bytes / 1_048_576.0)
