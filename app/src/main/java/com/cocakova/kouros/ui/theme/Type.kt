package com.cocakova.kouros.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cocakova.kouros.R

@OptIn(ExperimentalTextApi::class)
private fun fraunces(weight: Int, opsz: Float) = Font(
    R.font.fraunces,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", opsz),
        FontVariation.Setting("SOFT", 50f),
    ),
)

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Display serif: Fraunces, soft, optical size tuned for large titles. */
val Display = FontFamily(fraunces(400, 72f), fraunces(600, 72f), fraunces(300, 72f))
/** Serif for small headings. */
val DisplaySmall = FontFamily(fraunces(500, 24f), fraunces(600, 24f))
/** UI sans: Inter. */
val Sans = FontFamily(inter(400), inter(500), inter(600), inter(700))

val KourosTypography = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight(400), fontSize = 52.sp, lineHeight = 56.sp, letterSpacing = (-0.02).em),
    displayMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight(400), fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.015).em),
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight(400), fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.01).em),
    headlineLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight(400), fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight(400), fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = DisplaySmall, fontWeight = FontWeight(500), fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = DisplaySmall, fontWeight = FontWeight(500), fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(600), fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight(600), fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight(600), fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.01.em),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(500), fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.02.em),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight(500), fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.04.em),
)
