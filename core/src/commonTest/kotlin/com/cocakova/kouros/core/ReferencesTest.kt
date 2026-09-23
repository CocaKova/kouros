package com.cocakova.kouros.core

import com.cocakova.kouros.core.api.ObjectInfo
import com.cocakova.kouros.core.form.References
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferencesTest {
    private val oi = ObjectInfo.parse(Json.parseToJsonElement("""
    {
      "Enc": {"input": {"required": {"prompt": ["STRING", {}],
                 "images": ["COMFY_AUTOGROW_V3", {"template": {"input": {"required": {"image": ["IMAGE", {}]}}, "names": ["image_1","image_2","image_3"], "min": 0}}]}},
              "output": ["CONDITIONING"], "output_node": false},
      "Plus": {"input": {"required": {"prompt": ["STRING", {}]}, "optional": {"image1": ["IMAGE", {}], "image2": ["IMAGE", {}], "image3": ["IMAGE", {}]}},
               "output": ["CONDITIONING"], "output_node": false},
      "Batch": {"input": {"required": {"images": ["COMFY_AUTOGROW_V3", {"template": {"input": {"required": {"image": ["IMAGE", {}]}}, "prefix": "image", "max": 5}}]}},
                "output": ["IMAGE"], "output_node": false},
      "LoadImage": {"input": {"required": {"image": [["a.png"], {"image_upload": true}]}}, "output": ["IMAGE", "MASK"], "output_node": false}
    }""").jsonObject)

    private val prompt = Json.parseToJsonElement("""
    {
      "1": {"class_type": "LoadImage", "inputs": {"image": "a.png"}},
      "2": {"class_type": "Enc", "inputs": {"prompt": "x", "images.image_1": ["1", 0]}},
      "3": {"class_type": "Plus", "inputs": {"prompt": "y", "image1": ["1", 0]}},
      "4": {"class_type": "Batch", "inputs": {}}
    }""").jsonObject

    @Test
    fun findsFreeSlotsOnReferenceConsumersOnly() {
        val r = References.of(prompt, oi)
        assertEquals(listOf("images.image_2", "images.image_3"), r.byNode["2"]!!.map { it.input })
        assertEquals(listOf("image2", "image3"), r.byNode["3"]!!.map { it.input })
        assertTrue("4" !in r.byNode) // an image utility, not a reference list
        assertEquals(2, r.capacity)
        assertEquals("image 2", r.labelFor(0))
    }

    @Test
    fun injectsOneLoaderPerPhotoIntoEveryNode() {
        val out = References.of(prompt, oi).inject(prompt, listOf("kouros/p.jpg", "kouros/q.jpg", "kouros/r.jpg"))
        assertEquals("LoadImage", (out["kouros_ref_1"] as JsonObject)["class_type"].toString().trim('"'))
        val enc = (out["2"] as JsonObject)["inputs"] as JsonObject
        assertEquals("[\"kouros_ref_2\",0]", (enc["images.image_3"] as JsonArray).toString())
        val plus = (out["3"] as JsonObject)["inputs"] as JsonObject
        assertEquals("[\"kouros_ref_1\",0]", plus["image2"].toString())
        // A third photo has nowhere to go on a two-slot node; the loader exists but stays unwired.
        assertTrue(plus.keys.none { (plus[it] as? JsonArray)?.toString()?.contains("kouros_ref_3") == true })
    }

    @Test
    fun humanizes() {
        assertEquals("ref image 0", References.humanize("ref_image_0"))
        assertEquals("image 3", References.humanize("image3"))
    }
}
