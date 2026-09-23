package com.cocakova.pygmalion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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

val PygShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun PygmalionTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val atelier = if (dark) AtelierColors(Status.runningDark, Status.doneDark, Status.errorDark, Status.idleDark, true)
    else AtelierColors(Status.runningLight, Status.doneLight, Status.errorLight, Status.idleLight, false)
    CompositionLocalProvider(LocalAtelier provides atelier) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = PygTypography,
            shapes = PygShapes,
            content = content,
        )
    }
}

object Atelier {
    val colors: AtelierColors @Composable get() = LocalAtelier.current
}
