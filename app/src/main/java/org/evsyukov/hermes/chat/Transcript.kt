package org.evsyukov.hermes.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.evsyukov.hermes.data.remote.GatewayEvent
import org.evsyukov.hermes.data.remote.StoredMessage
import org.evsyukov.hermes.data.remote.double
import org.evsyukov.hermes.data.remote.string

/** A file the user attached: a path on the server, shown as a chip or a picture. */
data class Attachment(val path: String, val name: String, val image: Boolean)

/** A tool the agent ran during a turn. */
data class ToolRun(
    val id: String,
    val name: String,
    val context: String,
    val args: String,
    val done: Boolean,
    val failed: Boolean = false,
    val durationSeconds: Double? = null,
    val summary: String? = null,
)

/** A piece of an answer, in the order the agent produced it. */
sealed interface Part {
    data class Text(val text: String) : Part
    data class Reasoning(val text: String) : Part
    data class Tool(val run: ToolRun) : Part
}

sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String, val attachments: List<Attachment>, val rowId: Long? = null) : ChatItem

    data class Assistant(
        override val key: String,
        val parts: List<Part>,
        val streaming: Boolean,
        val error: String? = null,
        val interrupted: Boolean = false,
    ) : ChatItem {
        /** The answer without tools and reasoning: what copying and reading aloud take. */
        val text: String get() = parts.filterIsInstance<Part.Text>().joinToString("\n\n") { it.text }.trim()
        val media: List<String> get() = mediaPaths(text)
    }
}

private val ATTACHMENT_LINE = Regex("""^@(image|file):(.+)$""")
private val MEDIA_LINE = Regex("""(?m)^[\t ]*[`"']?MEDIA:\s*[`"']?([^`"'\n]+?)[`"']?[\t ]*$""")
private val MEDIA_INLINE = Regex("""[`"']?MEDIA:\s*(`[^`\n]+`|"[^"\n]+"|'[^'\n]+'|[^\s`"]+)""")
private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "svg")

/** Where the server appends what it read from `@file:` references; the user wrote what comes before. */
private val CONTEXT_SECTIONS = listOf("\n\n--- Context Warnings ---", "\n\n--- Attached Context ---")
private val IMAGE_PLACEHOLDERS = setOf("[screenshot]", "[image]")

/**
 * Splits a stored user message into what the user wrote and the files it points at: the server
 * keeps `@image:` and `@file:` lines in the text and appends the content of the files after a
 * `--- Attached Context ---` line, which the chat does not show.
 */
fun splitUserText(text: String): Pair<String, List<Attachment>> {
    val cut = CONTEXT_SECTIONS.mapNotNull { marker -> text.indexOf(marker).takeIf { it >= 0 } }.minOrNull()
    val written = if (cut == null) text else text.substring(0, cut)
    // Pictures sent to the model are named by `@image:` lines the server appends after the context.
    val appended = if (cut == null) emptyList() else text.substring(cut).lines().filter { it.trim().startsWith("@image:") }
    val attachments = mutableListOf<Attachment>()
    val kept = (written.lines() + appended).filter { line -> line.trim() !in IMAGE_PLACEHOLDERS }.filter { line ->
        val match = ATTACHMENT_LINE.matchEntire(line.trim()) ?: return@filter true
        val path = match.groupValues[2].trim()
        val name = path.substringAfterLast('/')
        attachments += Attachment(path, name, match.groupValues[1] == "image" || name.substringAfterLast('.').lowercase() in IMAGE_EXT)
        false
    }
    return kept.joinToString("\n").trim() to attachments.distinctBy { it.path }
}

/** Paths the agent delivered with `MEDIA:` directives, in order and without repeats. */
fun mediaPaths(text: String): List<String> = MEDIA_INLINE.findAll(text)
    .map { it.groupValues[1].trim().trim('`', '"', '\'').trimEnd('.', ',', ';', ':', '!', '?', ')') }
    .filter { it.contains('/') || Regex("""\.[^.]""").containsMatchIn(it) }
    .distinct()
    .toList()

/** The answer text with the `MEDIA:` lines removed; the files show as cards under it. */
fun stripMedia(text: String): String = text.replace(MEDIA_LINE, "").trim()

private val CONTEXT_KEYS = listOf("command", "query", "url", "path", "file_path", "pattern", "name", "action", "goal", "task", "question")

/** A short hint of what a tool call did, from its JSON arguments: the command, the query, the URL. */
fun argumentContext(arguments: String): String {
    val obj = try {
        org.evsyukov.hermes.data.remote.HermesJson.parseToJsonElement(arguments) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return ""
    val value = CONTEXT_KEYS.firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } }.orEmpty()
    return value.lineSequence().first().take(80)
}

/** Builds the transcript from the stored history: tool results join the call that asked for them. */
fun transcriptOf(messages: List<StoredMessage>): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    var current: ChatItem.Assistant? = null
    fun flush() {
        current?.let { items += it }
        current = null
    }
    for (m in messages) {
        if (m.hidden) continue
        when (m.role) {
            "user" -> {
                flush()
                val (text, attachments) = splitUserText(m.text)
                items += ChatItem.User("u${m.rowId}", text, attachments + m.images.map { Attachment(it, "image", true) }, m.rowId)
            }
            "assistant" -> {
                val parts = (current?.parts ?: emptyList()).toMutableList()
                m.reasoning?.takeIf { it.isNotBlank() }?.let { parts += Part.Reasoning(it) }
                if (m.text.isNotBlank()) parts += Part.Text(m.text)
                m.toolCalls.forEach { call -> parts += Part.Tool(ToolRun(call.id, call.name, argumentContext(call.arguments), call.arguments, done = true)) }
                current = ChatItem.Assistant(current?.key ?: "a${m.rowId}", parts, streaming = false)
            }
            "tool" -> {
                val turn = current ?: continue
                val parts = turn.parts.map { part ->
                    if (part is Part.Tool && part.run.id == m.toolCallId) Part.Tool(part.run.copy(summary = m.text.take(2000))) else part
                }
                current = turn.copy(parts = parts)
            }
        }
    }
    flush()
    return items
}

/**
 * Applies one gateway event to the transcript of a live chat, the way the desktop app builds its
 * bubbles: deltas grow the last part of the open answer, tools become parts of their own, and
 * `message.complete` closes the answer with its final text.
 */
fun reduce(items: List<ChatItem>, event: GatewayEvent, keyOf: () -> String): List<ChatItem> {
    val payload = event.payload
    return when (event.type) {
        "message.start" -> openAnswer(items, keyOf)
        "message.delta" -> {
            val text = (payload["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return items
            updateOpen(openAnswer(items, keyOf)) { appendPart(it, text) { old -> Part.Text(old + text) } }
        }
        "message.interim" -> {
            val text = payload.string("text").orEmpty()
            val streamed = payload["already_streamed"]?.let { (it as? JsonPrimitive)?.content == "true" } ?: false
            if (streamed || text.isBlank()) items else updateOpen(openAnswer(items, keyOf)) { it + Part.Text(text) }
        }
        "reasoning.delta" -> {
            val text = payload.string("text") ?: return items
            updateOpen(openAnswer(items, keyOf)) { parts -> appendReasoning(parts, text) }
        }
        "reasoning.available" -> {
            val text = payload.string("text") ?: return items
            updateOpen(openAnswer(items, keyOf)) { parts -> placeReasoning(parts, text) }
        }
        "tool.start" -> {
            val run = ToolRun(
                id = payload.string("tool_id").orEmpty(),
                name = payload.string("name").orEmpty(),
                context = payload.string("context") ?: payload.string("preview").orEmpty(),
                args = payload.string("args_text") ?: (payload["args"] as? JsonObject)?.toString().orEmpty(),
                done = false,
            )
            updateOpen(openAnswer(items, keyOf)) { parts ->
                if (parts.any { it is Part.Tool && it.run.id == run.id }) parts else parts + Part.Tool(run)
            }
        }
        "tool.complete" -> {
            val id = payload.string("tool_id").orEmpty()
            updateOpen(items) { parts ->
                parts.map { part ->
                    if (part is Part.Tool && part.run.id == id) {
                        Part.Tool(
                            part.run.copy(
                                done = true,
                                durationSeconds = payload.double("duration_s"),
                                summary = payload.string("summary") ?: payload.string("result_text")?.take(2000),
                            ),
                        )
                    } else {
                        part
                    }
                }
            }
        }
        "message.complete" -> {
            val status = payload.string("status") ?: "complete"
            val final = payload.string("text")?.takeIf { it.isNotBlank() } ?: payload.string("rendered")?.takeIf { it.isNotBlank() }
            val error = if (status == "error") {
                (payload["error"] as? JsonPrimitive)?.content ?: (payload["error"] as? JsonObject)?.string("message") ?: payload.string("failure_reason") ?: ""
            } else {
                null
            }
            val opened = openAnswer(items, keyOf)
            val last = opened.last() as ChatItem.Assistant
            var parts = last.parts.map { if (it is Part.Tool && !it.run.done) Part.Tool(it.run.copy(done = true)) else it }
            // The final text replaces what streamed, which may lack the rendering the server applied at the end.
            if (final != null) {
                val streamed = parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                if (streamed.trim() != final.trim()) {
                    val lastText = parts.indexOfLast { it is Part.Text }
                    parts = if (lastText >= 0 && parts.count { it is Part.Text } == 1) {
                        parts.toMutableList().also { it[lastText] = Part.Text(final) }
                    } else if (streamed.isBlank()) {
                        parts + Part.Text(final)
                    } else {
                        parts
                    }
                }
            }
            opened.dropLast(1) + last.copy(parts = parts, streaming = false, error = error, interrupted = status == "interrupted")
        }
        else -> items
    }
}

/** Closes an answer that never got `message.complete`, e.g. after the user stopped it. */
fun closeOpen(items: List<ChatItem>, interrupted: Boolean): List<ChatItem> {
    val last = items.lastOrNull() as? ChatItem.Assistant ?: return items
    if (!last.streaming) return items
    val parts = last.parts.map { if (it is Part.Tool && !it.run.done) Part.Tool(it.run.copy(done = true)) else it }
    return items.dropLast(1) + last.copy(parts = parts, streaming = false, interrupted = interrupted)
}

private fun openAnswer(items: List<ChatItem>, keyOf: () -> String): List<ChatItem> {
    val last = items.lastOrNull()
    return if (last is ChatItem.Assistant && last.streaming) items else items + ChatItem.Assistant(keyOf(), emptyList(), streaming = true)
}

private fun updateOpen(items: List<ChatItem>, change: (List<Part>) -> List<Part>): List<ChatItem> {
    val last = items.lastOrNull() as? ChatItem.Assistant ?: return items
    return items.dropLast(1) + last.copy(parts = change(last.parts))
}

private fun appendPart(parts: List<Part>, text: String, grow: (String) -> Part): List<Part> {
    val last = parts.lastOrNull()
    return if (last is Part.Text) parts.dropLast(1) + grow(last.text) else parts + Part.Text(text)
}

/**
 * A whole reasoning block that arrives late, after the answer streamed, goes where it belongs:
 * in place of an earlier one, or before the first piece of text.
 */
private fun placeReasoning(parts: List<Part>, text: String): List<Part> {
    val existing = parts.indexOfFirst { it is Part.Reasoning }
    if (existing >= 0) return parts.toMutableList().also { it[existing] = Part.Reasoning(text) }
    val firstText = parts.indexOfFirst { it is Part.Text }
    return if (firstText < 0) parts + Part.Reasoning(text) else parts.toMutableList().also { it.add(firstText, Part.Reasoning(text)) }
}

private fun appendReasoning(parts: List<Part>, text: String): List<Part> {
    val last = parts.lastOrNull()
    return if (last is Part.Reasoning) parts.dropLast(1) + Part.Reasoning(last.text + text) else parts + Part.Reasoning(text)
}
