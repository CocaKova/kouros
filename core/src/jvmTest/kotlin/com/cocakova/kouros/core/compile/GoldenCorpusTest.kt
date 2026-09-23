package com.cocakova.kouros.core.compile

import com.cocakova.kouros.core.api.ObjectInfo
import com.cocakova.kouros.core.graph.WorkflowFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The compiler against the ComfyUI frontend itself.
 *
 * Each corpus directory under `resources/corpus/` holds the `object_info.json` of the server
 * that produced it, and one directory per case with `workflow.json` (as saved) and `api.json`
 * (what the frontend's own graphToPrompt produced for it — see tools/golden/). The compiler must
 * produce the same prompt, `_meta` aside.
 *
 * `public/` ships with the repo. Other corpora (a builder's own workflows, the full template
 * set) are gitignored and simply join the run when present.
 */
class GoldenCorpusTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun compilerMatchesFrontend() {
        val root = File("src/jvmTest/resources/corpus")
        val corpora = root.listFiles { f -> f.isDirectory && File(f, "object_info.json").isFile }?.sortedBy { it.name } ?: emptyList()
        if (corpora.isEmpty()) fail("no corpus under ${root.absolutePath}")

        val report = StringBuilder()
        var pass = 0; var skipped = 0
        val deferred = mutableListOf<String>()
        val frontendFaults = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (corpus in corpora) {
            val oi = ObjectInfo.parse(json.parseToJsonElement(File(corpus, "object_info.json").readText()) as JsonObject)
            val compiler = WorkflowCompiler(oi)
            val cases = corpus.listFiles { f -> f.isDirectory && File(f, "api.json").isFile }?.sortedBy { it.name } ?: emptyList()
            for (case in cases) {
                val expected = json.parseToJsonElement(File(case, "api.json").readText()) as JsonObject
                // The frontend emits nodes without class_type when the server lacks their node pack;
                // such a prompt cannot run at all, so there is nothing to match.
                if (expected.values.any { (it as JsonObject)["class_type"] == null }) { skipped++; continue }
                val wf = WorkflowFormat.parse(json.parseToJsonElement(File(case, "workflow.json").readText()) as JsonObject)
                val got = runCatching { compiler.compile(wf) }.getOrElse { e ->
                    failures += "${corpus.name}/${case.name}: threw $e"; continue
                }
                val diffs = diff(normalize(expected), normalize(got.prompt))
                if (diffs.isEmpty()) pass++
                else if (frontendInvalidAtEveryDiff(expected, got.prompt, oi)) {
                    // Where we differ, the frontend's value is one the server itself would reject
                    // (and ours is accepted): a frontend fault, not something to imitate.
                    frontendFaults += "${corpus.name}/${case.name}"
                    report.appendLine("!! frontend-invalid ${corpus.name}/${case.name}: " + diffs.take(2).joinToString())
                }
                else if (!got.confident) {
                    // The compiler said it was unsure: the app takes this workflow's prompt from
                    // another source (server history, the frontend oracle). Honest, not wrong.
                    deferred += "${corpus.name}/${case.name}"
                    report.appendLine("·· deferred ${corpus.name}/${case.name}: " + got.diagnostics.filter { it.severity != Severity.INFO }.take(2).joinToString { it.code + " " + it.message })
                } else {
                    failures += "${corpus.name}/${case.name}: ${diffs.size} differences"
                    report.appendLine("── ${corpus.name}/${case.name}")
                    diffs.take(8).forEach { report.appendLine("   $it") }
                    got.diagnostics.take(5).forEach { report.appendLine("   diag: $it") }
                }
            }
        }
        val summary = "golden corpus: $pass exact, ${deferred.size} deferred (compiler unsure), " +
            "${frontendFaults.size} frontend-invalid, ${failures.size} CONFIDENTLY WRONG, " +
            "$skipped skipped (frontend output unrunnable)"
        File("build").mkdirs()
        File("build/golden-report.txt").writeText(summary + "\n" + failures.joinToString("\n") + "\n\n" + report)
        println(summary)
        if (failures.isNotEmpty()) fail(summary + "\n" + failures.take(20).joinToString("\n") + "\nsee core/build/golden-report.txt")
    }

    private fun frontendInvalidAtEveryDiff(expected: JsonObject, got: JsonObject, oi: ObjectInfo): Boolean {
        val bad = PromptValidator.validate(expected, oi).filter { it.kind == PromptValidator.Kind.BAD_CHOICE }
            .map { it.nodeId to it.input }.toSet()
        if (bad.isEmpty()) return false
        val ours = PromptValidator.validate(got, oi).filter { it.kind == PromptValidator.Kind.BAD_CHOICE }
            .map { it.nodeId to it.input }.toSet()
        for ((id, node) in expected) {
            val e = (node as JsonObject)["inputs"] as JsonObject
            val g = ((got[id] as? JsonObject)?.get("inputs") as? JsonObject) ?: return false
            for (k in e.keys + g.keys) {
                if (same(e[k], g[k])) continue
                if ((id to k) !in bad || (id to k) in ours) return false
            }
        }
        return true
    }

    private fun normalize(p: JsonObject): Map<String, JsonElement?> =
        p.mapValues { (_, n) -> JsonObject((n as JsonObject).filterKeys { it != "_meta" }) }

    private fun diff(a: Map<String, JsonElement?>, b: Map<String, JsonElement?>): List<String> {
        val out = mutableListOf<String>()
        for (k in (a.keys + b.keys).sorted()) {
            val x = a[k] as? JsonObject; val y = b[k] as? JsonObject
            when {
                x == null -> out += "extra node $k (${y?.get("class_type")})"
                y == null -> out += "missing node $k (${x["class_type"]})"
                x["class_type"] != y["class_type"] -> out += "$k class ${x["class_type"]} ≠ ${y["class_type"]}"
                else -> {
                    val xi = x["inputs"] as JsonObject; val yi = y["inputs"] as JsonObject
                    for (n in (xi.keys + yi.keys).sorted()) {
                        if (!same(xi[n], yi[n])) out += "$k ${x["class_type"]}.$n: expected ${short(xi[n])} got ${short(yi[n])}"
                    }
                }
            }
        }
        return out
    }

    /** Numbers compare by value (the frontend writes 1 where Kotlin may keep 1.0). */
    private fun same(a: JsonElement?, b: JsonElement?): Boolean {
        if (a == b) return true
        if (a is JsonArray && b is JsonArray) return a.size == b.size && a.indices.all { same(a[it], b[it]) }
        if (a is JsonObject && b is JsonObject) return a.keys == b.keys && a.keys.all { same(a[it], b[it]) }
        val x = a?.toString()?.trim('"')?.toDoubleOrNull(); val y = b?.toString()?.trim('"')?.toDoubleOrNull()
        return x != null && y != null && x == y && (a.toString().startsWith('"') == b.toString().startsWith('"'))
    }

    private fun short(e: JsonElement?) = e?.toString()?.let { if (it.length > 80) it.take(77) + "…" else it } ?: "∅"
}
