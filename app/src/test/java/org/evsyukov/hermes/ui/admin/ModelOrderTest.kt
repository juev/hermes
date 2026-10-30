package org.evsyukov.hermes.ui.admin

import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.data.remote.ModelChoices
import org.evsyukov.hermes.data.remote.ModelProvider
import org.junit.Test

class ModelOrderTest {
    private fun provider(slug: String, name: String, models: List<String>, current: Boolean = false) =
        ModelProvider(slug, name, models, current, warning = null)

    @Test
    fun `the provider in use comes first, the rest by name`() {
        val choices = ModelChoices(
            providers = listOf(
                provider("zai", "Z.AI", listOf("glm-5")),
                provider("openai", "OpenAI", listOf("gpt-5")),
                provider("nous", "Nous", listOf("hermes-4"), current = true),
                provider("anthropic", "Anthropic", listOf("claude-opus")),
            ),
            model = "hermes-4",
            provider = "nous",
        )

        val ordered = choices.stableOrder()

        assertThat(ordered.providers.map { it.slug }).containsExactly("nous", "anthropic", "openai", "zai").inOrder()
        assertThat(ordered.model).isEqualTo("hermes-4")
        assertThat(ordered.provider).isEqualTo("nous")
    }

    @Test
    fun `the order is the same for the same list in another order`() {
        val providers = listOf(
            provider("openai", "OpenAI", listOf("gpt-5-mini", "gpt-5", "gpt-5-nano")),
            provider("nous", "Nous", listOf("hermes-4", "claude-opus"), current = true),
        )
        val first = ModelChoices(providers, "hermes-4", "nous").stableOrder()
        val second = ModelChoices(providers.reversed().map { it.copy(models = it.models.reversed()) }, "hermes-4", "nous").stableOrder()

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `the models of a provider are sorted by name without regard to case`() {
        val choices = ModelChoices(
            providers = listOf(provider("nous", "Nous", listOf("z-model", "Alpha", "beta", "Gamma"), current = true)),
            model = "Alpha",
            provider = "nous",
        )

        assertThat(choices.stableOrder().providers.single().models).containsExactly("Alpha", "beta", "Gamma", "z-model").inOrder()
    }

    @Test
    fun `a provider with the same name as another keeps a stable order by slug`() {
        val choices = ModelChoices(
            providers = listOf(
                provider("b", "Same", listOf("m")),
                provider("a", "Same", listOf("m")),
            ),
            model = null,
            provider = null,
        )

        assertThat(choices.stableOrder().providers.map { it.slug }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `an empty list stays empty`() {
        val choices = ModelChoices(emptyList(), null, null)

        assertThat(choices.stableOrder()).isEqualTo(choices)
    }
}
