package com.cocakova.pygmalion.core.compile

import com.cocakova.pygmalion.core.api.ObjectInfo
import com.cocakova.pygmalion.core.form.FormEngine
import com.cocakova.pygmalion.core.graph.WorkflowFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test

/** Not an assertion: a readable dump of the form chosen for every corpus workflow, for review. */
class FormReportTest {
    @Test
    fun writeReport() {
        val json = Json { ignoreUnknownKeys = true }
        val out = StringBuilder()
        val root = File("src/jvmTest/resources/corpus")
        for (corpus in root.listFiles { f -> File(f, "object_info.json").isFile }!!.sortedBy { it.name }) {
            val oi = ObjectInfo.parse(json.parseToJsonElement(File(corpus, "object_info.json").readText()) as JsonObject)
            for (case in corpus.listFiles { f -> File(f, "workflow.json").isFile }!!.sortedBy { it.name }) {
                val wf = WorkflowFormat.parse(json.parseToJsonElement(File(case, "workflow.json").readText()) as JsonObject)
                val form = FormEngine(oi).build(WorkflowCompiler(oi).compile(wf), wf)
                out.appendLine("── ${corpus.name}/${case.name}")
                form.hero.forEach { out.appendLine("  ★ ${it.label} [${it.role}/${it.origin}/${it.score}] = ${it.initial.toString().take(50)}") }
                out.appendLine("  + ${form.advanced.size} advanced: " + form.advanced.take(8).joinToString { it.label })
            }
        }
        File("build").mkdirs()
        File("build/form-report.txt").writeText(out.toString())
    }
}
