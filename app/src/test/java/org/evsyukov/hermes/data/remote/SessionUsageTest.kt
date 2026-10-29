package org.evsyukov.hermes.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class SessionUsageTest {
    private fun usage(json: String) = ChatApi.usage(HermesJson.parseToJsonElement(json).jsonObject)

    @Test
    fun `input counts the tokens read from the cache too`() {
        val usage = usage("""{"input_tokens":1200,"cache_read_tokens":30000,"cache_write_tokens":800,"output_tokens":450,"estimated_cost_usd":0.12,"cost_status":"estimated"}""")
        assertThat(usage).isEqualTo(SessionUsage(input = 32_000, output = 450, costUsd = 0.12, included = false))
    }

    @Test
    fun `the billed amount wins over the estimate`() {
        val usage = usage("""{"input_tokens":10,"output_tokens":5,"estimated_cost_usd":0.5,"actual_cost_usd":0.42,"cost_status":"actual"}""")
        assertThat(usage?.costUsd).isEqualTo(0.42)
    }

    @Test
    fun `a model without a price shows tokens alone`() {
        val usage = usage("""{"input_tokens":10,"output_tokens":5,"estimated_cost_usd":0,"cost_status":"unknown"}""")
        assertThat(usage).isEqualTo(SessionUsage(input = 10, output = 5, costUsd = null, included = false))
    }

    @Test
    fun `a subscription says the tokens are included`() {
        val usage = usage("""{"input_tokens":10,"output_tokens":5,"estimated_cost_usd":0,"cost_status":"included"}""")
        assertThat(usage).isEqualTo(SessionUsage(input = 10, output = 5, costUsd = null, included = true))
    }

    @Test
    fun `a chat that spent nothing has no usage`() {
        assertThat(usage("""{"id":"s1","input_tokens":0,"output_tokens":0}""")).isNull()
        assertThat(usage("""{"id":"s1"}""")).isNull()
    }

    @Test
    fun `the chat summary carries its usage`() {
        val chat = ChatApi.summary(HermesJson.parseToJsonElement("""{"id":"s1","input_tokens":7,"output_tokens":3}""").jsonObject)
        assertThat(chat?.usage).isEqualTo(SessionUsage(input = 7, output = 3, costUsd = null, included = false))
    }
}
