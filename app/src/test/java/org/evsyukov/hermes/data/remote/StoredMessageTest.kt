package org.evsyukov.hermes.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
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
