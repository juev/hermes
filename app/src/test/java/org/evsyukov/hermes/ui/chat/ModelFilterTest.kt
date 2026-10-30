package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.remote.ModelChoices
import org.evsyukov.hermes.data.remote.ModelProvider
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
class ModelFilterTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val choices = ModelChoices(
        providers = listOf(
            ModelProvider("nous", "Nous", listOf("claude-opus", "claude-haiku", "hermes-4"), current = false, warning = null),
            ModelProvider("openai", "OpenAI", listOf("gpt-5", "gpt-5-mini"), current = true, warning = null),
            ModelProvider("deepseek", "DeepSeek", listOf("deepseek-v4"), current = false, warning = null),
        ),
        model = "gpt-5",
        provider = "openai",
    )

    @Test
    fun `only the models that match stay, with their own provider`() {
        val shown = matchingProviders(choices, "claude")

        assertThat(shown.map { it.slug }).containsExactly("nous")
        assertThat(shown.single().models).containsExactly("claude-opus", "claude-haiku").inOrder()
    }

    @Test
    fun `a match in several providers keeps each provider with its matching models`() {
        val shown = matchingProviders(choices, "5")

        assertThat(shown.map { it.slug }).containsExactly("openai")
        assertThat(shown.single().models).containsExactly("gpt-5", "gpt-5-mini")
    }

    @Test
    fun `a provider none of whose models match is left out`() {
        val shown = matchingProviders(choices, "deepseek")

        assertThat(shown.map { it.slug }).containsExactly("deepseek")
        assertThat(matchingProviders(choices, "gpt").map { it.slug }).containsExactly("openai")
    }

    @Test
    fun `the match ignores case and surrounding spaces`() {
        assertThat(matchingProviders(choices, "  CLAUDE ").map { it.slug }).containsExactly("nous")
    }

    @Test
    fun `an empty filter gives the whole list back in the server's order`() {
        assertThat(matchingProviders(choices, "").map { it.slug }).containsExactly("nous", "openai", "deepseek").inOrder()
        assertThat(matchingProviders(choices, "   ").map { it.slug }).containsExactly("nous", "openai", "deepseek").inOrder()
    }

    @Test
    fun `no match leaves no provider at all`() {
        assertThat(matchingProviders(choices, "zzz")).isEmpty()
    }

    private fun show(onPick: (String, String) -> Unit = { _, _ -> }) {
        compose.setContent { HermesTheme { ModelList(choices, current = "gpt-5", onPick = onPick) } }
    }

    @Test
    fun `typing a filter hides the other providers and clearing it brings them back`() {
        show()
        compose.onNodeWithText("claude-opus").assertIsDisplayed()
        compose.onNodeWithText("Nous").assertIsDisplayed()
        compose.onNodeWithText("DeepSeek").assertIsDisplayed()

        compose.onNodeWithTag("model-filter").performTextInput("gpt")
        compose.onNodeWithText("claude-opus").assertDoesNotExist()
        compose.onNodeWithText("Nous").assertDoesNotExist()
        compose.onNodeWithText("DeepSeek").assertDoesNotExist()
        compose.onNodeWithText("OpenAI").assertIsDisplayed()
        compose.onNodeWithText("gpt-5-mini").assertIsDisplayed()

        compose.onNodeWithTag("model-filter").performTextClearance()
        compose.onNodeWithText("claude-opus").assertIsDisplayed()
        compose.onNodeWithText("Nous").assertIsDisplayed()
        compose.onNodeWithText("DeepSeek").assertIsDisplayed()
    }

    @Test
    fun `a filter that matches nothing says so and keeps the field`() {
        show()

        compose.onNodeWithTag("model-filter").performTextInput("zzz")

        compose.onNodeWithText(app.getString(R.string.chat_models_no_match, "zzz")).assertIsDisplayed()
        compose.onNodeWithTag("model-filter").assertIsDisplayed()
    }

    @Test
    fun `a model shown by its filter can still be picked`() {
        var picked: Pair<String, String>? = null
        show { provider, model -> picked = provider to model }

        compose.onNodeWithTag("model-filter").performTextInput("mini")
        compose.onNodeWithText("gpt-5-mini").performClick()

        assertThat(picked).isEqualTo("openai" to "gpt-5-mini")
    }
}
