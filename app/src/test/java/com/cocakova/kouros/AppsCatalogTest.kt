package com.cocakova.kouros

import com.cocakova.kouros.core.apps.AppCatalog
import com.cocakova.kouros.core.apps.AppSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The apps this build ships, checked against themselves: an app whose form points at a field
 * its prompt doesn't have, or that loads a model file it never declares, would look fine on the
 * shelf and fail on someone's server.
 */
class AppsCatalogTest {
    private val apps: List<AppSpec> = AppCatalog.parse(
        listOf("src/main/assets/apps.json", "app/src/main/assets/apps.json").map(::File).first { it.isFile }.readText(),
    )

    private val modelFile = Regex("""\.(safetensors|ckpt|pt|pth|bin|gguf|sft|onnx)$""", RegexOption.IGNORE_CASE)

    private fun AppSpec.fieldKeys(): Set<String> = prompt.flatMap { (id, node) ->
        ((node as? JsonObject)?.get("inputs") as? JsonObject)?.filterValues { it !is JsonArray }?.keys?.map { "$id:$it" } ?: emptyList()
    }.toSet()

    @Test fun theCatalogIsThere() {
        assertTrue("expected the shipped apps, found ${apps.size}", apps.size >= 2)
        assertEquals("app ids must be unique", apps.map { it.id }.distinct().size, apps.size)
        for (a in apps) {
            assertTrue("${a.id} is missing its words", a.title.isNotBlank() && a.tagline.isNotBlank() && a.action.isNotBlank())
        }
    }

    @Test fun everyLinkPointsAtANodeThatExists() {
        for (a in apps) for ((id, node) in a.prompt) {
            val inputs = (node as? JsonObject)?.get("inputs") as? JsonObject ?: continue
            for ((name, v) in inputs) {
                val link = v as? JsonArray ?: continue
                val target = (link.firstOrNull() as? JsonPrimitive)?.contentOrNull
                assertTrue("${a.id}: $id.$name links to a node that isn't there", target != null && a.prompt.containsKey(target))
            }
        }
    }

    @Test fun everyArrangedFieldExistsInThePrompt() {
        for (a in apps) {
            val keys = a.fieldKeys()
            val referenced = a.layout.pinned + a.layout.hidden + a.layout.labels.keys + a.layout.presets.values.flatMap { it.keys }
            val strays = referenced.filterNot { it in keys }
            assertTrue("${a.id}: the form points at fields the prompt doesn't have: $strays", strays.isEmpty())
        }
    }

    @Test fun nothingIsBothPinnedAndHidden() {
        for (a in apps) {
            val both = a.layout.pinned.filter { it in a.layout.hidden }
            assertTrue("${a.id}: $both is pinned and hidden at once", both.isEmpty())
        }
    }

    @Test fun everyModelFileTheAppLoadsIsDeclaredWithALink() {
        for (a in apps) {
            val declared = a.models.map { it.name.lowercase() }.toSet()
            val loaded = a.prompt.values.flatMap { n ->
                ((n as? JsonObject)?.get("inputs") as? JsonObject)?.values.orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .filter { modelFile.containsMatchIn(it) }
            }
            for (f in loaded) {
                assertTrue("${a.id} loads $f but never declares it", f.substringAfterLast('/').lowercase() in declared)
            }
            for (m in a.models) {
                assertTrue("${a.id}: ${m.name} has no link, so setup can't fetch it", m.url != null)
                assertTrue("${a.id}: ${m.name} has no size, so setup can't say how big it is", (m.size ?: 0) > 0)
                assertTrue("${a.id}: ${m.name} has no model folder", m.directory.isNotBlank())
            }
        }
    }

    @Test fun everyPackIsDetectedByAClassTheAppActuallyRuns() {
        for (a in apps) {
            val used = a.prompt.values.mapNotNull { (it as? JsonObject)?.get("class_type").let { c -> (c as? JsonPrimitive)?.contentOrNull } }.toSet()
            for (p in a.packs) {
                assertTrue("${a.id}: pack ${p.id} is detected by classes the prompt never runs", p.classes.any { it in used })
                assertTrue("${a.id}: pack ${p.id} has no https repository", p.repository.startsWith("https://"))
            }
            // A class from no declared pack must be one a stock server has; the corpus can't know
            // that here, so at least every custom-looking class is claimed by some pack.
            val claimed = a.packs.flatMap { it.classes }.toSet()
            val suspicious = used.filter { it.contains('_') && it !in claimed && !it.startsWith("Image") }
            assertTrue("${a.id}: no pack claims $suspicious", suspicious.isEmpty())
        }
    }

    @Test fun eachAppSavesSomething() {
        for (a in apps) {
            val outputs = a.prompt.values.count { n ->
                val cls = ((n as? JsonObject)?.get("class_type") as? JsonPrimitive)?.contentOrNull ?: ""
                cls.startsWith("Save") || cls.startsWith("Preview")
            }
            assertTrue("${a.id} never saves its result", outputs > 0)
        }
    }

    @Test fun eachAppTakesAPhotoAndPinsItFirst() {
        for (a in apps) {
            val loader = a.prompt.entries.firstOrNull { (_, n) ->
                ((n as? JsonObject)?.get("class_type") as? JsonPrimitive)?.contentOrNull == "LoadImage"
            }
            assertTrue("${a.id} has nothing to feed it", loader != null)
            assertEquals("${a.id}: the photo should be the first thing asked for", "${loader!!.key}:image", a.layout.pinned.firstOrNull())
        }
    }
}
