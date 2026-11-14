package org.evsyukov.hermes.ui.admin

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.remote.AdminApi
import org.evsyukov.hermes.data.remote.HermesHttp
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Model screen reports what the server actually applied: `api/model/set` can answer ok while a
 * managed policy keeps the main model, and the screen must then name the policy instead of claiming
 * the switch (issue #159). The comparison reads `api/model/info` after the set answer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
class ModelPolicyTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val strings = AppStrings(app.resources)
    private val mock = MockDashboard()
    private val modelChanged = AtomicInteger()
    private lateinit var api: AdminApi

    @Before
    fun setUp() {
        mock.start()
        val http = HermesHttp(
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
        mock.close()
    }

    private fun show() = compose.setContent {
        HermesTheme { ModelScreen(api, onBack = {}, onMainModelChanged = { modelChanged.incrementAndGet() }) }
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun assertNoText(text: String) {
        assertThat(compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `an ok answer that kept the old model reports the server policy, not success`() {
        mock.get("/api/model/info", OLD_INFO)
        mock.get("/api/model/options", OPTIONS)
        mock.setModelResponse("""{"ok":true}""")
        show()
        waitForText("gpt-5-codex")

        compose.onNodeWithText("gpt-5").performClick()

        val policy = app.getString(R.string.admin_model_policy_kept)
        waitForText(policy)
        // The snackbar suspends the pick until it is dismissed; only afterwards is the pick complete.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(policy, substring = true)).fetchSemanticsNodes().isEmpty() }
        assertNoText(app.getString(R.string.admin_model_new_chats, "gpt-5"))
        assertThat(modelChanged.get()).isEqualTo(0)
        assertThat(mock.setBodies().single()).contains("\"scope\":\"main\"")
        assertThat(mock.setBodies().single()).contains("\"model\":\"gpt-5\"")
        // The screen keeps showing the model actually in effect.
        assertThat(compose.onAllNodes(hasText("gpt-5-codex")).fetchSemanticsNodes()).isNotEmpty()
    }

    @Test
    fun `a set the server applied confirms the choice and notifies the app once`() {
        mock.get("/api/model/info", OLD_INFO)
        mock.infoAfterSet = NEW_INFO
        mock.get("/api/model/options", OPTIONS)
        mock.setModelResponse("""{"ok":true}""")
        show()
        waitForText("gpt-5-codex")

        compose.onNodeWithText("gpt-5").performClick()

        waitForText(app.getString(R.string.admin_model_new_chats, "gpt-5"))
        assertNoText(app.getString(R.string.admin_model_policy_kept))
        compose.waitUntil(10_000) { modelChanged.get() == 1 }
        assertThat(modelChanged.get()).isEqualTo(1)
    }

    @Test
    fun `an expensive model still asks first and only the confirmed set is compared`() {
        mock.get("/api/model/info", OLD_INFO)
        mock.infoAfterSet = NEW_INFO
        mock.get("/api/model/options", OPTIONS)
        mock.setModelResponse("""{"confirm_required":true,"confirm_message":"gpt-5 is expensive"}""")
        show()
        waitForText("gpt-5-codex")

        compose.onNodeWithText("gpt-5").performClick()
        waitForText("gpt-5 is expensive")
        assertNoText(app.getString(R.string.admin_model_new_chats, "gpt-5"))
        assertNoText(app.getString(R.string.admin_model_policy_kept))
        assertThat(modelChanged.get()).isEqualTo(0)
        assertThat(mock.sets()).isEqualTo(1)

        mock.setModelResponse("""{"ok":true}""")
        compose.onNodeWithText(app.getString(R.string.chat_switch)).performClick()

        waitForText(app.getString(R.string.admin_model_new_chats, "gpt-5"))
        compose.waitUntil(10_000) { modelChanged.get() == 1 }
        assertThat(modelChanged.get()).isEqualTo(1)
        assertThat(mock.sets()).isEqualTo(2)
        assertThat(mock.setBodies().last()).contains("\"confirm_expensive_model\":true")
    }

    private companion object {
        const val OLD_INFO = """{"provider":"openai-codex","model":"gpt-5-codex","effective_context_length":"272000"}"""
        const val NEW_INFO = """{"provider":"openai-codex","model":"gpt-5","effective_context_length":"400000"}"""
        const val OPTIONS =
            """{"providers":[{"slug":"openai-codex","name":"OpenAI Codex","models":["gpt-5-codex","gpt-5"],"is_current":true}],""" +
                """"provider":"openai-codex","model":"gpt-5-codex"}"""
    }

    /** A dashboard whose model info changes only when a set is applied; a policy keeps it unchanged. */
    private class MockDashboard {
        val server = MockWebServer()
        private val bodies = ConcurrentHashMap<String, String>()
        private val requests = ConcurrentLinkedQueue<RecordedRequest>()

        @Volatile
        var infoAfterSet: String? = null

        @Volatile
        private var setResponse: MockResponse =
            MockResponse.Builder().code(500).body("""{"detail":"no set configured"}""").build()

        fun start() {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.url.encodedPath
                    if (request.method == "POST" && path == "/api/model/set") {
                        infoAfterSet?.let { bodies["/api/model/info"] = it }
                        return setResponse
                    }
                    val body = bodies[path] ?: return MockResponse.Builder().code(404).build()
                    return MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
                }
            }
            server.start()
        }

        fun close() = server.close()

        fun get(path: String, body: String) {
            bodies[path] = body
        }

        fun setModelResponse(body: String) {
            setResponse = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
        }

        fun sets(): Int = requests.count { it.method == "POST" && it.url.encodedPath == "/api/model/set" }

        fun setBodies(): List<String> =
            requests.filter { it.method == "POST" && it.url.encodedPath == "/api/model/set" }.map { it.body?.utf8().orEmpty() }
    }
}