package com.cocakova.pygmalion.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class OutputsTest {
    private fun o(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun imagesVideoAudioAndUnknownPacks() {
        val items = Outputs.classify(
            "9",
            o(
                """{"images":[{"filename":"a.png","subfolder":"","type":"output"}],
                   "gifs":[{"filename":"b.mp4","subfolder":"v","type":"output","format":"video/h264-mp4"}],
                   "audio":[{"filename":"c.flac","subfolder":"","type":"output"}],
                   "whatever_pack":[{"filename":"d.bin","subfolder":"","type":"output"}],
                   "text":["hello"]}""",
            ),
        )
        assertEquals(
            listOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.FILE, MediaKind.TEXT),
            items.map { it.kind },
        )
    }

    @Test fun animatedFlag() {
        val items = Outputs.classify("1", o("""{"images":[{"filename":"x.webp","subfolder":"","type":"output"}],"animated":[true]}"""))
        assertEquals(MediaKind.ANIMATED, items.single().kind)
    }

    @Test fun tempOutputsAreMarked() {
        val items = Outputs.classify("1", o("""{"images":[{"filename":"x.png","subfolder":"","type":"temp"}]}"""))
        assertEquals(true, items.single().isTemp)
    }
}
