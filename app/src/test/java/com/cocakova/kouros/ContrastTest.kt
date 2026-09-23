package com.cocakova.kouros

import androidx.compose.ui.graphics.Color
import com.cocakova.kouros.ui.theme.Carrara
import com.cocakova.kouros.ui.theme.Status
import com.cocakova.kouros.ui.theme.Stone
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Status colors must read on their ground in both themes: WCAG AA, 4.5:1. */
class ContrastTest {
    private fun lum(c: Color): Double {
        fun ch(v: Float) = if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
    private fun ratio(a: Color, b: Color): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    @Test fun darkStatusOnStone() {
        listOf(Status.runningDark, Status.doneDark, Status.errorDark, Status.idleDark).forEach { c ->
            listOf(Stone.s0, Stone.s1, Stone.s2).forEach { g -> assertTrue("$c on $g = ${ratio(c, g)}", ratio(c, g) >= 4.5) }
        }
    }

    @Test fun lightStatusOnCarrara() {
        listOf(Status.runningLight, Status.doneLight, Status.errorLight, Status.idleLight).forEach { c ->
            listOf(Carrara.floor, Carrara.slab).forEach { g -> assertTrue("$c on $g = ${ratio(c, g)}", ratio(c, g) >= 4.5) }
        }
    }

    @Test fun bodyText() {
        assertTrue(ratio(Stone.bone, Stone.s0) >= 7.0)
        assertTrue(ratio(Stone.dust, Stone.s0) >= 4.5)
        assertTrue(ratio(Carrara.graphite, Carrara.floor) >= 7.0)
        assertTrue(ratio(Carrara.slate, Carrara.floor) >= 4.5)
    }
}
