package org.evsyukov.hermes.ui.admin

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.remote.AdminApi
import org.evsyukov.hermes.data.remote.ConnectorsApi
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.remote.HermesHttp
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * The four admin forms that write to the server — a config field, a secret, a new cron job and an
 * MCP install — keep their input when the write fails, freeze it while a write is in flight, allow
 * exactly one request at a time, and close only after the server accepted them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
class AdminFormRecoveryTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val strings = AppStrings(app.resources)
    private val mock = MockAdmin()
    private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var http: HermesHttp
    private lateinit var api: AdminApi

    @Before
    fun setUp() {
        mock.start()
        http = HermesHttp(
            mock.server.url("/"),
            OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build(),
            tokens = { null },
            saveTokens = {},
            strings = strings,
        )
        api = AdminApi(http)
    }

    @After
    fun tearDown() {
        gatewayScope.cancel()
        mock.close()
    }

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        HermesTheme {
            val window = remember { InlineFormWindow() }
            CompositionLocalProvider(LocalFormWindow provides window) { content() }
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForDescription(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForDialog() = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("form-submit").fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForDialogGone() = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("form-submit").fetchSemanticsNodes().isEmpty()
    }

    /** A field of the form, not of the screen behind it. */
    private fun dialogFields() = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("form-window")))

    /** Every editable value of the open form, including a disabled or read-only field. */
    private fun formValues() = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and hasAnyAncestor(hasTestTag("form-window")),
    )

    private fun assertFieldHolds(text: String) {
        assertThat(
            compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and
                    hasAnyAncestor(hasTestTag("form-window")) and hasText(text),
            ).fetchSemanticsNodes(),
        ).hasSize(1)
    }

    /** Every text field of the open form is either editable or frozen, never a mix. */
    private fun assertFormFields(editable: Boolean) {
        val fields = formValues().fetchSemanticsNodes()
        assertThat(fields).isNotEmpty()
        for (index in fields.indices) {
            if (editable) formValues()[index].assertIsEnabled() else formValues()[index].assertIsNotEnabled()
        }
    }

    /** Waits until the save is running and its one request has reached the server; the answer is held by the fixture. */
    private fun waitForHeldWrite(writePath: String, count: Int) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(app.getString(R.string.common_saving)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { mock.writes(writePath) == count }
        assertThat(compose.onAllNodesWithText(app.getString(R.string.common_saving)).fetchSemanticsNodes()).isNotEmpty()
    }

    /**
     * Reproduces the delayed-answer window on one real form. The first accepted write is held: no
     * widget may change while its answer is pending, and only that value may reach the server. Then
     * a rejection gives the form back with the value intact, the correction is what the next
     * requests carry, and a held success closes the form without a second send.
     */
    private fun exerciseForm(
        writePath: String,
        unknownOutcome: Boolean,
        open: () -> Unit,
        fill: (String) -> Unit,
        replace: (String) -> Unit,
        submit: () -> Unit,
        frozenExtra: () -> Unit = {},
        editableExtra: () -> Unit = {},
    ) {
        val first = "A-marker"
        val corrected = "B-marker"

        open()
        fill(first)
        val held = mock.writes(writePath)
        mock.respond(200, """{"ok":true}""", delayMillis = 2_000)
        submit()
        waitForHeldWrite(writePath, held + 1)
        assertFormFields(editable = false)
        frozenExtra()
        compose.onNodeWithTag("form-submit").assertIsNotEnabled().performTouchInput { click() }
        waitForDialogGone()
        assertThat(mock.writes(writePath)).isEqualTo(held + 1)
        assertThat(mock.bodies(writePath).last()).contains(first)

        open()
        fill(first)
        mock.respond(400, """{"detail":"invalid value"}""")
        submit()
        waitForText("invalid value")
        assertFormFields(editable = true)
        assertFieldHolds(first)
        editableExtra()
        replace(corrected)
        assertFieldHolds(corrected)

        mock.respond(503, """{"detail":"server busy"}""")
        submit()
        waitForText("server busy")
        assertFormFields(editable = true)
        assertFieldHolds(corrected)

        mock.closeConnection()
        submit()
        waitForText(app.getString(if (unknownOutcome) R.string.admin_form_unknown_outcome else R.string.admin_form_connection_lost))
        assertFormFields(editable = true)
        assertFieldHolds(corrected)

        val accepted = mock.writes(writePath)
        mock.respond(200, """{"ok":true}""", delayMillis = 2_000)
        submit()
        waitForHeldWrite(writePath, accepted + 1)
        assertFormFields(editable = false)
        frozenExtra()
        compose.onNodeWithTag("form-submit").assertIsNotEnabled().performTouchInput { click() }
        waitForDialogGone()
        assertThat(mock.writes(writePath)).isEqualTo(accepted + 1)
        assertThat(mock.bodies(writePath).last()).contains(corrected)
    }

    @Test
    fun `a config field keeps its value through every failure and closes only on success`() {
        mock.get("/api/config/schema", """{"fields":{"provider.name":{"type":"string","description":"Main model","category":"provider"}}}""")
        mock.get("/api/config", """{"provider":{"name":"gpt-4"}}""")
        show { ConfigScreen(api, onBack = {}) }
        waitForText("provider.name")

        val open = { compose.onNodeWithText("provider.name").performClick(); waitForDialog() }
        val fill: (String) -> Unit = { value ->
            dialogFields()[0].performTextClearance()
            dialogFields()[0].performTextInput(value)
        }
        val reloads = mock.gets("/api/config")
        exerciseForm(
            "/api/config",
            unknownOutcome = false,
            open = open,
            fill = fill,
            replace = fill,
            submit = { compose.onNodeWithTag("form-submit").performClick() },
        )
        assertThat(mock.gets("/api/config")).isGreaterThan(reloads)
    }

    @Test
    fun `a boolean config field freezes its switch while the save is held`() {
        mock.get("/api/config/schema", """{"fields":{"flags.enabled":{"type":"boolean","description":"Enable the flag","category":"flags"}}}""")
        mock.get("/api/config", """{"flags":{"enabled":false}}""")
        show { ConfigScreen(api, onBack = {}) }
        waitForText("flags.enabled")

        compose.onNodeWithText("flags.enabled").performClick()
        waitForDialog()
        val switch = hasAnyAncestor(hasTestTag("form-window")) and isToggleable()
        compose.onNode(switch).assertIsEnabled().performClick().assertIsOn()

        val writes = mock.writes("/api/config")
        mock.respond(200, """{"ok":true}""", delayMillis = 2_000)
        compose.onNodeWithTag("form-submit").performClick()
        waitForHeldWrite("/api/config", writes + 1)
        compose.onNode(switch).assertIsNotEnabled()
        waitForDialogGone()
        assertThat(mock.writes("/api/config")).isEqualTo(writes + 1)
        assertThat(mock.bodies("/api/config").last()).contains("\"enabled\":true")
    }

    @Test
    fun `an enum config field freezes its dropdown while the save is held`() {
        mock.get(
            "/api/config/schema",
            """{"fields":{"provider.mode":{"type":"string","description":"Mode","category":"provider","options":["fast","slow"]}}}""",
        )
        mock.get("/api/config", """{"provider":{"mode":"fast"}}""")
        show { ConfigScreen(api, onBack = {}) }
        waitForText("provider.mode")
        // The editor reads the current value when it opens, so the config must already be on screen.
        waitForText("fast")

        compose.onNodeWithText("provider.mode").performClick()
        waitForDialog()
        assertFieldHolds("fast")

        val writes = mock.writes("/api/config")
        mock.respond(200, """{"ok":true}""", delayMillis = 2_000)
        compose.onNodeWithTag("form-submit").performClick()
        waitForHeldWrite("/api/config", writes + 1)
        assertFormFields(editable = false)
        compose.onNodeWithTag("form-options").assertIsNotEnabled()
        waitForDialogGone()
        assertThat(mock.writes("/api/config")).isEqualTo(writes + 1)
        assertThat(mock.bodies("/api/config").last()).contains("\"mode\":\"fast\"")
    }

    @Test
    fun `a secret keeps its draft through every failure, clears it on success and never closes early`() {
        mock.get("/api/env", """{}""")
        show { SecretsScreen(api, onBack = {}) }
        waitForText(app.getString(R.string.admin_secrets_add))

        val open = {
            compose.onNodeWithText(app.getString(R.string.admin_secrets_add)).performClick()
            waitForDialog()
        }
        val fill: (String) -> Unit = { value ->
            dialogFields()[0].performTextInput("API_TOKEN")
            dialogFields()[1].performTextInput(value)
        }
        val replace: (String) -> Unit = { value ->
            dialogFields()[1].performTextClearance()
            dialogFields()[1].performTextInput(value)
        }
        val reloads = mock.gets("/api/env")
        exerciseForm(
            "/api/env",
            unknownOutcome = false,
            open = open,
            fill = fill,
            replace = replace,
            submit = { compose.onNodeWithTag("form-submit").performClick() },
        )
        assertThat(mock.gets("/api/env")).isGreaterThan(reloads)

        // The ephemeral draft is gone with the accepted save: a new variable opens empty.
        open()
        assertThat(compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("form-window")) and hasText("B-marker")).fetchSemanticsNodes()).isEmpty()

        // An explicit cancel drops the ephemeral draft as well.
        dialogFields()[0].performTextInput("LEAKY_TOKEN")
        dialogFields()[1].performTextInput("draft-secret")
        compose.onNodeWithText(app.getString(R.string.common_cancel)).performClick()
        waitForDialogGone()
        open()
        assertThat(compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("form-window")) and hasText("draft-secret")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `a rejected cron creation keeps the form, and a lost connection says to inspect the list first`() {
        mock.get("/api/cron/jobs", """{"jobs":[]}""")
        mock.get("/api/cron/delivery-targets", """{"targets":[]}""")
        show { CronScreen(api, onBack = {}) }
        waitForDescription(app.getString(R.string.admin_cron_new))

        val open = {
            compose.onNodeWithContentDescription(app.getString(R.string.admin_cron_new)).performClick()
            waitForDialog()
        }
        val fill: (String) -> Unit = { value ->
            dialogFields()[0].performTextInput("Nightly")
            dialogFields()[1].performTextInput("0 9 * * *")
            dialogFields()[2].performTextInput(value)
        }
        val replace: (String) -> Unit = { value ->
            dialogFields()[2].performTextClearance()
            dialogFields()[2].performTextInput(value)
        }
        val local = app.getString(R.string.admin_cron_local)
        val reloads = mock.gets("/api/cron/jobs")
        exerciseForm(
            "/api/cron/jobs",
            unknownOutcome = true,
            open = open,
            fill = fill,
            replace = replace,
            submit = { compose.onNodeWithTag("form-submit").performClick() },
            frozenExtra = { compose.onNode(hasText(local) and hasAnyAncestor(hasTestTag("form-window"))).assertIsNotEnabled() },
            editableExtra = { compose.onNode(hasText(local) and hasAnyAncestor(hasTestTag("form-window"))).assertIsEnabled() },
        )
        assertThat(mock.gets("/api/cron/jobs")).isGreaterThan(reloads)
    }

    @Test
    fun `a rejected MCP install keeps the form, and a lost connection says to inspect the list first`() {
        mock.get("/api/mcp/servers", """{"servers":[]}""")
        mock.get(
            "/api/mcp/catalog",
            """{"entries":[{"name":"github","description":"GitHub issues","connector_slug":null,"source":"official",
            "transport":"stdio","auth_type":"env","required_env":[{"name":"GITHUB_TOKEN","prompt":"Personal access token","required":true}],
            "command":"node","args":["dist/index.js"],"url":null,"install_url":"https://github.com/example/github-mcp","install_ref":"v1.2.0",
            "bootstrap":[],"default_enabled":null,"post_install":"","suggest":null,"needs_install":true,"installed":false,"enabled":false}]}""",
        )
        mock.webSocket(FakeGateway())
        val gatewayHttp = HermesHttp(mock.server.url("/"), OkHttpClient(), tokens = { null }, saveTokens = {}, strings = strings)
        val connectors = ConnectorsApi(GatewaySocket(gatewayHttp, gatewayScope, strings))
        show { IntegrationsScreen(api, connectors, onOpen = {}, onBack = {}) }
        waitForText(app.getString(R.string.integrations_catalog))

        val open = {
            compose.onNodeWithText(app.getString(R.string.integrations_catalog)).performClick()
            waitForText("GitHub issues")
            compose.onNodeWithText("github").performClick()
            waitForDialog()
        }
        val fill: (String) -> Unit = { value -> dialogFields()[0].performTextInput(value) }
        val replace: (String) -> Unit = { value ->
            dialogFields()[0].performTextClearance()
            dialogFields()[0].performTextInput(value)
        }
        val reloads = mock.gets("/api/mcp/servers")
        exerciseForm(
            "/api/mcp/catalog/install",
            unknownOutcome = true,
            open = open,
            fill = fill,
            replace = replace,
            submit = { compose.onNodeWithTag("form-submit").performClick() },
        )
        assertThat(mock.gets("/api/mcp/servers")).isGreaterThan(reloads)

        // The credential was only ever the dialog's memory: it is empty when the dialog opens again.
        open()
        assertThat(compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("form-window")) and hasText("B-marker")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `the raw config still asks to replace the file and keeps the editor on a rejection`() {
        mock.get("/api/config/schema", """{"fields":{}}""")
        mock.get("/api/config", """{}""")
        mock.get("/api/config/raw", """{"yaml":"a: 1"}""")
        show { ConfigScreen(api, onBack = {}) }
        waitForDescription(app.getString(R.string.admin_config_raw))

        compose.onNodeWithContentDescription(app.getString(R.string.admin_config_raw)).performClick()
        compose.waitUntil(10_000) { dialogFields().fetchSemanticsNodes().isNotEmpty() }
        waitForText("a: 1")
        dialogFields()[0].performTextClearance()
        dialogFields()[0].performTextInput("b: 2")
        compose.onNodeWithText(app.getString(R.string.common_save)).performClick()
        waitForText(app.getString(R.string.admin_config_replace_title))

        mock.respond(400, """{"detail":"invalid value"}""")
        compose.onNodeWithText(app.getString(R.string.admin_config_replace)).performClick()
        waitForText("invalid value")
        assertFieldHolds("b: 2")
    }

    /**
     * Hosts the form in the screen instead of a dialog window. Qualification: on the Robolectric and
     * Compose test harness pinned in this project, an editable text field inside a Compose dialog did
     * not settle, so a test would wait forever. This host drives the same form composables; it says
     * nothing about real modal dismissal, Android Back, the IME or window layout, and production
     * still uses the [AlertDialog] wrapper.
     */
    private class InlineFormWindow : FormWindow {
        @Composable
        override fun Show(
            title: @Composable () -> Unit,
            onDismiss: () -> Unit,
            confirm: @Composable () -> Unit,
            dismiss: @Composable () -> Unit,
            content: @Composable () -> Unit,
        ) {
            Box(Modifier.fillMaxSize().testTag("form-window")) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    title()
                    content()
                    Row {
                        confirm()
                        dismiss()
                    }
                }
            }
        }
    }

    /** The smallest fake of the chat socket the integrations screen asks for its account list. */
    private class FakeGateway : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = HermesJson.parseToJsonElement(text).jsonObject
            val id = frame["id"]?.jsonPrimitive?.content ?: return
            val method = frame["method"]?.jsonPrimitive?.content ?: return
            val result = when (method) {
                "connectors.list" -> """{"available":true,"connectors":[]}"""
                "connectors.catalog" -> """{"connectors":[]}"""
                "connectors.accounts" -> """{"accounts":[]}"""
                else -> "{}"
            }
            webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":$result}""")
        }
    }

    /** A dashboard that answers reads from a fixed map and writes from the last installed answer. */
    private class MockAdmin {
        val server = MockWebServer()
        private val bodies = ConcurrentHashMap<String, String>()
        private val requests = ConcurrentLinkedQueue<RecordedRequest>()
        @Volatile private var writeResponse: MockResponse = MockResponse.Builder().code(500).body("""{"detail":"no write configured"}""").build()
        @Volatile private var socket: WebSocketListener? = null

        fun start() {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.url.encodedPath
                    socket?.takeIf { path == "/api/ws" }?.let { return MockResponse.Builder().webSocketUpgrade(it).build() }
                    if (request.method != "GET") return writeResponse
                    val body = bodies[path] ?: return MockResponse.Builder().code(404).build()
                    return MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
                }
            }
            server.start()
        }

        fun close() = server.close()

        fun webSocket(listener: WebSocketListener) {
            socket = listener
        }

        fun get(path: String, body: String) {
            bodies[path] = body
        }

        fun respond(code: Int, body: String, delayMillis: Long = 0) {
            val builder = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
            writeResponse = if (delayMillis > 0) builder.headersDelay(delayMillis, TimeUnit.MILLISECONDS).build() else builder.build()
        }

        fun closeConnection() {
            // The client gives up after its short read timeout: the write's outcome is unknown.
            writeResponse = MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{}")
                .headersDelay(15, TimeUnit.SECONDS)
                .build()
        }

        fun gets(path: String): Int = requests.count { it.method == "GET" && it.url.encodedPath == path }

        fun writes(path: String): Int = requests.count { it.method != "GET" && it.url.encodedPath == path }

        /** The actual payloads of the writes to [path], in arrival order. */
        fun bodies(path: String): List<String> =
            requests.filter { it.method != "GET" && it.url.encodedPath == path }.map { it.body?.utf8().orEmpty() }
    }
}
