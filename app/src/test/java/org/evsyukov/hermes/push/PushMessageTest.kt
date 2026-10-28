package org.evsyukov.hermes.push

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PushMessageTest {
    @Test
    fun `a turn of hermes-push is read`() {
        val message = PushMessage.parse(
            """{"type":"turn","session_id":"20261028_150734_d03145","platform":"telegram","job":null,"preview":"Готово"}""".toByteArray(),
        )
        assertThat(message).isEqualTo(PushMessage("turn", "20261028_150734_d03145", "telegram", null, "Готово"))
    }

    @Test
    fun `anything else is not a message`() {
        assertThat(PushMessage.parse("not json".toByteArray())).isNull()
        assertThat(PushMessage.parse("""{"kind":"entries"}""".toByteArray())).isNull()
    }
}
