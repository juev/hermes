package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.ChatController
import org.evsyukov.hermes.chat.LiveGateway
import org.evsyukov.hermes.chat.LiveServer
import org.evsyukov.hermes.chat.closeWithOwnedJobs
import org.evsyukov.hermes.data.remote.ModelChoices
import org.evsyukov.hermes.data.remote.ModelProvider
import org.evsyukov.hermes.data.remote.SessionUsage
import org.evsyukov.hermes.data.settings.Settings
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The header is one bar: the menu and the new-chat button flank a central column whose plain title
 * sits over its own model target. The title does nothing, the model target opens the picker alone,
 * and Rename is a labelled item of the chat menu. The totals take a plain full-width line only when
 * they do not fit the centre. A tap on one control never runs another, and at a narrow width with
 * large text the effective targets keep out of each other's way while the model and the totals stay
 * readable.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeaderActionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = LiveServer.database(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = LiveGateway()
    private val server = LiveServer(gateway)
    private val strings = AppStrings(app.resources)

    /** How many times the screen asked for the model list. */
    private var modelLoads = 0

    private val choices = ModelChoices(
        providers = listOf(ModelProvider(slug = "openai", name = "OpenAI", models = listOf("gpt-5.2"), current = true, warning = null)),
        model = "live-model",
        provider = "openai",
    )

    @After
    fun tearDown() {
        scope.closeWithOwnedJobs(db) { server.close() }
    }

    private fun open(connected: Boolean = true): ChatController = runBlocking {
        if (connected) gateway.connect()
        ChatController(server.session(app, scope, db), gateway, scope, strings, "s1").also { chat ->
            withTimeout(5_000) { chat.state.first { it.title == "Mail" && !it.loading } }
        }
    }

    @Composable
    private fun Screen(chat: ChatController, draft: ChatDraft = ChatDraft()) = ChatScreen(
        controller = chat, settings = Settings(), serverName = "Home", connected = true,
        onMenu = {}, onNewChat = {}, openFile = {}, loadFile = { null },
        loadModels = { modelLoads += 1; choices },
        openLocal = {}, onShown = { c, visible -> c.setVisible(visible) }, draft = draft,
    )

    private fun show(chat: ChatController) {
        compose.setContent { HermesTheme { Screen(chat) } }
        compose.waitForIdle()
    }

    @Test
    fun `the title is plain text and the model opens only the picker`() {
        val chat = open()
        show(chat)

        compose.onNodeWithTag("chat-title").assertHasNoClickAction().assertIsDisplayed()
        compose.onNodeWithText("Mail").assertHasNoClickAction()

        // A centre tap on the title is not an action at all: no picker, no rename, no model load.
        compose.onNodeWithTag("chat-title").performTouchInput { click() }
        compose.waitForIdle()
        assertThat(modelLoads).isEqualTo(0)
        compose.onNodeWithText("Chat name").assertDoesNotExist()
        compose.onNodeWithText(strings.get(R.string.chat_rename)).assertDoesNotExist()
        compose.onNodeWithText("OpenAI").assertDoesNotExist()

        compose.onNodeWithTag("model-picker")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performTouchInput { click() }
        compose.waitUntil(5_000) { modelLoads == 1 }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("OpenAI").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("OpenAI").assertIsDisplayed()
        compose.onNodeWithText("Chat name").assertDoesNotExist()

        // The picker itself says that the choice is this chat's and when it takes effect.
        compose.onNodeWithText(strings.get(R.string.chat_models_scope)).assertIsDisplayed()
        compose.onNodeWithTag("model-scope").assertHasNoClickAction()
        compose.onNodeWithTag("model-filter").assertIsDisplayed()
    }

    @Test
    fun `rename is a labelled chat menu item and asks only for rename`() {
        val chat = open()
        show(chat)

        // The menu holds one labelled action; opening it neither loads the models nor opens the dialog.
        compose.onNodeWithContentDescription(strings.get(R.string.chat_actions)).performTouchInput { click() }
        compose.onNodeWithText(strings.get(R.string.chat_rename)).assertIsDisplayed()
        compose.onNodeWithText(strings.get(R.string.sidebar_rename_title)).assertDoesNotExist()
        compose.onNodeWithText("OpenAI").assertDoesNotExist()
        assertThat(modelLoads).isEqualTo(0)
    }

    @Test
    fun `each header control runs only its own callback`() {
        // An AlertDialog holding a text field never reports idle under Robolectric, so the dialog
        // cannot be composed here. The header is exercised at the callbacks it hands to ChatScreen:
        // every target runs its own action and leaves the others alone.
        var menus = 0
        var newChats = 0
        var models = 0
        var renames = 0
        compose.setContent {
            HermesTheme {
                ChatHeader(
                    title = "Mail", model = "live-model", usage = null, modelPending = false,
                    canRename = true, onMenu = { menus += 1 }, onNewChat = { newChats += 1 },
                    onModels = { models += 1 }, onRename = { renames += 1 },
                )
            }
        }

        compose.onNodeWithTag("chat-menu").performTouchInput { click() }
        assertThat(menus).isEqualTo(1)
        assertThat(newChats + models + renames).isEqualTo(0)

        compose.onNodeWithTag("chat-new").performTouchInput { click() }
        assertThat(newChats).isEqualTo(1)
        assertThat(menus).isEqualTo(1)
        assertThat(models + renames).isEqualTo(0)

        compose.onNodeWithTag("model-picker").performTouchInput { click() }
        assertThat(models).isEqualTo(1)
        assertThat(menus).isEqualTo(1)
        assertThat(newChats).isEqualTo(1)
        assertThat(renames).isEqualTo(0)

        compose.onNodeWithContentDescription(strings.get(R.string.chat_actions)).performTouchInput { click() }
        compose.onNodeWithText(strings.get(R.string.chat_rename)).assertIsDisplayed()
        assertThat(renames).isEqualTo(0)

        compose.onNodeWithText(strings.get(R.string.chat_rename)).performTouchInput { click() }
        compose.waitForIdle()
        assertThat(renames).isEqualTo(1)
        assertThat(models).isEqualTo(1)
        assertThat(menus).isEqualTo(1)
        assertThat(newChats).isEqualTo(1)
    }

    @Test
    fun `a new chat has no rename action in its header`() {
        val chat = runBlocking {
            gateway.connect()
            ChatController(server.session(app, scope, db), gateway, scope, strings, null)
        }
        show(chat)

        compose.onNodeWithContentDescription(strings.get(R.string.chat_actions)).assertDoesNotExist()
        compose.onNodeWithContentDescription(strings.get(R.string.chat_chats)).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.get(R.string.common_new_chat)).assertIsDisplayed()
    }

    @Test
    fun `a long title and model at 360 dp keep every control apart and the totals readable`() {
        server.summary = """{"id":"s1","title":"Mail","model":"stored-model","last_active":100.0,"is_active":false,""" +
            """"input_tokens":1234000,"output_tokens":987000,"actual_cost_usd":12.34}"""
        val longTitle = "A conversation about the whole migration that never ends"
        val longModel = "openrouter/anthropic/claude-sonnet-4.5-thinking"
        val usage = "Chat: 1.2 M in · 987.0 K out · $12.34"
        val chat = open()
        show(chat)
        runBlocking { withTimeout(5_000) { chat.state.first { it.usage != null } } }
        runBlocking {
            gateway.event("session.title", buildJsonObject { put("session_id", "s1"); put("title", longTitle) })
            gateway.event("session.info", buildJsonObject { put("model", longModel) })
        }
        compose.waitUntil(5_000) {
            chat.state.value.title == longTitle && chat.state.value.model == longModel
        }
        compose.waitForIdle()

        // Title may ellipsize, but the controls are all there, apart even by effective touch bounds.
        compose.onNodeWithTag("chat-title").assertIsDisplayed()
        val menu = bounds("chat-menu")
        val title = bounds("chat-title")
        val picker = bounds("model-picker")
        val actions = bounds("chat-actions")
        val newChat = bounds("chat-new")
        assertTargetsApart(title, listOf("chat-menu", "model-picker", "chat-actions", "chat-new"))

        // One bar, not a second full-width strip: the buttons share their vertical range with the
        // model target that sits under the title in the centre.
        assertThat(menu.bottom).isGreaterThan(picker.top)
        assertThat(actions.bottom).isGreaterThan(picker.top)
        assertThat(newChat.bottom).isGreaterThan(picker.top)
        assertThat(title.bottom).isAtMost(picker.top)

        // The long model keeps its identity in the tree and gives way inside the target, not the screen.
        val modelBounds = boundsOf(longModel)
        compose.onNodeWithText(longModel, useUnmergedTree = true).assertIsDisplayed()
        assertThat(modelBounds.right).isAtMost(picker.right)
        assertThat(modelBounds.right).isAtMost(rootWidth())

        // The totals measured wider than the centre, so they take a plain full-width line: no touch
        // target, no second interactive row, and every number in full.
        val usageBounds = boundsOf(usage)
        compose.onNodeWithTag("header-usage").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText(usage, useUnmergedTree = true).assertIsDisplayed()
        assertThat(usageBounds.top).isAtLeast(picker.bottom)
        assertThat(usageBounds.right).isAtMost(rootWidth())
        assertNoOverflow(usage)
        assertThat(bounds("header").height).isAtMost(usageBounds.bottom + 8f)
        assertThat(headerClickTargets()).hasSize(4)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h640dp", fontScale = 2f)
    fun `in Russian with large text the controls keep their labels and place`() {
        server.summary = """{"id":"s1","title":"Mail","model":"stored-model","last_active":100.0,"is_active":false,""" +
            """"input_tokens":1234000,"output_tokens":987000,"actual_cost_usd":12.34}"""
        val localized = ApplicationProvider.getApplicationContext<Application>()
        val longTitle = "Очень длинное название разговора, которое не помещается в шапке"
        val longModel = "openrouter/anthropic/claude-sonnet-4.5-thinking"
        val chat = open()
        compose.setContent { HermesTheme { Screen(chat) } }
        compose.waitForIdle()
        runBlocking { withTimeout(5_000) { chat.state.first { it.usage != null } } }
        runBlocking {
            gateway.event("session.title", buildJsonObject { put("session_id", "s1"); put("title", longTitle) })
            gateway.event("session.info", buildJsonObject { put("model", longModel) })
        }
        compose.waitUntil(5_000) { chat.state.value.title == longTitle && chat.state.value.model == longModel }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(localized.getString(R.string.chat_chats)).assertIsDisplayed()
        compose.onNodeWithContentDescription(localized.getString(R.string.chat_actions)).assertIsDisplayed()
        compose.onNodeWithContentDescription(localized.getString(R.string.common_new_chat)).assertIsDisplayed()
        compose.onNodeWithTag("model-picker")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onNodeWithText(longModel, useUnmergedTree = true).assertIsDisplayed()

        val title = bounds("chat-title")
        val picker = bounds("model-picker")
        val menu = bounds("chat-menu")
        val actions = bounds("chat-actions")
        val newChat = bounds("chat-new")
        assertTargetsApart(title, listOf("chat-menu", "model-picker", "chat-actions", "chat-new"))
        assertThat(menu.bottom).isGreaterThan(picker.top)
        assertThat(actions.bottom).isGreaterThan(picker.top)
        assertThat(newChat.bottom).isGreaterThan(picker.top)
        assertThat(title.bottom).isAtMost(picker.top)

        compose.onNodeWithTag("header-usage").assertIsDisplayed().assertHasNoClickAction()
        assertNodeNoOverflow("header-usage")
        assertThat(bounds("header-usage").right).isAtMost(rootWidth())
        println("ru large header height: ${bounds("header").height}px")
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h640dp", fontScale = 2f)
    fun `the pending Russian timing lays out in full at large text`() {
        val localized = ApplicationProvider.getApplicationContext<Application>()
        val timing = localized.getString(R.string.chat_model_next_turn)
        compose.setContent {
            HermesTheme {
                Box(Modifier.width(360.dp)) {
                    ChatHeader(
                        title = "Очень длинное название разговора, которое не помещается в шапке",
                        model = "openrouter/anthropic/claude-sonnet-4.5-thinking",
                        usage = SessionUsage(1_234_000, 987_000, 12.34, included = false),
                        modelPending = true, canRename = true,
                        onMenu = {}, onNewChat = {}, onModels = {}, onRename = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        // The note is complete in the semantics, and the layout shows every word instead of an
        // ellipsis: the model name may give way, the timing may not.
        compose.onNodeWithText(timing, useUnmergedTree = true).assertIsDisplayed()
        assertNoOverflow(timing)
        compose.onNodeWithTag("header-usage").assertIsDisplayed().assertHasNoClickAction()
        assertNodeNoOverflow("header-usage")
    }

    @Test
    fun `large text makes the header taller instead of clipping it`() {
        compose.setContent {
            HermesTheme {
                val base = LocalDensity.current
                Column {
                    CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) {
                        Box(Modifier.testTag("header-default")) {
                            ChatHeader(
                                title = "Mail", model = "live-model", usage = null, modelPending = false,
                                canRename = false, onMenu = {}, onNewChat = {}, onModels = {}, onRename = {},
                            )
                        }
                    }
                    CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                        Box(Modifier.testTag("header-large")) {
                            ChatHeader(
                                title = "Mail", model = "live-model", usage = null, modelPending = false,
                                canRename = false, onMenu = {}, onNewChat = {}, onModels = {}, onRename = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        val normal = bounds("header-default").height
        val large = bounds("header-large").height
        println("header height default=${normal}px large=${large}px")
        assertThat(large).isGreaterThan(normal)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h640dp")
    fun `the Russian picker explains scope and timing without hiding search`() {
        val localized = ApplicationProvider.getApplicationContext<Application>()
        val chat = open()
        show(chat)

        compose.onNodeWithTag("model-picker").performTouchInput { click() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(localized.getString(R.string.chat_models_scope)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(localized.getString(R.string.chat_models_scope)).assertIsDisplayed()
        compose.onNodeWithTag("model-scope").assertHasNoClickAction()
        compose.onNodeWithTag("model-filter").assertIsDisplayed()
    }

    @Test
    fun `English long header controls accept centre taps`() = narrowPointerChecks()

    @Test
    @Config(fontScale = 2f)
    fun `English large long header controls accept centre taps`() = narrowPointerChecks()

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h640dp")
    fun `Russian long header controls accept centre taps`() = narrowPointerChecks()

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h640dp", fontScale = 2f)
    fun `Russian large long header controls accept centre taps`() = narrowPointerChecks()

    private fun narrowPointerChecks() {
        var menus = 0
        var newChats = 0
        var models = 0
        var renames = 0
        val localized = ApplicationProvider.getApplicationContext<Application>()
        val title = if (localized.resources.configuration.locales[0].language == "ru")
            "Очень длинное название разговора, которое не помещается в шапке"
        else "A conversation about the whole migration that never ends"
        compose.setContent {
            HermesTheme {
                Box(Modifier.width(360.dp)) {
                    ChatHeader(
                        title = title, model = "openrouter/anthropic/claude-sonnet-4.5-thinking",
                        usage = SessionUsage(1_234_000, 987_000, 12.34, included = false),
                        modelPending = true, canRename = true,
                        onMenu = { menus += 1 }, onNewChat = { newChats += 1 },
                        onModels = { models += 1 }, onRename = { renames += 1 },
                    )
                }
            }
        }
        compose.waitForIdle()
        assertTargetsApart(bounds("chat-title"), listOf("chat-menu", "model-picker", "chat-actions", "chat-new"))
        assertNoOverflow(localized.getString(R.string.chat_model_next_turn))
        compose.onNodeWithTag("header-usage").assertIsDisplayed().assertHasNoClickAction()
        assertNodeNoOverflow("header-usage")
        compose.onNodeWithTag("chat-menu").performTouchInput { click() }
        assertThat(listOf(menus, newChats, models, renames)).containsExactly(1, 0, 0, 0).inOrder()
        compose.onNodeWithTag("chat-new").performTouchInput { click() }
        assertThat(listOf(menus, newChats, models, renames)).containsExactly(1, 1, 0, 0).inOrder()
        compose.onNodeWithTag("model-picker").performTouchInput { click() }
        assertThat(listOf(menus, newChats, models, renames)).containsExactly(1, 1, 1, 0).inOrder()
        compose.onNodeWithTag("chat-actions").performTouchInput { click() }
        compose.onNodeWithText(localized.getString(R.string.chat_rename))
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performTouchInput { click() }
        compose.waitForIdle()
        assertThat(listOf(menus, newChats, models, renames)).containsExactly(1, 1, 1, 1).inOrder()
        println("360dp pointer matrix locale=${localized.resources.configuration.locales[0]} fontScale=${localized.resources.configuration.fontScale} header=${bounds("header")} usage=${bounds("header-usage")}")
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun boundsOf(text: String): Rect =
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** How the text settled: an ellipsized line hides its end. */
    private fun assertNoOverflow(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertThat(results).isNotEmpty()
        val result = results.last()
        assertThat((0 until result.lineCount).any { result.isLineEllipsized(it) }).isFalse()
    }

    private fun assertNodeNoOverflow(tag: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertThat(results).isNotEmpty()
        val result = results.last()
        assertThat((0 until result.lineCount).any { result.isLineEllipsized(it) }).isFalse()
    }

    private fun rootWidth(): Float = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.right

    private val minimumTarget: Float get() = with(compose.density) { 48.dp.toPx() }

    /**
     * The platform grows a clickable smaller than 48 dp around its centre before it takes taps. A
     * layout check alone can therefore miss two targets that actually compete for the same finger.
     */
    private fun effectiveBounds(tag: String): Rect {
        val layout = bounds(tag)
        fun grow(start: Float, end: Float): Pair<Float, Float> {
            val size = end - start
            if (size >= minimumTarget) return start to end
            val pad = (minimumTarget - size) / 2f
            return (start - pad) to (end + pad)
        }
        val (left, right) = grow(layout.left, layout.right)
        val (top, bottom) = grow(layout.top, layout.bottom)
        return Rect(left, top, right, bottom)
    }

    private fun assertTargetsApart(title: Rect, tags: List<String>) {
        val targets = tags.map { it to effectiveBounds(it) }
        targets.forEach { (_, target) ->
            assertThat(target.width).isAtLeast(minimumTarget)
            assertThat(target.height).isAtLeast(minimumTarget)
            assertThat(title.overlaps(target)).isFalse()
        }
        targets.forEachIndexed { index, (_, first) ->
            targets.drop(index + 1).forEach { (_, second) ->
                assertThat(first.overlaps(second)).isFalse()
            }
        }
    }

    /** Every touch target inside the header: one per control, never a second interactive row. */
    private fun headerClickTargets(): List<Rect> {
        val header = bounds("header")
        return compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .filter { it.left >= header.left && it.right <= header.right && it.top >= header.top && it.bottom <= header.bottom }
    }
}
