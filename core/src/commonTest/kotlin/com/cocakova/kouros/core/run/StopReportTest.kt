package com.cocakova.kouros.core.run

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StopReportTest {
    @Test fun aServerStopSaysWhereAndThatItWasNotTheApp() {
        val r = StopReport(StopReport.Kind.BY_SERVER, nodeTitle = "Decode", availableMemory = 7_300_000_000)
        assertEquals("The server stopped this run at Decode", r.headline)
        assertTrue(r.detail!!.contains("Kouros didn't stop it"))
        assertTrue(r.detail!!.endsWith("It had 7.3 GB of memory left."))
    }

    @Test fun aStopFromThePhoneNeedsNoExplaining() {
        val r = StopReport(StopReport.Kind.BY_YOU)
        assertEquals("You stopped this run", r.headline)
        assertNull(r.detail)
    }

    @Test fun aFailureLeadsWithTheNodeAndKeepsItsMessage() {
        val r = StopReport(StopReport.Kind.FAILED, nodeType = "VAEDecode", message = "out of memory")
        assertEquals("VAEDecode failed", r.headline)
        assertEquals("out of memory", r.detail)
        assertEquals("The run failed", StopReport(StopReport.Kind.FAILED).headline)
    }

    @Test fun theServersWordsAreCleanedAndCapped() {
        val lines = listOf(
            "\u001B[32mINFO\u001B[0m starting",
            "[512B blob data]",
            "   ",
            "guard: interrupt — 7.3 GB available",
            "Processing interrupted",
        )
        assertEquals(listOf("INFO starting", "guard: interrupt — 7.3 GB available", "Processing interrupted"), StopReport.lastWords(lines))
        assertEquals(listOf("Processing interrupted"), StopReport.lastWords(lines, limit = 1))
    }

    @Test fun itSurvivesTheDatabase() {
        val r = StopReport(StopReport.Kind.BY_SERVER, nodeTitle = "Decode", availableMemory = 9_000_000_000, serverSaid = listOf("a", "b"))
        assertEquals(r, StopReport.decode(r.encode()))
        assertNull(StopReport.decode(null))
        assertNull(StopReport.decode("not json"))
    }

    @Test fun memoryReadsInWholeUnits() {
        assertEquals("It had 1.0 GB of memory left.", StopReport(StopReport.Kind.BY_SERVER, availableMemory = 1_000_000_000).memoryLine)
        assertEquals("It had 512 MB of memory left.", StopReport(StopReport.Kind.BY_SERVER, availableMemory = 512_000_000).memoryLine)
    }
}
