package org.evsyukov.hermes.chat

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import org.evsyukov.hermes.data.remote.GatewayEvent
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.remote.StoredMessage
import org.evsyukov.hermes.data.remote.ToolCallInfo
import org.junit.Test

class TranscriptTest {
    private var counter = 0
    private val keys = { "k${counter++}" }

    private fun event(type: String, payload: String = "{}") =
        GatewayEvent(type, "s1", null, HermesJson.parseToJsonElement(payload).jsonObject)

    private fun play(vararg events: GatewayEvent, start: List<ChatItem> = emptyList()): List<ChatItem> =
        events.fold(start) { items, e -> reduce(items, e, keys) }

    @Test
    fun `a streamed turn keeps reasoning, tools and text in order`() {
        val items = play(
            event("message.start"),
            event("reasoning.delta", """{"text":"Ду"}"""),
            event("reasoning.delta", """{"text":"маю"}"""),
            event("tool.start", """{"tool_id":"c1","name":"terminal","context":"ls -la"}"""),
            event("tool.complete", """{"tool_id":"c1","name":"terminal","duration_s":0.4,"summary":"3 files"}"""),
            event("message.delta", """{"text":"Вот "}"""),
            event("message.delta", """{"text":"файлы"}"""),
            event("message.complete", """{"text":"Вот файлы","status":"complete"}"""),
        )
        val answer = items.single() as ChatItem.Assistant
        assertThat(answer.streaming).isFalse()
        assertThat(answer.parts).containsExactly(
            Part.Reasoning("Думаю"),
            Part.Tool(ToolRun("c1", "terminal", "ls -la", "", done = true, durationSeconds = 0.4, summary = "3 files")),
            Part.Text("Вот файлы"),
        ).inOrder()
    }

    @Test
    fun `reasoning that arrives after the answer goes before it`() {
        val items = play(
            event("message.delta", """{"text":"Ответ"}"""),
            event("reasoning.available", """{"text":"Думал"}"""),
        )
        assertThat((items.single() as ChatItem.Assistant).parts).containsExactly(Part.Reasoning("Думал"), Part.Text("Ответ")).inOrder()
    }

    @Test
    fun `the final text replaces a stream that differs from it`() {
        val items = play(
            event("message.delta", """{"text":"черно"}"""),
            event("message.complete", """{"text":"Чистовик","status":"complete"}"""),
        )
        assertThat((items.single() as ChatItem.Assistant).text).isEqualTo("Чистовик")
    }

    @Test
    fun `an empty final text keeps what streamed`() {
        val items = play(event("message.delta", """{"text":"ответ"}"""), event("message.complete", """{"status":"complete"}"""))
        assertThat((items.single() as ChatItem.Assistant).text).isEqualTo("ответ")
    }

    @Test
    fun `an error closes the answer with its message`() {
        val items = play(event("message.start"), event("message.complete", """{"status":"error","error":"rate limit"}"""))
        val answer = items.single() as ChatItem.Assistant
        assertThat(answer.error).isEqualTo("rate limit")
        assertThat(answer.streaming).isFalse()
    }

    @Test
    fun `a delta after the user message opens a new answer`() {
        val start = listOf<ChatItem>(ChatItem.User("u1", "привет", emptyList()))
        val items = play(event("message.delta", """{"text":"Здравствуйте"}"""), start = start)
        assertThat(items).hasSize(2)
        assertThat((items.last() as ChatItem.Assistant).streaming).isTrue()
    }

    @Test
    fun `a running turn the history ends with opens again and gets the text it lacks`() {
        val tool = Part.Tool(ToolRun("c1", "terminal", "ls", "", done = true))
        val stored = listOf(ChatItem.User("u1", "найди", emptyList()), ChatItem.Assistant("a2", listOf(Part.Text("Ищу. "), tool), streaming = false))
        val items = resumeTurn(stored, "Ищу. Нашёл", keys)
        assertThat(items).hasSize(2)
        val answer = items.last() as ChatItem.Assistant
        assertThat(answer.streaming).isTrue()
        assertThat(answer.parts).containsExactly(Part.Text("Ищу. "), tool, Part.Text("Нашёл")).inOrder()
        // The next event of the turn lands in the same answer.
        assertThat(play(event("tool.start", """{"tool_id":"c2","name":"terminal"}"""), start = items)).hasSize(2)
    }

    @Test
    fun `a running turn with no stored answer gets one that is open`() {
        val stored = listOf<ChatItem>(ChatItem.User("u1", "привет", emptyList()))
        val waiting = resumeTurn(stored, "", keys)
        assertThat((waiting.last() as ChatItem.Assistant).parts).isEmpty()
        assertThat((waiting.last() as ChatItem.Assistant).streaming).isTrue()
        val streamed = resumeTurn(stored, "Здравствуйте", keys)
        assertThat((streamed.last() as ChatItem.Assistant).parts).containsExactly(Part.Text("Здравствуйте"))
    }

    @Test
    fun `text that does not continue the stored answer is not shown twice`() {
        val stored = listOf(ChatItem.User("u1", "найди", emptyList()), ChatItem.Assistant("a2", listOf(Part.Text("Ищу")), streaming = false))
        assertThat((resumeTurn(stored, "Другое", keys).last() as ChatItem.Assistant).parts).containsExactly(Part.Text("Ищу"))
    }

    @Test
    fun `a running turn shows that it waits until an answer is open`() {
        val user = ChatItem.User("u1", "привет", emptyList())
        val done = ChatItem.Assistant("a1", listOf(Part.Text("ответ")), streaming = false)
        val open = ChatItem.Assistant("a2", emptyList(), streaming = true)
        assertThat(ChatState(items = listOf(user), running = true).awaitingAnswer).isTrue()
        assertThat(ChatState(items = listOf(user, done), running = true).awaitingAnswer).isTrue()
        assertThat(ChatState(items = listOf(user, open), running = true).awaitingAnswer).isFalse()
        assertThat(ChatState(items = listOf(user), running = false).awaitingAnswer).isFalse()
    }

    @Test
    fun `stopping closes running tools`() {
        val items = closeOpen(play(event("tool.start", """{"tool_id":"c1","name":"web_search"}""")), interrupted = true)
        val answer = items.single() as ChatItem.Assistant
        assertThat(answer.interrupted).isTrue()
        assertThat((answer.parts.single() as Part.Tool).run.done).isTrue()
    }

    @Test
    fun `attachment lines leave the user text`() {
        val (text, attachments) = splitUserText("Что на фото?\n@image:/opt/data/images/a.png\n@file:/work/report.pdf")
        assertThat(text).isEqualTo("Что на фото?")
        assertThat(attachments).containsExactly(
            Attachment("/opt/data/images/a.png", "a.png", image = true),
            Attachment("/work/report.pdf", "report.pdf", image = false),
        ).inOrder()
    }

    @Test
    fun `the context the server attached stays out of the bubble`() {
        val stored = "Посмотри файлы\n@file:attachments/report.pdf\n@file:attachments/notes.md\n\n--- Attached Context ---\n\n" +
            "📎 @file:attachments/report.pdf (application/pdf, 864 B) — binary file\n\n📄 @file:attachments/notes.md (27 tokens)\n```markdown\n# Notes\n```\n[screenshot]"
        val (text, attachments) = splitUserText(stored)
        assertThat(text).isEqualTo("Посмотри файлы")
        assertThat(attachments.map { it.path }).containsExactly("attachments/report.pdf", "attachments/notes.md").inOrder()
    }

    @Test
    fun `pictures named after the attached context are kept`() {
        val stored = "Что на фото?\n@file:attachments/notes.md\n\n--- Attached Context ---\n\n📄 @file:attachments/notes.md\n```markdown\n# x\n```\n@image:/opt/data/images/upload_1.png\n[screenshot]"
        val (text, attachments) = splitUserText(stored)
        assertThat(text).isEqualTo("Что на фото?")
        assertThat(attachments).containsExactly(
            Attachment("attachments/notes.md", "notes.md", image = false),
            Attachment("/opt/data/images/upload_1.png", "upload_1.png", image = true),
        ).inOrder()
    }

    @Test
    fun `media directives are found and removed from the text`() {
        val text = "Готово.\nMEDIA:/opt/data/out/chart.png\nСм. также `MEDIA:/tmp/report.pdf`."
        assertThat(mediaPaths(text)).containsExactly("/opt/data/out/chart.png", "/tmp/report.pdf").inOrder()
        assertThat(stripMedia(text)).doesNotContain("chart.png")
    }

    @Test
    fun `a word after MEDIA is not a file`() {
        assertThat(mediaPaths("MEDIA: tags are how files are sent")).isEmpty()
    }

    @Test
    fun `stored tool results join their calls`() {
        val messages = listOf(
            StoredMessage(1, "user", "сколько места?", null, emptyList(), null, null, 1.0, false, emptyList()),
            StoredMessage(2, "assistant", "", null, listOf(ToolCallInfo("c1", "terminal", "{\"command\":\"df -h\"}")), null, null, 2.0, false, emptyList()),
            StoredMessage(3, "tool", """{"output": "/dev/sda1 34%", "exit_code": 0}""", null, emptyList(), "terminal", "c1", 3.0, false, emptyList()),
            StoredMessage(4, "assistant", "Занято 34%", null, emptyList(), null, null, 4.0, false, emptyList()),
        )
        val items = transcriptOf(messages)
        assertThat(items).hasSize(2)
        val answer = items[1] as ChatItem.Assistant
        assertThat((answer.parts[0] as Part.Tool).run.output).isEqualTo(ToolOutput("/dev/sda1 34%", markdown = false))
        assertThat(answer.text).isEqualTo("Занято 34%")
    }

    @Test
    fun `a live tool result shows what the tool returned`() {
        val items = play(
            event("tool.start", """{"tool_id":"c1","name":"delegate_task"}"""),
            event("tool.complete", """{"tool_id":"c1","result":{"results":[{"status":"completed","summary":"## Вывод\n\n**Готово**"}]}}"""),
        )
        val run = ((items.single() as ChatItem.Assistant).parts.single() as Part.Tool).run
        assertThat(run.output).isEqualTo(ToolOutput("## Вывод\n\n**Готово**", markdown = true))
    }

    @Test
    fun `hidden rows stay out of the transcript`() {
        val items = transcriptOf(listOf(StoredMessage(1, "user", "[System: x]", null, emptyList(), null, null, 1.0, true, emptyList())))
        assertThat(items).isEmpty()
    }
}
