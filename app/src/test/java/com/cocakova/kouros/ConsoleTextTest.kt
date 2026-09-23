package com.cocakova.kouros

import com.cocakova.kouros.core.api.DeviceStats
import com.cocakova.kouros.core.api.SystemStats
import com.cocakova.kouros.ui.servers.argGroups
import com.cocakova.kouros.ui.servers.memoryLine
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleTextTest {
    @Test fun flagsKeepTheirValues() {
        assertEquals(
            listOf("--listen 127.0.0.1", "--port 8188", "--highvram", "--reserve-vram 96.1"),
            argGroups(listOf("--listen", "127.0.0.1", "--port", "8188", "--highvram", "--reserve-vram", "96.1")),
        )
        assertEquals(listOf("stray", "--a b c"), argGroups(listOf("stray", "--a", "b", "c")))
    }

    private fun stats(ramT: Long, ramF: Long, vT: Long, vF: Long) =
        SystemStats(null, null, null, ramT, ramF, listOf(DeviceStats("gpu", "cuda", vT, vF)))

    private val G = 1_073_741_824L

    @Test fun unifiedMemoryShowsSystemFree() {
        assertEquals("20.0 GB of 120.0 GB free", memoryLine(stats(120 * G, 20 * G, 120 * G, 5 * G)))
    }

    @Test fun discreteGpuShowsVramFree() {
        assertEquals("6.0 GB of 24.0 GB free", memoryLine(stats(64 * G, 30 * G, 24 * G, 6 * G)))
    }
}
