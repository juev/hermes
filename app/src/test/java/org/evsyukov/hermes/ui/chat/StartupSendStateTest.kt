package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.R
import org.evsyukov.hermes.background.TurnHold
import org.evsyukov.hermes.chat.ChatController
import org.evsyukov.hermes.chat.ChatItem
import org.evsyukov.hermes.chat.Delivery
import org.evsyukov.hermes.chat.LiveGateway
import org.evsyukov.hermes.chat.LiveServer
import org.evsyukov.hermes.chat.closeWithOwnedJobs
import org.evsyukov.hermes.data.settings.Settings
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The initial load answers the server's state as it was when the load asked. A message sent while that
 * answer is still on its way is newer than it: the older idle answer must not put the chat back to idle
 * and take Stop away from the message the user just sent.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
class StartupSendStateTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = LiveServer.database(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = LiveGateway()
    private val server = LiveServer(gateway)
    private val strings = AppStrings(app.resources)

    /** Every answer of a join the controller applied, in order. */
    private val adoptions = AtomicInteger()

    /** The history answer the initial load waits for, released by the test. */
    private val history = CountDownLatch(1)

    /** The early-failure test calls [tearDown] itself; the later `@After` call must change nothing. */
    private val teardownDone = AtomicBoolean()

    @After
    fun tearDown() {
        if (!teardownDone.compareAndSet(false, true)) return
        history.countDown()
        server.historyGate = null
        // A load already waiting in `session.active_list` holds this gate. Clearing the field alone
        // would leave that call waiting while the join below waits for it.
        gateway.activeListGate?.countDown()
        gateway.activeListGate = null
        gateway.afterResume = null
        // Cache writes run on this scope; one still in its DAO call would outlive a cancelled job and
        // hit the database after it closed. The database is closed only once those jobs are over.
        scope.closeWithOwnedJobs(db) { server.close() }
    }

    private fun chat(onTurnStart: () -> TurnHold = { TurnHold.NONE }) = ChatController(
        server.session(app, scope, db), gateway, scope, strings, "s1",
        onLive = { _, _, _ -> adoptions.incrementAndGet() },
        onTurnStart = onTurnStart,
    )

    @androidx.compose.runtime.Composable
    private fun Screen(controller: ChatController, draft: ChatDraft) = ChatScreen(
        controller = controller, settings = Settings(), serverName = "Home", connected = true,
        onMenu = {}, onNewChat = {}, openFile = {}, loadFile = { null }, loadModels = { error("not opened") },
        openLocal = {}, onShown = { c, visible -> c.setVisible(visible) }, draft = draft,
    )

    private fun stops() =
        compose.onAllNodesWithContentDescription(app.getString(R.string.composer_stop)).fetchSemanticsNodes().size

    /** The chat's join has been applied: the live session is known and events of it can be taken. */
    private suspend fun ChatController.untilJoined() =
        withTimeout(5_000) { while (joinedTransport == 0L) delay(10) }

    /** Waits for [count] applied join answers, failing the test rather than carrying on early. */
    private suspend fun awaitAdoptions(count: Int) =
        withTimeout(5_000) { while (adoptions.get() < count) delay(10) }

    @Test
    fun `a message sent before the initial idle answer is applied keeps running and its stop`() = runBlocking<Unit> {
        gateway.connect()
        server.historyGate = history
        val chat = chat()
        try {
            compose.setContent { HermesTheme { Screen(chat, ChatDraft()) } }
            // The load is under way and waits for the history: its join answer, taken before the send,
            // is applied only after the history is released below.
            compose.waitUntil(5_000) { chat.state.value.loading }
            compose.waitUntil(5_000) { gateway.count("session.resume") == 1 }
            chat.untilJoined()

            chat.send("first", emptyList())
            // The send joined the live session on its own and submitted; the initial load's idle
            // answer has not been applied yet.
            compose.waitUntil(5_000) { gateway.count("prompt.submit") == 1 }
            compose.waitForIdle()
            assertThat(adoptions.get()).isEqualTo(0)
            assertThat(chat.state.value.running).isTrue()
            assertThat(stops()).isEqualTo(1)

            history.countDown()
            // The answer of the initial load's join is applied now, and it was asked before the send.
            withTimeout(5_000) { while (adoptions.get() < 1) delay(10) }
            compose.waitForIdle()

            assertWithMessage(
                "released: adoptions=${adoptions.get()} resumes=${gateway.count("session.resume")} " +
                    "submits=${gateway.count("prompt.submit")} loading=${chat.state.value.loading} " +
                    "running=${chat.state.value.running} stops=${stops()}",
            ).that(chat.state.value.running).isTrue()
            assertThat(stops()).isEqualTo(1)
        } finally {
            history.countDown()
            server.historyGate = null
            chat.close()
        }
    }

    @Test
    fun `a turn that finished before the initial idle answer is applied stays idle`() = runBlocking<Unit> {
        gateway.connect()
        server.historyGate = history
        val chat = chat()
        try {
            compose.setContent { HermesTheme { Screen(chat, ChatDraft()) } }
            compose.waitUntil(5_000) { chat.state.value.loading }
            compose.waitUntil(5_000) { gateway.count("session.resume") == 1 }
            chat.untilJoined()

            chat.send("first", emptyList())
            compose.waitUntil(5_000) { gateway.count("prompt.submit") == 1 }
            // The fake server runs the turn and finishes it while the initial answer is still held:
            // its completion is the newest word on the send.
            gateway.start("first")
            gateway.delta("an answer")
            gateway.complete()
            compose.waitUntil(5_000) { !chat.state.value.running }

            history.countDown()
            withTimeout(5_000) { while (adoptions.get() < 1) delay(10) }
            compose.waitForIdle()

            assertWithMessage(
                "released: adoptions=${adoptions.get()} resumes=${gateway.count("session.resume")} " +
                    "submits=${gateway.count("prompt.submit")} loading=${chat.state.value.loading} " +
                    "running=${chat.state.value.running} stops=${stops()}",
            ).that(chat.state.value.running).isFalse()
            assertThat(stops()).isEqualTo(0)
        } finally {
            history.countDown()
            server.historyGate = null
            chat.close()
        }
    }

    @Test
    fun `a message sent before the join is asked but submitted after its idle answer keeps running`() = runBlocking<Unit> {
        gateway.connect()
        server.historyGate = history
        val active = CountDownLatch(1)
        val answered = CountDownLatch(1)
        gateway.activeListGate = active
        gateway.afterResume = { answered.countDown() }
        // The send reaches `onTurnStart` before the load asks its join: it lets the load past the live
        // list and waits until the idle answer is taken. The submission then follows that answer.
        val chat = chat {
            active.countDown()
            check(answered.await(5, TimeUnit.SECONDS)) { "the join answer was not taken" }
            TurnHold.NONE
        }
        try {
            compose.setContent { HermesTheme { Screen(chat, ChatDraft()) } }

            chat.send("first", emptyList())
            compose.waitUntil(5_000) { gateway.count("prompt.submit") == 1 }
            // The server runs the turn; the answer its join took, however, was idle: it was taken
            // before the submission.
            gateway.start("first")
            compose.waitUntil(5_000) {
                (chat.state.value.items.lastOrNull() as? ChatItem.Assistant)?.streaming == true
            }
            compose.waitForIdle()
            assertThat(adoptions.get()).isEqualTo(0)
            assertThat(chat.state.value.running).isTrue()
            assertThat(stops()).isEqualTo(1)

            history.countDown()
            withTimeout(5_000) { while (adoptions.get() < 1) delay(10) }
            compose.waitForIdle()

            assertWithMessage(
                "released: adoptions=${adoptions.get()} resumes=${gateway.count("session.resume")} " +
                    "submits=${gateway.count("prompt.submit")} loading=${chat.state.value.loading} " +
                    "running=${chat.state.value.running} stops=${stops()}",
            ).that(chat.state.value.running).isTrue()
            assertThat(stops()).isEqualTo(1)
        } finally {
            history.countDown()
            server.historyGate = null
            gateway.activeListGate = null
            gateway.afterResume = null
            chat.close()
        }
    }

    @Test
    fun `a turn finished before the join answer was applied stays idle`() = runBlocking<Unit> {
        gateway.connect()
        server.historyGate = history
        val active = CountDownLatch(1)
        val answered = CountDownLatch(1)
        gateway.activeListGate = active
        gateway.afterResume = { answered.countDown() }
        val chat = chat {
            active.countDown()
            check(answered.await(5, TimeUnit.SECONDS)) { "the join answer was not taken" }
            TurnHold.NONE
        }
        try {
            compose.setContent { HermesTheme { Screen(chat, ChatDraft()) } }

            chat.send("first", emptyList())
            compose.waitUntil(5_000) { gateway.count("prompt.submit") == 1 }
            gateway.start("first")
            gateway.delta("an answer")
            gateway.complete()
            compose.waitUntil(5_000) { !chat.state.value.running }

            history.countDown()
            withTimeout(5_000) { while (adoptions.get() < 1) delay(10) }
            compose.waitForIdle()

            assertWithMessage(
                "released: adoptions=${adoptions.get()} resumes=${gateway.count("session.resume")} " +
                    "submits=${gateway.count("prompt.submit")} loading=${chat.state.value.loading} " +
                    "running=${chat.state.value.running} stops=${stops()}",
            ).that(chat.state.value.running).isFalse()
            assertThat(stops()).isEqualTo(0)
        } finally {
            history.countDown()
            server.historyGate = null
            gateway.activeListGate = null
            gateway.afterResume = null
            chat.close()
        }
    }

    @Test
    fun `a stop before the join answer was applied is not undone by that answer`() = runBlocking<Unit> {
        gateway.connect()
        server.historyGate = history
        val active = CountDownLatch(1)
        val answered = CountDownLatch(1)
        gateway.activeListGate = active
        gateway.afterResume = { answered.countDown() }
        val chat = chat {
            active.countDown()
            check(answered.await(5, TimeUnit.SECONDS)) { "the join answer was not taken" }
            TurnHold.NONE
        }
        try {
            compose.setContent { HermesTheme { Screen(chat, ChatDraft()) } }

            chat.send("first", emptyList())
            compose.waitUntil(5_000) { gateway.count("prompt.submit") == 1 }
            gateway.start("first")
            compose.waitUntil(5_000) {
                (chat.state.value.items.lastOrNull() as? ChatItem.Assistant)?.streaming == true
            }
            chat.stop()
            compose.waitUntil(5_000) { !chat.state.value.running }

            history.countDown()
            withTimeout(5_000) { while (adoptions.get() < 1) delay(10) }
            compose.waitForIdle()

            assertWithMessage(
                "released: adoptions=${adoptions.get()} resumes=${gateway.count("session.resume")} " +
                    "submits=${gateway.count("prompt.submit")} interrupts=${gateway.count("session.interrupt")} " +
                    "loading=${chat.state.value.loading} running=${chat.state.value.running} stops=${stops()}",
            ).that(chat.state.value.running).isFalse()
            assertThat(stops()).isEqualTo(0)
        } finally {
            history.countDown()
            server.historyGate = null
            gateway.activeListGate = null
            gateway.afterResume = null
            chat.close()
        }
    }

    @Test
    fun `a fresh idle join after a turn finished while away puts the chat back to idle`() = runBlocking<Unit> {
        gateway.connect()
        val chat = chat()
        try {
            compose.setContent { HermesTheme { Screen(chat, ChatDraft()) } }
            compose.waitUntil(5_000) { !chat.state.value.loading && chat.joinedTransport != 0L }
            awaitAdoptions(1)

            // The first send never reaches the server: the connection is gone before prompt.submit.
            gateway.drop()
            chat.send("first", emptyList())
            compose.waitUntil(5_000) {
                chat.state.value.items.any { it is ChatItem.User && it.text == "first" && it.delivery == Delivery.FAILED }
            }
            assertThat(gateway.count("prompt.submit")).isEqualTo(0)

            // A new connection brings the chat back; the server is idle and its join is applied.
            gateway.connect()
            awaitAdoptions(2)
            compose.waitUntil(5_000) { !chat.state.value.loading && chat.joinedTransport != 0L }

            // The next send goes out and the server starts its turn.
            chat.send("second", emptyList())
            compose.waitUntil(5_000) { gateway.count("prompt.submit") == 1 }
            gateway.start("second")
            compose.waitUntil(5_000) { chat.state.value.running }
            compose.waitUntil(5_000) {
                (chat.state.value.items.lastOrNull() as? ChatItem.Assistant)?.streaming == true
            }

            // The turn ends on the server while the chat is disconnected: no outcome reaches it, so
            // only a later server answer can tell the chat that the turn is over.
            gateway.drop()
            gateway.finishWithoutEvent()

            // The fresh join is asked after the turn ended and answers idle. That answer is newer than
            // both the send and the missed outcome, so it is authoritative: the chat is idle, with no
            // Stop for a turn that is no longer there.
            server.historyGate = history
            gateway.connect()
            compose.waitUntil(5_000) { gateway.count("session.resume") >= 3 }
            history.countDown()
            awaitAdoptions(3)
            compose.waitForIdle()

            assertWithMessage(
                "fresh idle: adoptions=${adoptions.get()} resumes=${gateway.count("session.resume")} " +
                    "submits=${gateway.count("prompt.submit")} loading=${chat.state.value.loading} " +
                    "running=${chat.state.value.running} stops=${stops()}",
            ).that(chat.state.value.running).isFalse()
            assertThat(stops()).isEqualTo(0)
            // Neither the failed send nor the fresh idle answer submitted anything again.
            assertThat(gateway.count("prompt.submit")).isEqualTo(1)
            val first = chat.state.value.items.filterIsInstance<ChatItem.User>().single { it.text == "first" }
            assertThat(first.delivery).isEqualTo(Delivery.FAILED)
            val second = chat.state.value.items.filterIsInstance<ChatItem.User>().first { it.text == "second" }
            assertThat(second.delivery).isEqualTo(Delivery.SENT)
        } finally {
            history.countDown()
            server.historyGate = null
            chat.close()
        }
    }

    @Test
    fun `a reconnect while the server runs keeps the turn with the numeric running flag of the gateway`() = runBlocking<Unit> {
        gateway.connect()
        val chat = chat()
        try {
            compose.setContent { HermesTheme { Screen(chat, ChatDraft()) } }
            compose.waitUntil(5_000) { !chat.state.value.loading && chat.joinedTransport != 0L }
            awaitAdoptions(1)

            // A real send and a started turn: the chat knows the turn is running from its own events.
            chat.send("first", emptyList())
            compose.waitUntil(5_000) { gateway.count("prompt.submit") == 1 }
            gateway.start("first")
            gateway.delta("partial answer")
            compose.waitUntil(5_000) {
                chat.state.value.running && (chat.state.value.items.lastOrNull() as? ChatItem.Assistant)?.streaming == true
            }

            // The connection is lost while the turn still runs on the server. Its answer is not sent
            // to the chat; only the fresh resume can say the turn is still there.
            gateway.drop()
            server.historyGate = history
            gateway.connect()
            compose.waitUntil(5_000) { gateway.count("session.resume") >= 2 }
            history.countDown()
            awaitAdoptions(2)
            compose.waitForIdle()

            // The gateway reports `running` as 1, not as a boolean (docs/fixtures/socket/resume.json):
            // the fresh answer must still keep the turn, its Stop and its inflight words.
            assertWithMessage(
                "resumed: adoptions=${adoptions.get()} resumes=${gateway.count("session.resume")} " +
                    "submits=${gateway.count("prompt.submit")} loading=${chat.state.value.loading} " +
                    "running=${chat.state.value.running} stops=${stops()}",
            ).that(chat.state.value.running).isTrue()
            assertThat(stops()).isEqualTo(1)
            assertThat(gateway.count("prompt.submit")).isEqualTo(1)
            val answer = chat.state.value.items.filterIsInstance<ChatItem.Assistant>().last()
            assertThat(answer.streaming).isTrue()
            assertThat(answer.text).isEqualTo("partial answer")
        } finally {
            history.countDown()
            server.historyGate = null
            chat.close()
        }
    }

    @Test
    fun `the fixture teardown releases a load blocked in the live list and closes the database after it`() = runBlocking<Unit> {
        gateway.connect()
        val active = CountDownLatch(1)
        gateway.activeListGate = active
        val chat = chat()
        var tornDown = false
        try {
            compose.setContent { HermesTheme { Screen(chat, ChatDraft()) } }
            compose.waitUntil(5_000) { gateway.count("session.active_list") == 1 }

            // The method `@After` calls, on this fixture's own scope and database: the load is
            // blocked inside the gateway, and this call must release the gate before joining.
            tearDown()
            tornDown = true

            assertWithMessage("the teardown released the gate the load waited on")
                .that(active.count).isEqualTo(0L)
            assertThat(db.isOpen).isFalse()
        } finally {
            active.countDown()
            if (!tornDown) {
                // The expected failure left the scope cancelling: finish it here so no blocked
                // worker or open database outlives the test.
                gateway.activeListGate = null
                withTimeoutOrNull(5_000) { (scope.coroutineContext[Job] as? Job)?.cancelAndJoin() }
                if (db.isOpen) db.close()
            }
        }
    }
}
