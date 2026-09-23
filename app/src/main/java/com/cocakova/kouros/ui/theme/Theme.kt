package com.cocakova.kouros.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkScheme = darkColorScheme(
    primary = Accent.clay,
    onPrimary = Stone.s0,
    primaryContainer = Accent.clayDeep,
    onPrimaryContainer = Accent.ember,
    secondary = Accent.verdigrisBright,
    onSecondary = Stone.s0,
    tertiary = Accent.ember,
    background = Stone.s0,
    onBackground = Stone.bone,
    surface = Stone.s0,
    onSurface = Stone.bone,
    surfaceVariant = Stone.s3,
    onSurfaceVariant = Stone.dust,
    surfaceContainerLowest = Stone.s0,
    surfaceContainerLow = Stone.s1,
    surfaceContainer = Stone.s2,
    surfaceContainerHigh = Stone.s3,
    surfaceContainerHighest = Stone.s4,
    outline = Stone.line,
    outlineVariant = Stone.s4,
    error = Status.errorDark,
    onError = Stone.s0,
)

private val LightScheme = lightColorScheme(
    primary = Accent.clayDeep,
    onPrimary = Carrara.slab,
    primaryContainer = Color(0xFFF6DCCD),
    onPrimaryContainer = Color(0xFF4A1F0E),
    secondary = Status.doneLight,
    onSecondary = Carrara.slab,
    tertiary = Accent.clay,
    background = Carrara.floor,
    onBackground = Carrara.graphite,
    surface = Carrara.floor,
    onSurface = Carrara.graphite,
    surfaceVariant = Carrara.vein,
    onSurfaceVariant = Carrara.slate,
    surfaceContainerLowest = Carrara.slab,
    surfaceContainerLow = Color(0xFFF8F5F1),
    surfaceContainer = Color(0xFFF1EDE7),
    surfaceContainerHigh = Color(0xFFECE7E0),
    surfaceContainerHighest = Carrara.vein,
    outline = Carrara.line,
    outlineVariant = Carrara.vein,
    error = Status.errorLight,
    onError = Carrara.slab,
)

/** Colors Material has no slot for: run states, per theme. */
@Immutable
data class AtelierColors(
    val running: Color,
    val done: Color,
    val error: Color,
    val idle: Color,
    val isDark: Boolean,
)

val LocalAtelier = staticCompositionLocalOf {
    AtelierColors(Status.runningDark, Status.doneDark, Status.errorDark, Status.idleDark, true)
}

/**
 * The measures everything is laid out on. Spacing moves in steps of four; radii grow with the
 * size of the thing — a chip is barely rounded, a sheet is soft — so nested shapes never fight.
 */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Left and right margin of every screen. */
    val gutter = 16.dp
}

object Radius {
    val chip = 10.dp
    val field = 14.dp
    val card = 20.dp
    val stage = 24.dp
    val sheet = 28.dp
}

val KourosShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(Radius.chip),
    medium = RoundedCornerShape(Radius.field),
    large = RoundedCornerShape(Radius.card),
    extraLarge = RoundedCornerShape(Radius.sheet),
)

/**
 * Inputs as inset slabs: a surface one step above the floor with a quiet edge that warms to clay
 * when focused — instead of Material's bright outline on a black ground.
 */
@Composable
fun fieldColors(): TextFieldColors {
    val cs = MaterialTheme.colorScheme
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = cs.surfaceContainerLow,
        unfocusedContainerColor = cs.surfaceContainerLow,
        disabledContainerColor = cs.surfaceContainerLowest,
        focusedBorderColor = cs.primary.copy(alpha = 0.8f),
        unfocusedBorderColor = cs.outlineVariant,
        cursorColor = cs.primary,
        focusedLabelColor = cs.primary,
        unfocusedPlaceholderColor = cs.onSurfaceVariant.copy(alpha = 0.7f),
        focusedPlaceholderColor = cs.onSurfaceVariant.copy(alpha = 0.5f),
    )
}

/** The theme the person chose (system, light, dark), resolved against the system setting. */
@Composable
fun isKourosDark(): Boolean {
    val mode by com.cocakova.kouros.data.Settings.theme.collectAsState()
    return when (mode) {
        com.cocakova.kouros.data.Settings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        com.cocakova.kouros.data.Settings.ThemeMode.LIGHT -> false
        com.cocakova.kouros.data.Settings.ThemeMode.DARK -> true
    }
}

@Composable
fun KourosTheme(dark: Boolean = isKourosDark(), content: @Composable () -> Unit) {
    val atelier = if (dark) AtelierColors(Status.runningDark, Status.doneDark, Status.errorDark, Status.idleDark, true)
    else AtelierColors(Status.runningLight, Status.doneLight, Status.errorLight, Status.idleLight, false)
    CompositionLocalProvider(LocalAtelier provides atelier) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = KourosTypography,
            shapes = KourosShapes,
            content = content,
        )
    }
}

object Atelier {
    val colors: AtelierColors @Composable get() = LocalAtelier.current
}
