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

    @Test
    fun `a session row carries the server's read watermark`() {
        assertThat(summary("""{"id":"s1","last_active":10,"last_read_at":8.5}""")?.lastReadAt).isEqualTo(8.5)
        assertThat(summary("""{"id":"s1","last_active":10,"last_read_at":0}""")?.lastReadAt).isEqualTo(0.0)
        assertThat(summary("""{"id":"s1","last_active":10,"last_read_at":null}""")?.lastReadAt).isNull()
        assertThat(summary("""{"id":"s1","last_active":10}""")?.lastReadAt).isNull()
    }
}
