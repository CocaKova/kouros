package com.cocakova.pygmalion.core.ws

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WsParsingTest {
    @Test fun status() {
        val e = WsTextParser.parse("""{"type":"status","data":{"status":{"exec_info":{"queue_remaining":3}},"sid":"abc"}}""")
        assertEquals(WsEvent.Status(3, "abc"), e)
    }

    @Test fun executingNullMeansDone() {
        val e = assertIs<WsEvent.Executing>(WsTextParser.parse("""{"type":"executing","data":{"node":null,"prompt_id":"p"}}"""))
        assertNull(e.node)
        assertEquals("p", e.promptId)
    }

    @Test fun progressWithSubgraphId() {
        val e = WsTextParser.parse("""{"type":"progress","data":{"value":4,"max":20,"prompt_id":"p","node":"12:6"}}""")
        assertEquals(WsEvent.Progress("p", "12:6", 4, 20), e)
    }

    @Test fun numericNodeIdsBecomeStrings() {
        val e = assertIs<WsEvent.Executing>(WsTextParser.parse("""{"type":"executing","data":{"node":9,"prompt_id":"p"}}"""))
        assertEquals("9", e.node)
    }

    @Test fun progressState() {
        val e = assertIs<WsEvent.ProgressState>(WsTextParser.parse(
            """{"type":"progress_state","data":{"prompt_id":"p","nodes":{"3":{"value":2,"max":8,"state":"running","node_id":"3","display_node_id":"3","parent_node_id":null,"real_node_id":"3"}}}}""",
        ))
        assertEquals(2.0, e.nodes.getValue("3").value)
        assertEquals("running", e.nodes.getValue("3").state)
    }

    @Test fun executionError() {
        val e = assertIs<WsEvent.ExecutionError>(WsTextParser.parse(
            """{"type":"execution_error","data":{"prompt_id":"p","node_id":"5","node_type":"KSampler","exception_message":"boom","exception_type":"RuntimeError","traceback":["a","b"],"timestamp":1}}""",
        ))
        assertEquals("boom", e.exceptionMessage)
        assertEquals(listOf("a", "b"), e.traceback)
    }

    @Test fun unknownTypesAreKept() {
        assertIs<WsEvent.Other>(WsTextParser.parse("""{"type":"crystools.monitor","data":{"cpu":3}}"""))
    }

    @Test fun garbageIsIgnored() {
        assertNull(WsTextParser.parse("not json"))
        assertNull(WsTextParser.parse("[1,2]"))
    }

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    @Test fun legacyPreviewFrame() {
        val img = byteArrayOf(1, 2, 3)
        val f = assertIs<BinaryFrame.Preview>(BinaryFrame.decode(u32(1) + u32(2) + img))
        assertEquals("image/png", f.mime)
        assertEquals(img.toList(), f.imageBytes().toList())
    }

    @Test fun previewWithMetadata() {
        val meta = """{"image_type":"image/jpeg","prompt_id":"p","node_id":"12:6","display_node_id":"12"}""".encodeToByteArray()
        val img = byteArrayOf(9, 9)
        val f = assertIs<BinaryFrame.Preview>(BinaryFrame.decode(u32(4) + u32(meta.size) + meta + img))
        assertEquals("image/jpeg", f.mime)
        assertEquals("p", f.promptId)
        assertEquals("12:6", f.nodeId)
        assertEquals(2, f.imageLength)
    }

    @Test fun textFrame() {
        val id = "7".encodeToByteArray()
        val f = BinaryFrame.decode(u32(3) + u32(id.size) + id + "hello".encodeToByteArray())
        assertEquals(BinaryFrame.Text("7", "hello"), f)
    }

    @Test fun truncatedFramesDoNotThrow() {
        assertIs<BinaryFrame.Unknown>(BinaryFrame.decode(byteArrayOf(0, 0)))
        assertIs<BinaryFrame.Unknown>(BinaryFrame.decode(u32(4) + u32(999)))
    }
}
