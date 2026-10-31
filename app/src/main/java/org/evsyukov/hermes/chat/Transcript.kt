package org.evsyukov.hermes.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.evsyukov.hermes.data.remote.GatewayEvent
import org.evsyukov.hermes.data.remote.StoredMessage
import org.evsyukov.hermes.data.remote.double
import org.evsyukov.hermes.data.remote.string

/**
 * Skills or a cron run the server expanded a message into: the chat shows a chip with their names
 * and [full], the text the model got, on demand.
 */
data class Invocation(val cron: Boolean, val skills: List<String>, val full: String)

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
    /** The server's one-line account, like "Did 3 searches in 2s". */
    val summary: String? = null,
    val output: ToolOutput? = null,
)

/** A piece of an answer, in the order the agent produced it. */
sealed interface Part {
    data class Text(val text: String) : Part
    data class Reasoning(val text: String) : Part
    data class Tool(val run: ToolRun) : Part
}

/** How far a message of the user got; only a confirmed one needs no note under the bubble. */
enum class Delivery { SENDING, SENT, UNKNOWN, FAILED }

sealed interface ChatItem {
    val key: String

    /** When the message was written, in epoch seconds as the server keeps it; null for one with no time of its own. */
    val time: Double?

    /**
     * A message the user sent. [text] is what the bubble shows; [sent] is what goes again on
     * regenerate, which for a skill invocation is the whole message the server built from it.
     */
    data class User(
        override val key: String,
        val text: String,
        val attachments: List<Attachment>,
        val rowId: Long? = null,
        /** The message this one replies to, from a chat on Telegram or another platform. */
        val quote: String? = null,
        val invocation: Invocation? = null,
        val sent: String = text,
        /**
         * [Delivery.SENT] once the server took the prompt; [Delivery.UNKNOWN] when the answer was
         * lost after it may have been sent, so the message is kept and no retry is made on its own.
         */
        val delivery: Delivery = Delivery.SENT,
        override val time: Double? = null,
    ) : ChatItem {
        /**
         * The message as it goes again under [key]: regenerate sends what the server got, with the
         * skills it invoked; an edit sends the [replacement] alone.
         */
        fun rewritten(replacement: String?, key: String): User =
            if (replacement == null) copy(key = key) else copy(key = key, text = replacement, sent = replacement, invocation = null, quote = null)
    }

    /** The report of a cron job the server filed into the chat that scheduled the job. */
    data class CronReport(override val key: String, val job: String, val text: String, override val time: Double? = null) : ChatItem

    data class Assistant(
        override val key: String,
        val parts: List<Part>,
        val streaming: Boolean,
        val error: String? = null,
        val interrupted: Boolean = false,
        override val time: Double? = null,
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

// What the server writes into a user message it builds itself, as in its own describer of
// session previews (agent/skill_commands.py, cron/scheduler_prompt.py, gateway/mirror.py).
private const val CRON_REPORT = "[Cron delivery: "
private const val CRON_HINT = "[IMPORTANT: You are running as a scheduled cron job."
private const val SKILL_INVOKED = "[IMPORTANT: The user has invoked the "
private const val SKILL_INSTRUCTION = "The user has provided the following instruction alongside the skill invocation: "
private const val RUNTIME_NOTE = "\n\n[Runtime note:"
private const val BUNDLE = " skill bundle,"
private const val BUNDLE_INSTRUCTION = "\nUser instruction: "
private const val BUNDLE_FIRST_SKILL = "\n\n[Loaded as part of the "
private const val AUTO_LOADED = "[IMPORTANT: The \""
private const val AUTO_LOADED_SUFFIX = "\" skill is auto-loaded."
private const val SKILL_DIR_NOTE_END = "then run them with the terminal tool using the absolute path."
private const val REPLYING_TO = "[Replying to: \""
private val SKILL_NAMES = Regex("""(?m)^\[IMPORTANT: The (?:user has invoked the )?"([^"]*)"""")

/** The job and the text of a cron report the server filed as a user message, or null. */
fun cronReport(text: String): Pair<String, String>? {
    if (!text.startsWith(CRON_REPORT)) return null
    val head = text.substringBefore('\n')
    if (!head.endsWith("]")) return null
    return head.removePrefix(CRON_REPORT).removeSuffix("]") to text.substringAfter('\n', "").trim()
}

/**
 * The skills or the cron run a message was expanded into, and what the user wrote alongside
 * them; null for a message the user wrote as it is. A cron run shows no text of its own: its
 * prompt, the previous report and the notes for the model are all in the full text.
 */
fun invocationOf(text: String): Pair<Invocation, String>? {
    // A bundle names its skills in one quoted span: "/clean /work".
    val skills = SKILL_NAMES.findAll(text).flatMap { it.groupValues[1].split(' ') }.map { it.trimStart('/') }.filter { it.isNotBlank() }.distinct().toList()
    val written = when {
        text.startsWith(CRON_HINT) -> return Invocation(cron = true, skills = emptyList(), full = text) to ""
        text.startsWith(SKILL_INVOKED) && BUNDLE in text.substringBefore('\n') -> between(text, text.indexOf(BUNDLE_INSTRUCTION), BUNDLE_INSTRUCTION, BUNDLE_FIRST_SKILL)
        text.startsWith(SKILL_INVOKED) -> between(text, text.lastIndexOf(SKILL_INSTRUCTION), SKILL_INSTRUCTION, RUNTIME_NOTE)
        text.startsWith(AUTO_LOADED) && AUTO_LOADED_SUFFIX in text.substringBefore('\n') ->
            text.lastIndexOf(SKILL_DIR_NOTE_END).takeIf { it >= 0 }?.let { text.substring(it + SKILL_DIR_NOTE_END.length) }.orEmpty().trim()
        else -> return null
    }
    val cron = written.startsWith(CRON_HINT)
    return Invocation(cron, skills, text) to if (cron) "" else written
}

private fun between(text: String, at: Int, marker: String, end: String): String {
    if (at < 0) return ""
    val from = at + marker.length
    val to = text.indexOf(end, from).takeIf { it >= 0 } ?: text.length
    return text.substring(from, to).trim()
}

/** Splits `[Replying to: "…"]`, which the server puts before a reply on a chat platform, from the reply. */
fun splitQuote(text: String): Pair<String?, String> {
    if (!text.startsWith(REPLYING_TO)) return null to text
    val end = text.indexOf("\"]\n").takeIf { it >= 0 } ?: if (text.endsWith("\"]")) text.length - 2 else return null to text
    return text.substring(REPLYING_TO.length, end) to text.substring(minOf(end + 2, text.length)).trim()
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
        // The parser gives 0.0 to a row the server wrote without a time.
        val time = m.timestamp.takeIf { it != 0.0 }
        when (m.role) {
            "user" -> {
                flush()
                val report = cronReport(m.text)
                if (report != null) {
                    items += ChatItem.CronReport("c${m.rowId}", report.first, report.second, time)
                    continue
                }
                val invocation = invocationOf(m.text)
                val (quote, written) = if (invocation == null) splitQuote(m.text) else null to invocation.second
                val (text, attachments) = splitUserText(written)
                items += ChatItem.User(
                    "u${m.rowId}", text, attachments + m.images.map { Attachment(it, "image", true) }, m.rowId,
                    quote = quote, invocation = invocation?.first, sent = m.text, time = time,
                )
            }
            "assistant" -> {
                val parts = (current?.parts ?: emptyList()).toMutableList()
                m.reasoning?.takeIf { it.isNotBlank() }?.let { parts += Part.Reasoning(it) }
                if (m.text.isNotBlank()) parts += Part.Text(m.text)
                m.toolCalls.forEach { call -> parts += Part.Tool(ToolRun(call.id, call.name, argumentContext(call.arguments), call.arguments, done = true)) }
                // A turn spans several stored rows and keeps the time of the first of them.
                current = ChatItem.Assistant(current?.key ?: "a${m.rowId}", parts, streaming = false, time = current?.time ?: time)
            }
            "tool" -> {
                val turn = current ?: continue
                val parts = turn.parts.map { part ->
                    if (part is Part.Tool && part.run.id == m.toolCallId) Part.Tool(part.run.copy(output = toolOutput(m.text))) else part
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
 * `message.complete` closes the answer with its final text. An answer it opens takes its time from
 * [now], the clock of the phone, in epoch seconds.
 */
fun reduce(items: List<ChatItem>, event: GatewayEvent, keyOf: () -> String, now: () -> Double = ::nowSeconds): List<ChatItem> {
    val payload = event.payload
    return when (event.type) {
        "message.start" -> openAnswer(items, keyOf, now)
        "message.delta" -> {
            val text = (payload["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return items
            updateOpen(openAnswer(items, keyOf, now)) { appendPart(it, text) { old -> Part.Text(old + text) } }
        }
        "message.interim" -> {
            val text = payload.string("text").orEmpty()
            val streamed = payload["already_streamed"]?.let { (it as? JsonPrimitive)?.content == "true" } ?: false
            if (streamed || text.isBlank()) items else updateOpen(openAnswer(items, keyOf, now)) { it + Part.Text(text) }
        }
        "reasoning.delta" -> {
            val text = payload.string("text") ?: return items
            updateOpen(openAnswer(items, keyOf, now)) { parts -> appendReasoning(parts, text) }
        }
        "reasoning.available" -> {
            val text = payload.string("text") ?: return items
            updateOpen(openAnswer(items, keyOf, now)) { parts -> placeReasoning(parts, text) }
        }
        "tool.start" -> {
            val run = ToolRun(
                id = payload.string("tool_id").orEmpty(),
                name = payload.string("name").orEmpty(),
                context = payload.string("context") ?: payload.string("preview").orEmpty(),
                args = payload.string("args_text") ?: (payload["args"] as? JsonObject)?.toString().orEmpty(),
                done = false,
            )
            updateOpen(openAnswer(items, keyOf, now)) { parts ->
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
                                summary = payload.string("summary"),
                                output = payload["result"]?.let(::toolOutput) ?: payload.string("result_text")?.let(::toolOutput),
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
            val opened = openAnswer(items, keyOf, now)
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

/**
 * Picks up a turn that runs while the chat was not open. The server writes the answer down after
 * each round of tools, so the stored history may end with part of it; that part opens again for the
 * events to come. [streamed] is all the text the turn produced so far: what the stored part lacks of
 * it is added, and text that does not continue the stored part is left out rather than shown twice.
 */
fun resumeTurn(items: List<ChatItem>, streamed: String, keyOf: () -> String, now: () -> Double = ::nowSeconds): List<ChatItem> {
    val last = items.lastOrNull()
    if (last is ChatItem.Assistant && last.streaming) return items
    val opened = if (last is ChatItem.Assistant) items.dropLast(1) + last.copy(streaming = true) else openAnswer(items, keyOf, now)
    val stored = (opened.last() as ChatItem.Assistant).parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
    val missing = if (streamed.startsWith(stored)) streamed.substring(stored.length) else ""
    return if (missing.isBlank()) opened else updateOpen(opened) { it + Part.Text(missing) }
}

/** The clock of the phone in epoch seconds, the unit the server keeps its times in. */
internal fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

private fun openAnswer(items: List<ChatItem>, keyOf: () -> String, now: () -> Double): List<ChatItem> {
    val last = items.lastOrNull()
    return if (last is ChatItem.Assistant && last.streaming) items else items + ChatItem.Assistant(keyOf(), emptyList(), streaming = true, time = now())
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
