package com.cocakova.kouros.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * "Atelier": a sculptor's studio. Warm stone for surfaces, one clay accent for the thing being
 * made, verdigris only for "it worked". Dark is true black at the floor so OLED panels rest.
 */
object Stone {
    val s0 = Color(0xFF0B0A09) // floor (OLED)
    val s1 = Color(0xFF12100E)
    val s2 = Color(0xFF181614)
    val s3 = Color(0xFF1C1917)
    val s4 = Color(0xFF26221F)
    val s5 = Color(0xFF34302C)
    val line = Color(0xFF3A3531)
    val dust = Color(0xFF8C847A)   // secondary text on dark
    val bone = Color(0xFFE9E2D6)   // primary text on dark
}

object Carrara {
    val floor = Color(0xFFF4F1EC)
    val slab = Color(0xFFFBF9F6)
    val vein = Color(0xFFE4DED5)
    val line = Color(0xFFD6CFC4)
    val graphite = Color(0xFF2A2622) // primary text on light
    val slate = Color(0xFF6B635A)    // secondary text on light
}

object Accent {
    val clay = Color(0xFFC8663F)
    val clayBright = Color(0xFFE08A61)
    val clayDeep = Color(0xFF9E4A2A)
    val ember = Color(0xFFF0C9A8)
    val verdigris = Color(0xFF3E8E7E)
    val verdigrisBright = Color(0xFF6FC1AE)
}

/**
 * Run-state colors, each with an on-dark and an on-light variant that holds ≥4.5:1 against its
 * ground (checked in ContrastTest), so status reads in both themes and in the notification shade.
 */
object Status {
    val runningDark = Color(0xFFE08A61); val runningLight = Color(0xFF9E4A2A)
    val doneDark = Color(0xFF6FC1AE); val doneLight = Color(0xFF2C6B5E)
    val errorDark = Color(0xFFF08A7E); val errorLight = Color(0xFFA3342A)
    val idleDark = Color(0xFF8C847A); val idleLight = Color(0xFF6B635A)
}
