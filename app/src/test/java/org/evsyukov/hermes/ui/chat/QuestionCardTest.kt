package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.chat.ChatController
import org.evsyukov.hermes.chat.ChatItem
import org.evsyukov.hermes.chat.LiveGateway
import org.evsyukov.hermes.chat.LiveServer
import org.evsyukov.hermes.chat.QuestionMemory
import org.evsyukov.hermes.chat.QuestionState
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.settings.Settings
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A question of the agent is a card inside the transcript of the real chat screen, on a narrow phone.
 * It goes only by its own buttons: Back, swipes, taps elsewhere and a screen over the chat send
 * nothing, and what is written on it stays. A reader of older messages is told it waits, not moved.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp", application = Application::class)
class QuestionCardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = LiveServer.database(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = LiveGateway()
    private val server = LiveServer(gateway)
    private var onScreen by mutableStateOf(true)

    /** The questions of the connection, which the app hands to every chat it opens on it. */
    private val memory = QuestionMemory()

    /** The draft of each chat as the app keeps it: one per controller, so a chat opened again gets a new one. */
    private val drafts = HashMap<ChatController, ChatDraft>()
    private var current by mutableStateOf<ChatController?>(null)

    private fun controller(id: String) =
        ChatController(server.session(context, scope, db), gateway, scope, AppStrings(context.resources), id, visible = false, questionMemory = memory)

    /** What the app does to open another chat: the one on the screen is closed and the other comes in a new controller. */
    private fun switchTo(id: String): ChatController {
        val next = controller(id)
        compose.runOnIdle {
            current?.close()
            current = next
        }
        compose.waitForIdle()
        return next
    }

    /** Back presses that reached the app's own navigation, under the chat screen. */
    private var backs = 0

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    /** The chat screen of a turn that runs the clarify tool, after [history] finished turns. */
    private fun open(history: Int = 0): ChatController = runBlocking {
        gateway.connect()
        repeat(history) {
            gateway.start("message $it")
            gateway.delta("Answer $it")
            gateway.complete()
        }
        gateway.start("hi")
        gateway.delta("Let me ask.")
        val chat = controller("s1")
        current = chat
        compose.setContent {
            HermesTheme {
                BackHandler { backs++ }
                val shown = current
                if (onScreen && shown != null) {
                    ChatScreen(
                        controller = shown, settings = Settings(), serverName = "Home", connected = true,
                        onMenu = {}, onNewChat = {}, openFile = {}, loadFile = { null }, loadModels = { error("not opened") },
                        openLocal = {}, onShown = { c, visible -> c.setVisible(visible) }, draft = drafts.getOrPut(shown) { ChatDraft() },
                    )
                } else {
                    Text("Settings")
                }
            }
        }
        withTimeout(5_000) { chat.state.first { (it.items.lastOrNull() as? ChatItem.Assistant)?.text == "Let me ask." && !it.loading } }
        gateway.event("tool.start", buildJsonObject { put("tool_id", "t1"); put("name", "clarify") })
        gateway.deliver()
        chat
    }

    private fun clarify(vararg questions: Pair<String, List<String>>): JsonObject = buildJsonObject {
        put(
            "questions",
            JsonArray(
                questions.mapIndexed { i, (text, choices) ->
                    buildJsonObject {
                        put("qid", "q$i")
                        put("question", text)
                        if (choices.isNotEmpty()) put("choices", JsonArray(choices.map(::JsonPrimitive)))
                    }
                },
            ),
        )
    }

    private fun ask(id: String, params: JsonObject) {
        runBlocking { gateway.ask(id, "clarify", params) }
        waitFor(hasTestTag("question:$id"))
    }

    private fun waitFor(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        compose.waitUntil(5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun string(id: Int) = context.getString(id)

    private fun json(text: String) = HermesJson.parseToJsonElement(text)

    @Test
    fun `a question stands in the transcript, not in a sheet`() {
        open()

        ask("r1", clarify("Which branch?" to listOf("main", "dev")))

        compose.onNode(hasTestTag("question:r1") and hasAnyAncestor(hasTestTag("transcript"))).assertIsDisplayed()
        compose.onNode(isHeading() and hasText(string(org.evsyukov.hermes.R.string.chat_clarify_title))).assertIsDisplayed()
        assertThat(compose.onAllNodes(isDialog()).fetchSemanticsNodes()).isEmpty()
        // Brought into view for the reader at the end of the chat, so nothing has to announce it.
        compose.onNodeWithTag("question-waiting").assertDoesNotExist()
    }

    @Test
    fun `back, swipes, taps elsewhere and a screen over the chat answer nothing and keep the words`() {
        val chat = open()
        ask("r1", clarify("Which branch?" to listOf("main", "dev")))
        compose.onNodeWithTag("answer:q0").performTextInput("feature/x")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("question:r1").performTouchInput { swipeLeft(); swipeRight() }
        compose.onNodeWithTag("transcript").performTouchInput { swipeDown(); swipeUp() }
        compose.onNodeWithText("Let me ask.", substring = true).performClick()
        compose.runOnIdle { onScreen = false }
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.runOnIdle { onScreen = true }
        waitFor(hasTestTag("question:r1"))

        assertThat(backs).isEqualTo(1)
        compose.onNodeWithTag("answer:q0").assertTextContains("feature/x")
        compose.waitForIdle()
        assertThat(gateway.responses).isEmpty()
        assertThat(chat.state.value.questions.single().state).isEqualTo(QuestionState.PENDING)
    }

    @Test
    fun `a question asked under another screen is on the card when the chat comes back`() {
        open()
        compose.runOnIdle { onScreen = false }
        compose.waitForIdle()

        runBlocking { gateway.ask("r1", "clarify", clarify("Which branch?" to emptyList())) }
        compose.runOnIdle { onScreen = true }

        waitFor(hasTestTag("question:r1"))
        compose.onNodeWithText("Which branch?").assertIsDisplayed()
    }

    @Test
    fun `a choice, own words and a question left blank go in one answer for their request`() {
        val chat = open()
        ask("r1", clarify("Which branch?" to listOf("main", "dev"), "Anything else?" to emptyList(), "Skip me?" to emptyList()))
        compose.onNodeWithTag("question-answer").performScrollTo().assertIsNotEnabled()

        compose.onNode(hasText("dev") and isSelectable()).performScrollTo().performClick()
        compose.onNode(hasText("dev") and isSelectable()).assertIsSelected()
        compose.onNodeWithTag("answer:q1").performScrollTo().performTextInput("Run the tests")
        compose.onNodeWithTag("question-answer").performScrollTo().performClick()

        compose.waitUntil(5_000) { chat.state.value.questions.single().state == QuestionState.SUBMITTED }
        assertThat(gateway.responses).containsExactly(
            LiveGateway.Response("r1", json("""{"answers":{"q0":"dev","q1":"Run the tests","q2":null},"outcome":"submitted"}"""), null),
        )
        waitFor(hasText(string(org.evsyukov.hermes.R.string.chat_question_answered)))
        compose.onNodeWithTag("question-answer").assertDoesNotExist()
        compose.onNodeWithText("Run the tests").assertExists()
        compose.onNodeWithText(string(org.evsyukov.hermes.R.string.chat_question_no_answer)).assertExists()
        assertThat(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).hasSize(1) // the composer alone
    }

    @Test
    fun `cancelling takes the labelled button and the card says so`() {
        val chat = open()
        ask("r1", clarify("Which branch?" to emptyList()))

        compose.onNodeWithText(string(org.evsyukov.hermes.R.string.chat_question_cancel)).performScrollTo().performClick()

        compose.waitUntil(5_000) { chat.state.value.questions.single().state == QuestionState.CANCELLED }
        assertThat(gateway.responses).containsExactly(LiveGateway.Response("r1", json("""{"outcome":"cancelled"}"""), null))
        waitFor(hasText(string(org.evsyukov.hermes.R.string.chat_question_cancelled)))
        compose.onNodeWithText("Which branch?").assertExists()
        compose.onNodeWithTag("question-answer").assertDoesNotExist()
    }

    @Test
    fun `a question the server lets go shows how it ended and takes no answer`() {
        open()
        ask("r1", clarify("Which branch?" to emptyList()))

        runBlocking { gateway.withdraw("r1", "timeout") }

        waitFor(hasText(string(org.evsyukov.hermes.R.string.chat_question_expired)))
        compose.onNodeWithTag("question-answer").assertDoesNotExist()
        compose.onNodeWithTag("answer:q0").assertDoesNotExist()
    }

    @Test
    fun `a reader of older messages is told a question waits, and is not moved until they ask`() {
        open(history = 12)
        compose.onNodeWithTag("transcript").performScrollToNode(hasText("Answer 0", substring = true))
        compose.onNodeWithText("Answer 0", substring = true).assertIsDisplayed()

        runBlocking { gateway.ask("r1", "clarify", clarify("Which branch?" to emptyList())) }
        waitFor(hasTestTag("question-waiting"))

        compose.onNodeWithText("Answer 0", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("question-waiting").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("question-waiting").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("question-waiting")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("question:r1").assertIsDisplayed()
        assertThat(gateway.responses).isEmpty()
    }

    @Test
    fun `a long question with long choices fits a narrow screen and keeps every control reachable`() {
        open()
        val long = "Before I change the deployment, which of the environments listed in the runbook should I treat as " +
            "the source of truth for the database credentials, given that staging was rebuilt last week?"
        val choices = listOf("production (eu-west-1, the primary cluster)", "staging", "the local docker compose file", "ask the on-call engineer", "none")
        ask("r1", clarify(long to choices, "And the branch?" to emptyList()))

        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.right
        choices.forEach { choice ->
            val bounds = compose.onNodeWithText(choice).performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertThat(bounds.left).isAtLeast(0f)
            assertThat(bounds.right).isAtMost(width)
        }
        compose.onNodeWithText(long).performScrollTo().assertIsDisplayed()
        // The own answer is named for a screen reader by its label, not by a placeholder that goes once text is typed.
        compose.onNodeWithTag("answer:q1").performScrollTo().performClick().performTextInput("main")
        compose.onNodeWithTag("answer:q1").assertIsDisplayed()
        compose.onNode(hasTestTag("answer:q1") and hasText(string(org.evsyukov.hermes.R.string.chat_own_answer))).assertExists()
        compose.onNodeWithTag("question-answer").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText(string(org.evsyukov.hermes.R.string.chat_question_cancel)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("question:r1").assertExists()
        compose.onNodeWithTag("question-waiting").assertDoesNotExist()
    }

    @Test
    fun `switching to another chat and back keeps the written words, the choice and the answered card`() {
        val chat = open()
        ask("r1", clarify("Which file?" to emptyList()))
        compose.onNodeWithTag("answer:q0").performTextInput("notes.md")
        compose.onNodeWithTag("question-answer").performScrollTo().performClick()
        compose.waitUntil(5_000) { chat.state.value.questions.single().state == QuestionState.SUBMITTED }
        ask("r2", clarify("Which branch?" to listOf("main", "dev"), "Why?" to emptyList()))
        compose.onNode(hasText("dev") and isSelectable()).performScrollTo().performClick()
        compose.onNodeWithTag("answer:q1").performScrollTo().performTextInput("because")

        switchTo("s2")
        compose.onNodeWithTag("question:r1").assertDoesNotExist()
        compose.onNodeWithTag("question:r2").assertDoesNotExist()
        switchTo("s1")

        waitFor(hasTestTag("question:r2"))
        compose.onNodeWithTag("answer:q1").performScrollTo().assertTextContains("because")
        compose.onNode(hasText("dev") and isSelectable()).performScrollTo().assertIsSelected()
        compose.onNodeWithTag("transcript").performScrollToNode(hasText("notes.md"))
        compose.onNodeWithText("notes.md").assertIsDisplayed()
        compose.onAllNodes(hasText(string(org.evsyukov.hermes.R.string.chat_question_answered))).assertCountEquals(1)
        assertThat(gateway.responses.map { it.id }).containsExactly("r1")
    }

    @Test
    fun `a card answered near the end has no arrow over its outcome`() {
        val chat = open()
        ask("r1", clarify("Which of the environments listed in the runbook should I treat as the source of truth?" to listOf("production", "staging"), "Why?" to emptyList()))
        compose.onNode(hasText("staging") and isSelectable()).performScrollTo().performClick()
        compose.onNodeWithTag("question-answer").performScrollTo().performClick()
        compose.waitUntil(5_000) { chat.state.value.questions.single().state == QuestionState.SUBMITTED }
        compose.waitForIdle()

        compose.onNodeWithText(string(org.evsyukov.hermes.R.string.chat_question_answered)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(org.evsyukov.hermes.R.string.chat_scroll_down)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h360dp")
    fun `on a screen a keyboard leaves short, the buttons of a long card are reached by scrolling`() {
        val chat = open()
        val long = "Before I change the deployment, which of the environments listed in the runbook should I treat as " +
            "the source of truth for the database credentials, given that staging was rebuilt last week?"
        ask("r1", clarify(long to listOf("production", "staging", "none"), "Should I restart the services afterwards, and in which order?" to emptyList()))

        compose.onNodeWithTag("answer:q1").performScrollTo().performClick().performTextInput("api first")
        compose.onNodeWithTag("answer:q1").assertIsDisplayed()
        compose.onNodeWithText(string(org.evsyukov.hermes.R.string.chat_question_cancel)).performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("question-answer").performScrollTo().assertIsDisplayed().performClick()

        compose.waitUntil(5_000) { chat.state.value.questions.single().state == QuestionState.SUBMITTED }
        assertThat(gateway.responses.single().result).isEqualTo(json("""{"answers":{"q0":null,"q1":"api first"},"outcome":"submitted"}"""))
    }

    @Test
    fun `a card kept from an earlier visit shows its words at once and answers only once its chat has joined`() {
        open()
        ask("r1", clarify("Which file?" to emptyList()))
        compose.onNodeWithTag("answer:q0").performTextInput("notes.md")
        switchTo("s2")
        server.historyDelayMillis = 2_000
        switchTo("s1")

        waitFor(hasTestTag("question:r1"))
        compose.onNodeWithTag("answer:q0").assertTextContains("notes.md")
        compose.onNodeWithTag("question-answer").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(string(org.evsyukov.hermes.R.string.chat_question_cancel)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(string(org.evsyukov.hermes.R.string.chat_question_checking)).assertExists()
        compose.onNodeWithTag("question-answer").performClick()
        compose.waitForIdle()
        assertThat(gateway.responses).isEmpty()

        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("question-answer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("question-answer").performScrollTo().performClick()
        compose.waitUntil(5_000) { gateway.responses.isNotEmpty() }
        assertThat(gateway.responses.map { it.id }).containsExactly("r1")
    }
}
