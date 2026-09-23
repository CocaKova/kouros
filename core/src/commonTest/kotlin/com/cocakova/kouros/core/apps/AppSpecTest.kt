package com.cocakova.kouros.core.apps

import com.cocakova.kouros.core.api.ObjectInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSpecTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val spec = """
        {
          "id": "upscale",
          "title": "Upscale",
          "tagline": "bigger",
          "action": "Enlarge",
          "icon": "upscale",
          "kind": "IMAGE",
          "packs": [{"id": "pack", "title": "A pack", "repository": "https://example.com/pack", "classes": ["FancyUpscale"]}],
          "models": [{"name": "big.safetensors", "directory": "upscale_models", "url": "https://example.com/big.safetensors", "size": 1000}],
          "form": {"pinned": ["1:image"], "labels": {"1:image": "Photo"}},
          "prompt": {
            "1": {"class_type": "LoadImage", "inputs": {"image": "example.png"}},
            "2": {"class_type": "FancyUpscale", "inputs": {"image": ["1", 0], "model": "big.safetensors"}}
          }
        }
    """.trimIndent()

    private fun parse(text: String = spec) = AppSpec.parse(json.parseToJsonElement(text) as JsonObject)

    private fun objectInfo(vararg classes: String) = ObjectInfo.parse(
        json.parseToJsonElement(classes.joinToString(",", "{", "}") { """"$it": {"input": {}, "output": []}""" }) as JsonObject,
    )

    @Test fun readsAnAppAndItsFormArrangement() {
        val a = parse()!!
        assertEquals("upscale", a.id)
        assertEquals("Enlarge", a.action)
        assertEquals(listOf("1:image"), a.layout.pinned)
        assertEquals("Photo", a.layout.labels["1:image"])
        assertEquals(setOf("upscale_models"), a.modelFolders)
    }

    @Test fun refusesAnAppWhoseWorkflowIsNotAnApiPrompt() {
        // A workflow as the frontend saves it: nodes and links, not class_type and inputs.
        val frontend = """{"id": "x", "title": "X", "prompt": {"nodes": [], "links": []}}"""
        assertNull(AppSpec.parse(json.parseToJsonElement(frontend) as JsonObject))
        assertNull(AppSpec.parse(json.parseToJsonElement("""{"id": "x", "title": "X"}""") as JsonObject))
    }

    @Test fun refusesAnUnusableId() {
        assertNull(parse(spec.replace(""""id": "upscale"""", """"id": "../etc/passwd"""")))
    }

    @Test fun aMissingClassMeansTheNodePackIsMissing() {
        val a = parse()!!
        val needs = a.needs(objectInfo("LoadImage"), mapOf("upscale_models" to setOf("big.safetensors")))
        assertEquals(listOf("pack"), needs.packs.map { it.id })
        assertTrue(needs.models.isEmpty())
        assertTrue(!needs.met)
    }

    @Test fun aFileTheServerDoesNotOfferIsMissingWhateverItsCase() {
        val a = parse()!!
        val oi = objectInfo("LoadImage", "FancyUpscale")
        assertEquals(listOf("big.safetensors"), a.needs(oi, mapOf("upscale_models" to setOf("other.safetensors"))).models.map { it.name })
        assertTrue(a.needs(oi, mapOf("upscale_models" to setOf("BIG.safetensors"))).met)
        // Model folders are listed with their subfolder on some servers.
        assertTrue(a.needs(oi, mapOf("upscale_models" to setOf("ESRGAN/big.safetensors"))).met)
    }

    @Test fun aFolderTheServerNeverListedIsNotGuessedAtMissing() {
        val a = parse()!!
        assertTrue(a.needs(objectInfo("LoadImage", "FancyUpscale"), emptyMap()).met)
    }

    @Test fun downloadSizeAddsUpAndIsUnknownWhenOneSizeIs() {
        val a = parse()!!
        val oi = objectInfo("LoadImage", "FancyUpscale")
        assertEquals(1000L, a.needs(oi, mapOf("upscale_models" to emptySet<String>())).downloadSize)
        val sizeless = parse(spec.replace(""", "size": 1000""", ""))!!
        assertNull(sizeless.needs(oi, mapOf("upscale_models" to emptySet<String>())).downloadSize)
    }

    @Test fun linksAreHandedToTheMissingModelPanelByFileName() {
        val a = parse()!!
        assertEquals("https://example.com/big.safetensors", a.modelLinks()["big.safetensors"]?.url)
        assertEquals("upscale_models", a.modelLinks()["big.safetensors"]?.directory)
    }

    @Test fun onlyHttpsLinksSurvive() {
        val a = parse(spec.replace("https://example.com/big.safetensors", "http://example.com/big.safetensors"))!!
        assertNull(a.models.single().url)
        assertNull(AppSpec.parse(json.parseToJsonElement(spec.replace("https://example.com/pack", "git@example.com:pack")) as JsonObject)!!.packs.firstOrNull())
    }

    @Test fun anAppSurvivesBeingWrittenBackOut() {
        val a = parse()!!
        val again = AppSpec.parse(json.parseToJsonElement(AppCatalog.encode(a)) as JsonObject)
        assertEquals(a, again)
    }

    @Test fun theCatalogTakesEitherShapeAndDropsDuplicates() {
        assertEquals(1, AppCatalog.parse("""{"version": 1, "apps": [$spec]}""").size)
        assertEquals(1, AppCatalog.parse("[$spec]").size)
        assertEquals(1, AppCatalog.parse("[$spec,$spec]").size)
        assertTrue(AppCatalog.parse("not json").isEmpty())
    }
}
