package com.cocakova.kouros.ui.run

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cocakova.kouros.core.api.FileRef
import com.cocakova.kouros.core.api.MediaKind
import com.cocakova.kouros.core.api.Outputs
import com.cocakova.kouros.core.form.FieldRole
import com.cocakova.kouros.core.form.FormEngine
import com.cocakova.kouros.core.form.FormField
import com.cocakova.kouros.core.form.isMultilineText
import com.cocakova.kouros.media.Thumbs
import com.cocakova.kouros.net.ServerSession
import com.cocakova.kouros.ui.theme.Accent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToLong

/** Picks the right control for a field from its role and the server's spec. */
@Composable
fun FieldControl(
    field: FormField,
    value: JsonElement,
    onChange: (JsonElement) -> Unit,
    session: ServerSession,
    uploading: Boolean,
    onPickMedia: () -> Unit,
    modifier: Modifier = Modifier,
    control: String? = field.control,
    onControl: (String) -> Unit = {},
) {
    val spec = field.spec
    when {
        field.role == FieldRole.MEDIA -> MediaField(field, value, session, uploading, onPickMedia, modifier)
        field.role == FieldRole.SEED -> SeedField(field, value, onChange, modifier, control ?: "randomize", onControl)
        spec.isMultilineText() -> PromptField(field, value, onChange, modifier)
        spec.widgetType == "BOOLEAN" -> ToggleField(field, value, onChange, modifier)
        spec.widgetType == "COMBO" -> ChoiceField(field, value, onChange, modifier)
        spec.widgetType == "INT" || spec.widgetType == "FLOAT" -> NumberField(field, value, onChange, modifier)
        else -> TextField(field, value, onChange, modifier)
    }
}

@Composable
private fun Label(field: FormField, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            field.label.uppercase(), style = MaterialTheme.typography.labelMedium,
            color = if (field.role == FieldRole.NEGATIVE_PROMPT) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

@Composable
private fun PromptField(field: FormField, value: JsonElement, onChange: (JsonElement) -> Unit, modifier: Modifier) {
    val text = (value as? JsonPrimitive)?.contentOrNull ?: ""
    Column(modifier) {
        Label(field) { Text("${text.length}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = text, onValueChange = { onChange(JsonPrimitive(it)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = if (field.role == FieldRole.PROMPT) 120.dp else 72.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text(if (field.role == FieldRole.NEGATIVE_PROMPT) "What to avoid" else "Describe it") },
        )
    }
}

@Composable
private fun TextField(field: FormField, value: JsonElement, onChange: (JsonElement) -> Unit, modifier: Modifier) {
    Column(modifier) {
        Label(field)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            (value as? JsonPrimitive)?.contentOrNull ?: value.toString(), { onChange(JsonPrimitive(it)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        )
    }
}

@Composable
private fun ToggleField(field: FormField, value: JsonElement, onChange: (JsonElement) -> Unit, modifier: Modifier) {
    val on = (value as? JsonPrimitive)?.booleanOrNull ?: false
    Row(modifier.fillMaxWidth().clickable { onChange(JsonPrimitive(!on)) }, verticalAlignment = Alignment.CenterVertically) {
        Text(field.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(on, { onChange(JsonPrimitive(it)) })
    }
}

private fun formatNumber(v: Double, isInt: Boolean, step: Double?): String {
    if (isInt) return v.roundToLong().toString()
    val decimals = step?.takeIf { it > 0 && it < 1 }?.let { s -> s.toBigDecimal().stripTrailingZeros().scale().coerceIn(1, 4) } ?: 2
    return "%.${decimals}f".format(v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
}

@Composable
private fun NumberField(field: FormField, value: JsonElement, onChange: (JsonElement) -> Unit, modifier: Modifier) {
    val spec = field.spec
    val isInt = spec.widgetType == "INT"
    val current = (value as? JsonPrimitive)?.doubleOrNull ?: 0.0
    val min = spec.min; val max = spec.max
    val step = spec.step ?: if (isInt) 1.0 else 0.01
    val sliderable = min != null && max != null && max > min && (max - min) / step <= 4096 && (max - min) <= 1e6
    fun emit(d: Double) = onChange(if (isInt) JsonPrimitive(d.roundToLong()) else JsonPrimitive(d))
    var text by remember(current) { mutableStateOf(formatNumber(current, isInt, spec.step)) }
    val haptics = LocalHapticFeedback.current
    Column(modifier) {
        Label(field) {
            OutlinedTextField(
                text, { t -> text = t; t.toDoubleOrNull()?.let { d -> emit(d.coerceIn(min ?: d, max ?: d)) } },
                singleLine = true, modifier = Modifier.width(112.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(keyboardType = if (isInt) KeyboardType.Number else KeyboardType.Decimal),
                shape = RoundedCornerShape(10.dp),
            )
        }
        if (sliderable) {
            var drag by remember(current) { mutableFloatStateOf(current.toFloat()) }
            Slider(
                value = drag.coerceIn(min!!.toFloat(), max!!.toFloat()),
                onValueChange = { f ->
                    val snapped = (Math.round((f - min) / step) * step + min)
                    if (snapped.toFloat() != drag) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    drag = snapped.toFloat()
                    text = formatNumber(snapped, isInt, spec.step)
                },
                onValueChangeFinished = { emit(drag.toDouble()) },
                valueRange = min.toFloat()..max.toFloat(),
            )
        }
    }
}

@Composable
private fun SeedField(field: FormField, value: JsonElement, onChange: (JsonElement) -> Unit, modifier: Modifier, mode: String, onMode: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Column(modifier) {
        Label(field)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                (value as? JsonPrimitive)?.contentOrNull ?: "", { t -> t.toLongOrNull()?.let { onChange(JsonPrimitive(it)) } },
                singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onChange(FormEngine.nextSeed(value, "randomize", field.spec))
            }) { Icon(Icons.Outlined.Casino, "New random seed", tint = Accent.clay) }
            Box {
                TextButton(onClick = { menu = true }) { Text(mode.replace('-', ' ')) }
                DropdownMenu(menu, { menu = false }) {
                    listOf("fixed", "increment", "decrement", "randomize").forEach { m ->
                        DropdownMenuItem(text = { Text(m) }, onClick = { onMode(m); menu = false })
                    }
                }
            }
        }
        Text(
            when (mode) {
                "fixed" -> "Same seed every run"
                "randomize" -> "A new seed after each run"
                else -> "Seed ${if (mode == "increment") "+1" else "−1"} after each run"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(field: FormField, value: JsonElement, onChange: (JsonElement) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val current = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
    Column(modifier) {
        Label(field)
        Spacer(Modifier.height(6.dp))
        Surface(
            onClick = { open = true }, shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(current, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.Outlined.ExpandMore, null)
            }
        }
    }
    if (open) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var query by remember { mutableStateOf("") }
        val choices = field.spec.choices.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheet) {
            Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
                Text(field.label, style = MaterialTheme.typography.headlineSmall)
                if (choices.size > 8) OutlinedTextField(
                    query, { query = it }, placeholder = { Text("Search") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(14.dp),
                )
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    items(choices.filter { query.isBlank() || it.contains(query, ignoreCase = true) }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onChange(JsonPrimitive(c)); open = false }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            if (c == current) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

/** An input file: shows what the server has, and replaces it with something from the phone. */
@Composable
private fun MediaField(field: FormField, value: JsonElement, session: ServerSession, uploading: Boolean, onPick: () -> Unit, modifier: Modifier) {
    val raw = (value as? JsonPrimitive)?.contentOrNull ?: ""
    val ref = inputRef(raw)
    val kind = Outputs.byExtension(ref.filename) ?: MediaKind.IMAGE
    val context = LocalContext.current
    Column(modifier) {
        Label(field)
        Spacer(Modifier.height(6.dp))
        Surface(
            onClick = onPick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    if (raw.isNotEmpty() && (kind == MediaKind.IMAGE || kind == MediaKind.ANIMATED || kind == MediaKind.VIDEO)) {
                        AsyncImage(
                            model = Thumbs.request(context, session, ref, kind).build(),
                            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.aspectRatio(1f),
                        )
                    } else Icon(Icons.Outlined.AddPhotoAlternate, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (uploading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(ref.filename.ifEmpty { "Nothing selected" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (uploading) "Uploading…" else "Tap to choose from this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** "sub/name.png [output]" → where the server keeps it. */
fun inputRef(value: String): FileRef {
    val m = Regex("""^(.*?)(?: \[(input|output|temp)])?$""").find(value)
    val path = m?.groupValues?.get(1) ?: value
    val type = m?.groupValues?.get(2)?.ifEmpty { null } ?: "input"
    return FileRef(path.substringAfterLast('/'), path.substringBeforeLast('/', ""), type)
}
