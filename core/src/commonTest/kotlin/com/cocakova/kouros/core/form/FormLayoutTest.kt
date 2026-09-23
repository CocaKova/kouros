package com.cocakova.kouros.core.form

import com.cocakova.kouros.core.api.InputDef
import com.cocakova.kouros.core.api.TemplateCatalog
import com.cocakova.kouros.core.compile.ApiRef
import com.cocakova.kouros.core.compile.PromptValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormLayoutTest {
    private fun field(key: String, label: String = key) = FormField(
        key = key, label = label, group = "g", role = FieldRole.OTHER,
        spec = InputDef(key, "INT", false, JsonObject(emptyMap()), null),
        targets = listOf(ApiRef("1", key)), initial = JsonPrimitive(0), score = 0, origin = FieldOrigin.SCORED,
    )
    private val form = Form(hero = listOf(field("prompt"), field("seed")), advanced = listOf(field("steps"), field("cfg")), outputNodes = emptyList())
    private fun Form.keys() = hero.map { it.key } to advanced.map { it.key }

    @Test fun untouchedLayoutKeepsTheEngineChoice() {
        assertEquals(form, FormLayout().apply(form))
    }

    @Test fun pinningFromAdvancedAddsToTheEndOfTheTop() {
        val l = FormLayout().pin("steps", form)
        assertEquals(listOf("prompt", "seed", "steps") to listOf("cfg"), l.apply(form).keys())
    }

    @Test fun hidingRemovesAFieldEverywhereAndShowBringsItBack() {
        val l = FormLayout().hide("seed", form).hide("cfg", form)
        assertEquals(listOf("prompt") to listOf("steps"), l.apply(form).keys())
        assertEquals(listOf("seed", "cfg"), l.hiddenFields(form).map { it.key })
        assertEquals(listOf("prompt") to listOf("seed", "steps"), l.show("seed").apply(form).keys())
    }

    @Test fun movingReordersThePinnedFields() {
        val l = FormLayout().pin("cfg", form)
        val shown = l.apply(form)
        assertEquals(listOf("cfg", "prompt", "seed"), l.move("cfg", -5, shown).apply(form).hero.map { it.key })
    }

    @Test fun renamesAreAppliedAndBlankClearsThem() {
        val l = FormLayout().rename("steps", "  Quality ")
        assertEquals("Quality", l.apply(form).advanced.first { it.key == "steps" }.label)
        assertFalse(l.rename("steps", "").customized)
    }

    @Test fun keysTheWorkflowNoLongerHasAreIgnored() {
        val l = FormLayout(pinned = listOf("gone", "seed"), hidden = setOf("also-gone"))
        assertEquals(listOf("seed") to listOf("prompt", "steps", "cfg"), l.apply(form).keys())
    }

    @Test fun roundTripsThroughJsonWithPresets() {
        val l = FormLayout().pin("steps", form).savePreset(" Portrait ", mapOf("steps" to JsonPrimitive(30)))
        val back = FormLayout.decode(l.encode())
        assertEquals(l, back)
        assertEquals(JsonPrimitive(30), back.presets["Portrait"]?.get("steps"))
        assertTrue(back.reset().presets.isNotEmpty() && !back.reset().customized)
        assertEquals(FormLayout(), FormLayout.decode("not json"))
    }
}

class TemplateCatalogTest {
    private val index = Json.parseToJsonElement(
        """[{"moduleName":"default","title":"Image","type":"image","templates":[
             {"name":"img_a","title":"A","description":"d","mediaType":"image","mediaSubtype":"webp","tags":["T2I"],"models":["Z"],"size":2048,"openSource":true},
             {"name":"api_b","title":"B","mediaType":"image","mediaSubtype":"webp","openSource":false}]},
           {"title":"Audio","type":"audio","templates":[{"name":"song","title":"S","mediaType":"audio","mediaSubtype":"mp3"},
             {"name":"img_a","title":"dup"}]}]""",
    )

    @Test fun parsesGroupsKindsAndPaths() {
        val all = TemplateCatalog.parse(index)
        assertEquals(listOf("img_a", "api_b", "song"), all.map { it.name })
        val a = all[0]
        assertEquals("Image", a.group); assertEquals("image", a.kind); assertEquals(2048L, a.size)
        assertEquals("/templates/img_a.json", a.workflowPath); assertEquals("/templates/img_a-1.webp", a.thumbnailPath)
        assertTrue(a.runsLocally)
        assertFalse(all[1].runsLocally)
        assertTrue(all[2].runsLocally) // no flag: assume it runs locally
        assertFalse(all[2].thumbnailIsImage)
    }
}

class ModelNeedsTest {
    private val workflow = Json.parseToJsonElement(
        """{"nodes":[{"id":1,"type":"VAELoader","properties":{"models":[{"name":"ae.safetensors","url":"https://hf.co/ae.safetensors","directory":"vae"}]}}],
            "definitions":{"subgraphs":[{"nodes":[{"id":2,"type":"UNETLoader","properties":{"models":[{"name":"unet.safetensors","url":"http://insecure/unet","directory":"diffusion_models"}]}}]}]}}""",
    ) as JsonObject

    private fun bad(v: String) = PromptValidator.Issue(PromptValidator.Kind.BAD_CHOICE, "1", "x", v, "")

    @Test fun readsLinksFromRootAndSubgraphsKeepingOnlyHttps() {
        val links = ModelNeeds.links(workflow)
        assertEquals(ModelNeeds.Link("https://hf.co/ae.safetensors", "vae"), links["ae.safetensors"])
        assertNull(links["unet.safetensors"]?.url)
        assertEquals("diffusion_models", links["unet.safetensors"]?.directory)
    }

    @Test fun listsMissingModelFilesOnce() {
        val needs = ModelNeeds.missing(
            listOf(bad("sub/ae.safetensors"), bad("ae.safetensors"), bad("euler_x"), bad("other.gguf"),
                PromptValidator.Issue(PromptValidator.Kind.MISSING_NODE, "3", null, "Foo", "")),
            ModelNeeds.links(workflow),
        )
        assertEquals(listOf("sub/ae.safetensors", "other.gguf"), needs.map { it.name })
        assertEquals("vae", needs[0].directory)
        assertNull(needs[1].url)
    }

    @Test fun anEmptyModelFolderStillCountsAsMissing() {
        val combo = InputDef("unet_name", "COMBO", false, JsonObject(emptyMap()), emptyList())
        val issue = PromptValidator.checkChoice("1", "UNETLoader", combo, JsonPrimitive("z_image_turbo_bf16.safetensors"))
        assertEquals(PromptValidator.Kind.BAD_CHOICE, issue?.kind)
        // A value filled in at run time (an empty dynamic list) is left alone.
        assertNull(PromptValidator.checkChoice("1", "X", combo.copy(name = "mode"), JsonPrimitive("auto")))
    }
}
