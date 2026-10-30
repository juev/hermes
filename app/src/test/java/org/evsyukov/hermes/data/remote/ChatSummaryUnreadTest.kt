package org.evsyukov.hermes.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ChatSummaryUnreadTest {
    private fun summary(json: String) = ChatApi.summary(HermesJson.parseToJsonElement(json).jsonObject)

    @Test
    fun `a session row carries the server's unread flag`() {
        assertThat(summary("""{"id":"s1","last_active":10,"unread":true}""")?.unread).isTrue()
        assertThat(summary("""{"id":"s1","last_active":10,"unread":false}""")?.unread).isFalse()
        assertThat(summary("""{"id":"s1","last_active":10}""")?.unread).isFalse()
        assertThat(summary("""{"id":"s1","last_active":10,"unread":1}""")?.unread).isTrue()
    }
}
