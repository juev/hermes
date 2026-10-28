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
            StoredMessage(3, "tool", "/dev/sda1 34%", null, emptyList(), "terminal", "c1", 3.0, false, emptyList()),
            StoredMessage(4, "assistant", "Занято 34%", null, emptyList(), null, null, 4.0, false, emptyList()),
        )
        val items = transcriptOf(messages)
        assertThat(items).hasSize(2)
        val answer = items[1] as ChatItem.Assistant
        assertThat((answer.parts[0] as Part.Tool).run.summary).isEqualTo("/dev/sda1 34%")
        assertThat(answer.text).isEqualTo("Занято 34%")
    }

    @Test
    fun `hidden rows stay out of the transcript`() {
        val items = transcriptOf(listOf(StoredMessage(1, "user", "[System: x]", null, emptyList(), null, null, 1.0, true, emptyList())))
        assertThat(items).isEmpty()
    }
}
