package com.cocakova.kouros.core.form

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The person's own arrangement of a workflow's form, laid over what [FormEngine] chose:
 * which fields sit up top and in what order, which are hidden, what they're called, and named
 * sets of values to come back to. Keys are [FormField.key]s, which are stable across compiles;
 * a key that no longer exists (the workflow changed) is simply ignored.
 */
@Serializable
data class FormLayout(
    /** Fields shown up top, in this order. Empty = the engine's choice. */
    val pinned: List<String> = emptyList(),
    /** Fields kept out of sight (they still run with their values). */
    val hidden: Set<String> = emptySet(),
    /** Field key → the name the person gave it. */
    val labels: Map<String, String> = emptyMap(),
    /** Preset name → field values (and the references, under the same key a run uses). */
    val presets: Map<String, Map<String, JsonElement>> = emptyMap(),
) {
    val customized: Boolean get() = pinned.isNotEmpty() || hidden.isNotEmpty() || labels.isNotEmpty()

    /** [form] rearranged: pinned fields first as the hero, the rest in Advanced, hidden ones gone. */
    fun apply(form: Form): Form {
        if (!customized) return form
        val all = form.all.map { f -> labels[f.key]?.takeIf { it.isNotBlank() }?.let { f.copy(label = it) } ?: f }
        val byKey = all.associateBy { it.key }
        val heroKeys = if (pinned.isNotEmpty()) pinned.filter { it in byKey } else form.hero.map { it.key }
        val hero = heroKeys.filter { it !in hidden }.mapNotNull { byKey[it] }
        val advanced = all.filter { it.key !in heroKeys && it.key !in hidden }
        return form.copy(hero = hero, advanced = advanced)
    }

    /** The fields hidden from [form], so the editor can offer them back. */
    fun hiddenFields(form: Form): List<FormField> = form.all.filter { it.key in hidden }

    // Editing. Each returns a new layout; [current] is what is on screen now.

    fun pin(key: String, current: Form): FormLayout = copy(pinned = heroOf(current) - key + key, hidden = hidden - key)
    fun unpin(key: String, current: Form): FormLayout = copy(pinned = heroOf(current) - key)
    fun hide(key: String, current: Form): FormLayout = copy(pinned = heroOf(current) - key, hidden = hidden + key)
    fun show(key: String): FormLayout = copy(hidden = hidden - key)
    fun rename(key: String, label: String?): FormLayout =
        copy(labels = if (label.isNullOrBlank()) labels - key else labels + (key to label.trim()))

    /** Moves a pinned field [by] places (negative = up). */
    fun move(key: String, by: Int, current: Form): FormLayout {
        val list = heroOf(current).toMutableList()
        val i = list.indexOf(key)
        if (i < 0) return this
        val j = (i + by).coerceIn(0, list.lastIndex)
        list.removeAt(i); list.add(j, key)
        return copy(pinned = list)
    }

    fun savePreset(name: String, values: Map<String, JsonElement>) = copy(presets = presets + (name.trim() to values))
    fun deletePreset(name: String) = copy(presets = presets - name)

    /** Back to the engine's choice; presets are kept. */
    fun reset() = FormLayout(presets = presets)

    private fun heroOf(current: Form) = current.hero.map { it.key }.filter { it !in hidden }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(text: String?): FormLayout =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: FormLayout()
    }
}
