package org.evsyukov.hermes.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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
    fun `a failed turn of the agent is still shown`() {
        val row = message("""{"id":1,"role":"assistant","content":"Gateway shutting down","display_kind":"failed_turn"}""")
        assertThat(row.hidden).isFalse()
    }
}
