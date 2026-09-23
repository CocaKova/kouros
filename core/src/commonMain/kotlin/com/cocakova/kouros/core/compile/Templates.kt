package com.cocakova.kouros.core.compile

import com.cocakova.kouros.core.api.InputDef
import com.cocakova.kouros.core.api.ObjectInfo
import com.cocakova.kouros.core.graph.UiWorkflow
import com.cocakova.kouros.core.graph.WorkflowFormat
import com.cocakova.kouros.core.ws.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Where a runnable prompt came from, most faithful first. */
enum class TemplateSource {
    /** The file was already an API prompt. */
    API_FILE,
    /** Compiled here, and the compiler is confident it matches the desktop. */
    COMPILED,
    /** Compiled by the server's own frontend in a hidden browser. */
    FRONTEND,
    /** The prompt this workflow last ran with, from the server's history. */
    HISTORY,
    /** An API prompt embedded in the workflow file. */
    EMBEDDED,
    /** Compiled here although the compiler was unsure — shown with a warning. */
    COMPILED_UNSURE,
}

class RunTemplate(val compiled: CompiledWorkflow, val source: TemplateSource, val workflow: UiWorkflow?) {
    val prompt: JsonObject get() = compiled.prompt
}

object Templates {
    /**
     * Wraps a bare API prompt so the form engine can work with it: every literal input becomes
     * an editable ref, and seed-like inputs get the frontend's default "randomize" control.
     */
    fun fromApiPrompt(prompt: JsonObject, objectInfo: ObjectInfo?): CompiledWorkflow {
        val refs = LinkedHashMap<String, ApiRef>()
        val controls = LinkedHashMap<ApiRef, String>()
        for ((id, n) in prompt) {
            val node = n as? JsonObject ?: continue
            val inputs = node["inputs"] as? JsonObject ?: continue
            val def = node.str("class_type")?.let { objectInfo?.get(it) }
            for ((name, v) in inputs) {
                if (v is JsonArray) continue
                val ref = ApiRef(id, name)
                refs["$id:$name"] = ref
                val spec: InputDef? = def?.input(name)
                val control = spec?.controlAfterGenerate ?: if (name in InputDef.SEED_NAMES) "randomize" else null
                if (control != null) controls[ref] = control
            }
        }
        return CompiledWorkflow(prompt, emptyList(), refs, emptyMap(), controls)
    }

    /**
     * Keeps the form bindings from our own compile but takes the prompt from a more faithful
     * source (the frontend oracle). Node ids agree because both follow the same id rules.
     */
    fun withPrompt(compiled: CompiledWorkflow, prompt: JsonObject): CompiledWorkflow {
        val refs = compiled.widgetRefs.filterValues { ((prompt[it.nodeId] as? JsonObject)?.get("inputs") as? JsonObject)?.containsKey(it.input) == true }
        val promoted = compiled.promoted.mapValues { (_, t) -> t.filter { ((prompt[it.nodeId] as? JsonObject)?.get("inputs") as? JsonObject)?.containsKey(it.input) == true } }
            .filterValues { it.isNotEmpty() }
        return CompiledWorkflow(prompt, emptyList(), refs, promoted, compiled.seedControls)
    }

    /**
     * The local half of template resolution: API files and confident compiles are final; an
     * unsure compile is returned too, marked, so the caller can try the remote sources
     * (frontend oracle, history) before settling for it.
     */
    fun resolveLocal(file: JsonObject, objectInfo: ObjectInfo, adapters: NodeAdapters = NodeAdapters.DEFAULT): RunTemplate {
        if (WorkflowFormat.isApiPrompt(file)) return RunTemplate(fromApiPrompt(file, objectInfo), TemplateSource.API_FILE, null)
        val wf = WorkflowFormat.parse(file)
        val compiled = WorkflowCompiler(objectInfo, adapters).compile(wf)
        if (compiled.confident) return RunTemplate(compiled, TemplateSource.COMPILED, wf)
        wf.embeddedPrompt?.takeIf { WorkflowFormat.isApiPrompt(it) }?.let {
            return RunTemplate(withPrompt(compiled, it), TemplateSource.EMBEDDED, wf)
        }
        return RunTemplate(compiled, TemplateSource.COMPILED_UNSURE, wf)
    }
}
