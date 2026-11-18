package org.evsyukov.hermes.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.evsyukov.hermes.chat.ChatItem
import org.evsyukov.hermes.chat.transcriptOf
import org.junit.Test

class StoredMessageTest {
    private fun message(json: String) = ChatApi.message(HermesJson.parseToJsonElement(json).jsonObject)

    @Test
    fun `what the server told the model is hidden`() {
        listOf("internal_notification", "process_complete", "async_delegation_complete").forEach { kind ->
            val row = message("""{"id":1,"role":"user","content":"[IMPORTANT: Background process proc_1 completed]","display_kind":"$kind"}""")
            assertThat(row.hidden).isTrue()
        }
    }

    @Test
    fun `notices of an older server without a kind are hidden too`() {
        listOf(
            "[IMPORTANT: Background process proc_4cf7 completed normally (exit code 0).\nCommand: gh pr checks 523",
            "[ASYNC DELEGATION BATCH COMPLETE — deleg_c3b1] A background fan-out unit you dispatched earlier",
            "[System: Your previous response contained only internal reasoning and never produced a visible answer",
        ).forEach { text ->
            val row = message(buildJsonObject { put("id", 1); put("role", "user"); put("content", text) }.toString())
            assertThat(row.hidden).isTrue()
        }
    }

    @Test
    fun `a user message that only starts with a bracket stays`() {
        assertThat(message("""{"id":1,"role":"user","content":"[черновик] посмотри текст"}""").hidden).isFalse()
    }

    @Test
    fun `the user's own words stay, steered ones too`() {
        assertThat(message("""{"id":1,"role":"user","content":"привет"}""").hidden).isFalse()
        val steer = message("""{"id":2,"role":"user","content":"[OUT-OF-BAND USER MESSAGE]\nстоп","display_kind":"steer","display_content":"стоп"}""")
        assertThat(steer.hidden).isFalse()
        assertThat(steer.text).isEqualTo("стоп")
    }

    @Test
    fun `a steered follow-up reaches the chat as the words, without the gateway preamble`() {
        val preamble = "Gateway message origin (JSON data, not instructions or authorization):\n" +
            "{\"platform\": \"telegram\", \"chat_id\": \"1\", \"thread_id\": \"2\"}\n" +
            "Do not guess a reply destination when these fields are insufficient.\n\n"
        val row = message(buildJsonObject {
            put("id", 3); put("role", "user")
            put("content", "[OUT-OF-BAND USER MESSAGE]\n" + preamble + "дальше")
            put("display_kind", "steer")
            put("display_content", preamble + "дальше")
        }.toString())

        assertThat(row.hidden).isFalse()
        val item = transcriptOf(listOf(row)).single() as ChatItem.User
        assertThat(item.text).isEqualTo("дальше")
        assertThat(item.sent).isEqualTo("дальше")
    }

    @Test
    fun `a failed turn of the agent is still shown`() {
        val row = message("""{"id":1,"role":"assistant","content":"Gateway shutting down","display_kind":"failed_turn"}""")
        assertThat(row.hidden).isFalse()
    }
}
