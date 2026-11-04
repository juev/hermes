package org.evsyukov.hermes.background

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.remote.FakeHistory
import org.evsyukov.hermes.data.remote.FakeHistory.Companion.answer
import org.evsyukov.hermes.data.remote.FakeHistory.Companion.call
import org.evsyukov.hermes.data.remote.FakeHistory.Companion.reasoning
import org.evsyukov.hermes.data.remote.FakeHistory.Companion.result
import org.evsyukov.hermes.data.remote.FakeHistory.Companion.session
import org.evsyukov.hermes.data.remote.FakeHistory.Companion.user
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.ServerStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ActivityCheckTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val history = FakeHistory()
    private val check = ActivityCheck(context)
    private val announced = CopyOnWriteArrayList<Pair<String, String>>()
    private lateinit var session: ServerSession

    @Before
    fun setUp() {
        server.dispatcher = history
        server.start()
        // The mark of the last check is kept per server; each test has a server of its own.
        val profile = ServerProfile("check-${UUID.randomUUID()}", "Home", server.url("/").toString())
        session = ServerSession(profile, OkHttpClient(), ServerStore(context, PlainCipher, scope), AppStrings(context.resources))
        runBlocking { check.markSeen(session, now = 100.0) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private suspend fun run(visibleChat: String? = null, check: ActivityCheck = this.check) =
        check.run(session, { id, _, text, _ -> announced += id to text }, visibleChat)

    /**
     * A chat answered at 120 and one whose history fails, answered at 130; [failingFirst] puts the
     * failing chat first in the list, as the newer activity.
     */
    private fun answeredAndFailing(failingFirst: Boolean) {
        val readable = session("readable", lastActive = if (failingFirst) 120.0 else 140.0)
        history.rows["readable"] = listOf(user(1, 110.0, "question"), answer(2, 120.0, "the readable answer")) +
            if (failingFirst) emptyList() else listOf(call(3, 135.0, "c1"), result(4, 140.0, "c1"))
        val failing = session("failing", lastActive = 130.0)
        history.rows["failing"] = listOf(user(1, 125.0, "question"), answer(2, 130.0, "the late answer"))
        history.failing += "failing"
        history.sessions += if (failingFirst) listOf(failing, readable) else listOf(readable, failing)
    }

    private fun failingHistoryHoldsBackNothing(failingFirst: Boolean) = runBlocking<Unit> {
        answeredAndFailing(failingFirst)

        run()
        assertThat(announced).containsExactly("readable" to "the readable answer")

        // The history still fails; the process is started again since.
        run(check = ActivityCheck(context))
        assertThat(announced).containsExactly("readable" to "the readable answer")

        history.failing.clear()
        run(check = ActivityCheck(context))
        assertThat(announced).containsExactly("readable" to "the readable answer", "failing" to "the late answer").inOrder()

        history.sessions += session("later", lastActive = 150.0)
        history.rows["later"] = listOf(user(1, 145.0, "question"), answer(2, 150.0, "a later answer"))
        run()
        run()
        assertThat(announced).containsExactly(
            "readable" to "the readable answer", "failing" to "the late answer", "later" to "a later answer",
        ).inOrder()
    }

    @Test
    fun `a history that fails holds back no other answer, and is announced once it reads, when it is the newer chat`() =
        failingHistoryHoldsBackNothing(failingFirst = true)

    @Test
    fun `a history that fails holds back no other answer, and is announced once it reads, when it is the older chat`() =
        failingHistoryHoldsBackNothing(failingFirst = false)

    @Test
    fun `answers past the limit of one check are announced by the next, each once`() = runBlocking<Unit> {
        for (i in 1..7) {
            history.sessions += session("c$i", lastActive = 110.0 + i)
            history.rows["c$i"] = listOf(user(1, 105.0, "question"), answer(2, 110.0 + i, "answer $i"))
        }

        run()
        assertThat(announced).hasSize(5)

        run(check = ActivityCheck(context))
        run()
        assertThat(announced.map { it.first }).containsExactlyElementsIn((1..7).map { "c$it" })
    }

    /**
     * Thirty newer chats, without an answer, push the older ones out of the list of a check; a check
     * runs, then the newer chats go again.
     */
    private suspend fun pushedOutOfTheList() {
        val newer = (1..30).map { session("newer$it", lastActive = 200.0 + it) }
        for (i in 1..30) history.rows["newer$i"] = listOf(user(1, 200.0 + i, "a question"))
        history.sessions.addAll(0, newer.reversed())
        run(check = ActivityCheck(context))
        history.sessions.removeAll(newer.toSet())
    }

    @Test
    fun `a chat whose history failed is announced once it reads, even after newer chats pushed it out of a check`() = runBlocking<Unit> {
        history.sessions += session("healthy", lastActive = 130.0)
        history.rows["healthy"] = listOf(user(1, 125.0, "question"), answer(2, 130.0, "the healthy answer"))
        history.sessions += session("failing", lastActive = 120.0)
        history.rows["failing"] = listOf(user(1, 110.0, "question"), answer(2, 120.0, "the late answer"))
        history.failing += "failing"

        run()
        assertThat(announced).containsExactly("healthy" to "the healthy answer")

        pushedOutOfTheList()
        history.failing.clear()
        run(check = ActivityCheck(context))
        run()

        assertThat(announced).containsExactly("healthy" to "the healthy answer", "failing" to "the late answer").inOrder()
    }

    @Test
    fun `answers past the limit of a check are announced, even after newer chats pushed them out of the next`() = runBlocking<Unit> {
        for (i in 1..7) {
            history.sessions += session("c$i", lastActive = 110.0 + i)
            history.rows["c$i"] = listOf(user(1, 105.0, "question"), answer(2, 110.0 + i, "answer $i"))
        }

        run()
        assertThat(announced).hasSize(5)

        pushedOutOfTheList()
        run(check = ActivityCheck(context))
        run()

        assertThat(announced.map { it.first }).containsExactlyElementsIn((1..7).map { "c$it" })
    }

    @Test
    fun `a check under way does not take back the mark the app set meanwhile`() = runBlocking<Unit> {
        history.sessions += session("slow", lastActive = 130.0)
        history.rows["slow"] = listOf(user(1, 125.0, "question"), answer(2, 130.0, "the slow answer"))
        val hold = FakeHistory.Hold().also { history.held["slow"] = it }

        val checking = async(Dispatchers.IO) { run() }
        assertThat(hold.arrived.await(10, TimeUnit.SECONDS)).isTrue()
        // The app comes to the front while the check reads: everything up to 200 is seen.
        val seen = async(Dispatchers.IO) { check.markSeen(session, now = 200.0) }
        delay(200)
        hold.release.countDown()
        checking.await()
        seen.await()

        history.sessions += session("seen", lastActive = 150.0)
        history.rows["seen"] = listOf(user(1, 145.0, "question"), answer(2, 150.0, "an answer seen in the app"))
        run()

        assertThat(announced).containsExactly("slow" to "the slow answer")
    }

    @Test
    fun `reasoning, tools and the user's words after an old answer announce nothing, while a new answer elsewhere does`() = runBlocking<Unit> {
        history.sessions += session("working", lastActive = 150.0)
        history.rows["working"] = listOf(
            user(1, 80.0, "first question"), answer(2, 90.0, "the old answer"),
            user(3, 120.0, "next question"), reasoning(4, 125.0), call(5, 130.0, "c1"), result(6, 140.0, "c1"), reasoning(7, 150.0),
        )
        history.sessions += session("answered", lastActive = 130.0)
        history.rows["answered"] = listOf(user(1, 110.0, "hello"), answer(2, 130.0, "the new answer"))

        run()

        assertThat(announced).containsExactly("answered" to "the new answer")
    }

    @Test
    fun `a chat without an answer since the check is not announced by its preview or a placeholder`() = runBlocking<Unit> {
        history.sessions += session("asked", lastActive = 140.0, preview = "my own question")
        history.rows["asked"] = listOf(user(1, 120.0, "my own question"), call(2, 130.0, "c1"), result(3, 140.0, "c1"))

        run()

        assertThat(announced).isEmpty()
    }

    @Test
    fun `a failed read of the history announces nothing, and the next check still announces the answer`() = runBlocking<Unit> {
        history.sessions += session("flaky", lastActive = 120.0)
        history.rows["flaky"] = listOf(user(1, 110.0, "question"), answer(2, 120.0, "the answer"))
        history.failing += "flaky"

        runCatching { run() }
        assertThat(announced).isEmpty()

        history.failing.clear()
        run()

        assertThat(announced).containsExactly("flaky" to "the answer")
    }

    @Test
    fun `an answer behind a long tool run is announced with its own words`() = runBlocking<Unit> {
        val tools = (0 until 12).flatMap { i -> listOf(call(3L + 2 * i, 111.0 + 2 * i, "c$i"), result(4L + 2 * i, 112.0 + 2 * i, "c$i")) }
        history.sessions += session("long", lastActive = 134.0)
        history.rows["long"] = listOf(user(1, 105.0, "question"), answer(2, 110.0, "the answer before the tools")) + tools

        run()

        assertThat(announced).containsExactly("long" to "the answer before the tools")
    }

    @Test
    fun `a new answer is announced once`() = runBlocking<Unit> {
        history.sessions += session("a", lastActive = 120.0)
        history.rows["a"] = listOf(user(1, 110.0, "question"), answer(2, 120.0, "the answer"))

        run()
        run()

        assertThat(announced).containsExactly("a" to "the answer")
    }

    @Test
    fun `the chat on the screen is not announced`() = runBlocking<Unit> {
        history.sessions += session("open", lastActive = 120.0)
        history.rows["open"] = listOf(user(1, 110.0, "question"), answer(2, 120.0, "the answer"))

        run(visibleChat = "open")

        assertThat(announced).isEmpty()
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}
