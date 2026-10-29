package org.evsyukov.hermes.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.evsyukov.hermes.data.remote.HermesJson

/** What a tool returned, ready to show: prose as Markdown, or what a program printed, as it was. */
data class ToolOutput(val text: String, val markdown: Boolean)

private const val OUTPUT_LIMIT = 2000
private val UNTRUSTED_OPEN = Regex("""^<untrusted_tool_result[^>]*>\s*""")
private val UNTRUSTED_CLOSE = Regex("""\s*</untrusted_tool_result>$""")
private val UNTRUSTED_WARNING = Regex("""^The following content was retrieved[^\n]*\n\n""")
private val FRONT_MATTER = Regex("""^---\n.*?\n---\n+""", RegexOption.DOT_MATCHES_ALL)
private val NUMBERED_LINE = Regex("""^\s*\d+\|""")

/** Fields with what a program printed: a terminal, a script, a diff. */
private val PRINTED_KEYS = listOf("output", "diff", "stdout", "stderr", "error")

/** Fields with prose: a subagent's summary, a skill, a page, a message of the tool. */
private val PROSE_KEYS = listOf("summary", "content", "answer", "analysis", "message", "text")

private val pretty = Json { prettyPrint = true }

/**
 * The readable part of a tool result. The server wraps results from outside in an
 * `<untrusted_tool_result>` envelope with a warning for the model, and most tools answer with JSON
 * whose strings hold the text: those layers are taken off. A JSON without such text stays JSON.
 */
fun toolOutput(raw: String): ToolOutput? {
    var text = raw.trim()
    UNTRUSTED_OPEN.find(text)?.let { open ->
        text = UNTRUSTED_WARNING.replace(UNTRUSTED_CLOSE.replace(text.substring(open.range.last + 1), ""), "").trim()
    }
    if (text.isEmpty()) return null
    val json = if (text.startsWith('{') || text.startsWith('[')) runCatching { HermesJson.parseToJsonElement(text) }.getOrNull() else null
    val output = if (json != null) toolOutput(json) else ToolOutput(text, markdown = false)
    return output?.let { it.copy(text = it.text.take(OUTPUT_LIMIT)) }
}

/** The same for a result the gateway already parsed, as `tool.complete` carries it. */
fun toolOutput(result: JsonElement): ToolOutput? = when (result) {
    is JsonNull -> null
    is JsonPrimitive -> if (result.isString) toolOutput(result.content) else ToolOutput(result.content, markdown = false)
    is JsonObject -> (textOf(result) ?: ToolOutput(pretty.encodeToString(JsonElement.serializer(), result), markdown = false))
        .let { it.copy(text = it.text.take(OUTPUT_LIMIT)) }
    is JsonArray -> ToolOutput(pretty.encodeToString(JsonElement.serializer(), result).take(OUTPUT_LIMIT), markdown = false)
}

private fun textOf(obj: JsonObject): ToolOutput? {
    obj.stringField(PRINTED_KEYS)?.let { return ToolOutput(it, markdown = false) }
    obj.stringField(PROSE_KEYS)?.let { return prose(it) }
    // A subagent batch or extracted pages: the prose of each result.
    val results = (obj["results"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.stringField(PROSE_KEYS) }.orEmpty()
    return if (results.isEmpty()) null else ToolOutput(results.joinToString("\n\n---\n\n") { FRONT_MATTER.replace(it, "") }, markdown = true)
}

/** A file read with line numbers is code; anything else in a prose field is Markdown, minus front matter. */
private fun prose(text: String): ToolOutput =
    if (text.lineSequence().take(3).all { NUMBERED_LINE.containsMatchIn(it) }) ToolOutput(text, markdown = false)
    else ToolOutput(FRONT_MATTER.replace(text, ""), markdown = true)

private fun JsonObject.stringField(keys: List<String>): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
