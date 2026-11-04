package org.evsyukov.hermes.data.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.remote.FakeHistory.Companion.answer
import org.evsyukov.hermes.data.remote.FakeHistory.Companion.call
import org.evsyukov.hermes.data.remote.FakeHistory.Companion.reasoning
import org.evsyukov.hermes.data.remote.FakeHistory.Companion.result
import org.evsyukov.hermes.data.remote.FakeHistory.Companion.user
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class LastReplyTest {
    private val server = MockWebServer()
    private val history = FakeHistory()
    private lateinit var api: ChatApi

    @Before
    fun setUp() {
        server.dispatcher = history
        server.start()
        val strings = AppStrings(ApplicationProvider.getApplicationContext<Application>().resources)
        api = ChatApi(HermesHttp(server.url("/"), OkHttpClient(), tokens = { null }, saveTokens = {}, strings = strings))
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** [count] tool calls with their results, two rows each, a second apart from [from]. */
    private fun tools(firstRow: Long, from: Double, count: Int) =
        (0 until count).flatMap { i -> listOf(call(firstRow + 2 * i, from + 2 * i, "c$i"), result(firstRow + 2 * i + 1, from + 2 * i + 1, "c$i")) }

    @Test
    fun `the newest answer after the mark is found behind a long tool run`() = runBlocking<Unit> {
        history.rows["s"] = listOf(user(1, 100.0, "question"), answer(2, 110.0, "an answer"), answer(3, 111.0, "the newest answer")) +
            tools(firstRow = 4, from = 112.0, count = 60)

        val reply = api.lastReply("s", after = 105.0)

        assertThat(reply?.text).isEqualTo("the newest answer")
        assertThat(reply?.timestamp).isEqualTo(111.0)
    }

    @Test
    fun `reasoning, tool calls and results, the user's words and hidden rows are no answer`() = runBlocking<Unit> {
        history.rows["s"] = listOf(
            answer(1, 90.0, "the answer already read"),
            user(2, 101.0, "next question"),
            reasoning(3, 102.0),
            call(4, 103.0, "c1"),
            result(5, 104.0, "c1"),
            """{"id": 6, "role": "assistant", "content": "for the model only", "display_kind": "hidden", "timestamp": 105.0}""",
            """{"id": 7, "role": "assistant", "content": "   ", "timestamp": 106.0}""",
        )

        assertThat(api.lastReply("s", after = 100.0)).isNull()
    }

    @Test
    fun `the look goes back no further than the mark`() = runBlocking<Unit> {
        history.rows["s"] = listOf(answer(1, 50.0, "an old answer")) + tools(firstRow = 2, from = 51.0, count = 100) +
            listOf(user(202, 300.0, "question"), call(203, 301.0, "c"), result(204, 302.0, "c"))

        assertThat(api.lastReply("s", after = 299.0)).isNull()
        assertThat(history.historyRequests).hasSize(1)
    }

    @Test
    fun `an answer at the mark is not after it`() = runBlocking<Unit> {
        history.rows["s"] = listOf(user(1, 90.0, "question"), answer(2, 100.0, "the answer"))

        assertThat(api.lastReply("s", after = 100.0)).isNull()
        assertThat(api.lastReply("s", after = 99.5)?.text).isEqualTo("the answer")
    }
}
