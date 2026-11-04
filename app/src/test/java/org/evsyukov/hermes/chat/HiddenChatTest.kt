package org.evsyukov.hermes.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.remote.HermesJson
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A chat that is not on the screen keeps every event of its turn on the phone: the words and the
 * reasoning go to a buffer per piece of the answer instead of rebuilding the transcript for each delta,
 * nothing is published for the screen, and shown again the chat publishes its state once without
 * asking the server anything. The gateway is a fake, so no test waits for a socket.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class HiddenChatTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = LiveServer.database(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = LiveGateway()
    private val server = LiveServer(gateway)
    private val strings = AppStrings(context.resources)

    /** What the chat told the read mark, in order. */
    private val reads = ConcurrentLinkedQueue<Pair<String, Double>>()

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    private fun chat(visible: Boolean = true) =
        ChatController(server.session(context, scope, db), gateway, scope, strings, "s1", onRead = { id, at -> reads += id to at }, visible = visible)

    private fun answer(state: ChatState) = state.items.lastOrNull() as? ChatItem.Assistant

    private suspend fun ChatController.until(timeoutMillis: Long = 5_000, condition: (ChatState) -> Boolean): ChatState =
        withTimeout(timeoutMillis) { state.first(condition) }

    /** A chat opened on a turn under way: "Hello" streamed before it opened, " there" while it is on the screen. */
    private suspend fun openMidTurn(): ChatController {
        gateway.connect()
        gateway.start("hi")
        gateway.delta("Hello")
        val chat = chat()
        chat.until { answer(it)?.text == "Hello" && !it.loading }
        gateway.delta(" there")
        chat.until { answer(it)?.text == "Hello there" }
        // Opening the chat read it; what follows is about the answers that come after.
        withTimeout(5_000) { while (reads.isEmpty()) delay(10) }
        reads.clear()
        return chat
    }

    /** Every state the chat publishes from now on, as it publishes it. */
    private fun ChatController.publications(): MutableList<ChatState> {
        val seen = Collections.synchronizedList(mutableListOf<ChatState>())
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { state.drop(1).collect { seen += it } }
        return seen
    }

    /** Items without keys and times, to compare what two chats show. */
    private fun shape(items: List<ChatItem>) = items.map { item ->
        when (item) {
            is ChatItem.User -> "user:${item.text}"
            is ChatItem.CronReport -> "cron:${item.text}"
            is ChatItem.Assistant -> "assistant:${item.streaming}:${item.error}:" + item.parts.joinToString("|") { part ->
                when (part) {
                    is Part.Text -> "text=${part.text}"
                    is Part.Reasoning -> "reasoning=${part.text}"
                    is Part.Tool -> "tool=${part.run.id}:${part.run.done}"
                }
            }
        }
    }

    @Test
    fun `2000 hidden deltas rebuild nothing and publish nothing, and the return publishes all of them once`() = runBlocking<Unit> {
        val chat = openMidTurn()
        chat.setVisible(false)
        val published = chat.state.value
        val working = chat.working.items
        val calls = gateway.calls.size
        val requests = server.http.requestCount

        repeat(2_000) { gateway.delta(" w$it") }
        gateway.deliver()

        // Neither the screen's copy nor the chat's own transcript was rebuilt for a delta.
        assertThat(chat.state.value).isSameInstanceAs(published)
        assertThat(chat.working.items).isSameInstanceAs(working)

        val seen = chat.publications()
        chat.setVisible(true)

        assertThat(seen).hasSize(1)
        assertThat(answer(seen.single())!!.text).isEqualTo("Hello there" + (0 until 2_000).joinToString("") { " w$it" })
        assertThat(answer(seen.single())!!.streaming).isTrue()
        // The return asked the server nothing; the turn goes on streaming once.
        assertThat(gateway.calls.size).isEqualTo(calls)
        assertThat(server.http.requestCount).isEqualTo(requests)
        gateway.delta("!")
        assertThat(answer(chat.until { answer(it)?.text?.endsWith("!") == true })!!.text).endsWith(" w1999!")
    }

    @Test
    fun `turns that run while hidden end up as the chat on the screen shows them`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("hi")
        gateway.delta("Hello")
        val shown = chat()
        val hidden = chat()
        shown.until { answer(it)?.text == "Hello" && !it.loading }
        hidden.until { answer(it)?.text == "Hello" && !it.loading }
        hidden.setVisible(false)

        // The open turn: reasoning, words and a tool between them, then its end.
        repeat(50) { gateway.reasoning("r$it ") }
        repeat(50) { gateway.delta(" a$it") }
        gateway.tool("t1", "terminal")
        repeat(50) { gateway.reasoning("s$it ") }
        repeat(50) { gateway.delta(" b$it") }
        gateway.complete()
        // A turn longer than the gateway's replay ring, with tools inside it.
        gateway.start("long")
        repeat(LiveGateway.RING) { gateway.delta(" c$it") }
        gateway.tool("t2", "terminal")
        gateway.persistRound()
        repeat(LiveGateway.RING) { gateway.delta(" d$it") }
        gateway.tool("t3", "terminal")
        gateway.complete()
        // A third turn, still running.
        gateway.start("more")
        repeat(100) { gateway.delta(" e$it") }
        gateway.deliver()
        // The usage of the finished turns is read as each ends, as on the screen; then everything is quiet.
        delay(300)
        val calls = gateway.calls.size
        val requests = server.http.requestCount

        hidden.setVisible(true)

        assertThat(gateway.calls.size).isEqualTo(calls)
        assertThat(server.http.requestCount).isEqualTo(requests)
        assertThat(shape(hidden.state.value.items)).isEqualTo(shape(shown.state.value.items))
        val answers = hidden.state.value.items.filterIsInstance<ChatItem.Assistant>()
        assertThat(answers).hasSize(3)
        val long = answers[1].parts
        assertThat(long.filterIsInstance<Part.Tool>().map { it.run.id }).containsExactly("t2", "t3").inOrder()
        assertThat(long.filterIsInstance<Part.Text>().joinToString("") { it.text })
            .isEqualTo((0 until LiveGateway.RING).joinToString("") { " c$it" } + (0 until LiveGateway.RING).joinToString("") { " d$it" })
        assertThat(answers[0].parts.filterIsInstance<Part.Reasoning>().map { it.text })
            .containsExactly((0 until 50).joinToString("") { "r$it " }, (0 until 50).joinToString("") { "s$it " }).inOrder()
        assertThat(answers[2].streaming).isTrue()
        assertThat(hidden.state.value.running).isTrue()
    }

    @Test
    fun `a turn that ended while hidden is whole on return, and only then is read`() = runBlocking<Unit> {
        val chat = openMidTurn()
        chat.setVisible(false)
        val published = chat.state.value

        gateway.delta(" done")
        gateway.complete()
        gateway.deliver()
        // The chat knows the turn is over; the screen's copy does not, and nothing was read.
        withTimeout(5_000) { while (chat.working.running) delay(10) }
        delay(300)
        assertThat(chat.state.value).isSameInstanceAs(published)
        assertThat(reads).isEmpty()

        chat.setVisible(true)
        val back = chat.state.value
        withTimeout(5_000) { while (reads.isEmpty()) delay(10) }

        assertThat(back.running).isFalse()
        assertThat(answer(back)!!.text).isEqualTo("Hello there done")
        assertThat(answer(back)!!.streaming).isFalse()
        assertThat(back.items.count { it is ChatItem.Assistant }).isEqualTo(1)
        assertThat(reads.last()).isEqualTo("s1" to gateway.lastActive)
    }

    @Test
    fun `an error of a hidden turn is there on return, whole`() = runBlocking<Unit> {
        val chat = openMidTurn()
        chat.setVisible(false)
        val message = "quota exceeded: " + "x".repeat(2_000)

        gateway.delta(" and")
        gateway.fail(message)
        gateway.deliver()
        chat.setVisible(true)
        val back = chat.state.value

        assertThat(back.error).isEqualTo(message)
        assertThat(back.running).isFalse()
        assertThat(answer(back)!!.text).isEqualTo("Hello there and")
    }

    @Test
    fun `questions asked and cancelled while hidden stand on return as the socket left them`() = runBlocking<Unit> {
        val chat = openMidTurn()
        chat.setVisible(false)
        val published = chat.state.value

        gateway.ask("r1", "clarify", questions("Which file?"))
        gateway.deliverRequests()
        gateway.event("request.cancel", buildJsonObject { put("id", "r1") })
        gateway.deliver()
        gateway.ask("r2", "clarify", questions("Which branch?"))
        gateway.deliverRequests()
        gateway.delta(" waiting")
        gateway.deliver()

        // The chat holds r2 open and r1 closed while hidden; the screen's copy is unchanged.
        assertThat(chat.working.questions.map { it.id to it.state }).containsExactly("r1" to QuestionState.CLOSED, "r2" to QuestionState.PENDING).inOrder()
        assertThat(chat.state.value).isSameInstanceAs(published)
        chat.setVisible(true)
        val back = chat.state.value

        assertThat(back.questions.single { it.state == QuestionState.PENDING }.questions.single().text).isEqualTo("Which branch?")
        assertThat(answer(back)!!.text).isEqualTo("Hello there waiting")
    }

    @Test
    fun `a card opened while hidden is there on return`() = runBlocking<Unit> {
        val chat = openMidTurn()
        chat.setVisible(false)

        gateway.event("connection.request", HermesJson.parseToJsonElement(CARD).jsonObject)
        gateway.deliver()
        chat.setVisible(true)

        assertThat(chat.state.value.connections.single().opId).isEqualTo("op9")
    }

    @Test
    fun `stopping a hidden turn keeps every word it streamed`() = runBlocking<Unit> {
        val chat = openMidTurn()
        chat.setVisible(false)
        repeat(100) { gateway.delta(" s$it") }
        gateway.deliver()

        chat.stop()
        withTimeout(5_000) { while (chat.working.running) delay(10) }
        chat.setVisible(true)
        val stopped = answer(chat.state.value)!!

        assertThat(stopped.interrupted).isTrue()
        assertThat(stopped.text).isEqualTo("Hello there" + (0 until 100).joinToString("") { " s$it" })
    }

    @Test
    fun `a reconnect while hidden joins again and converges, and the return asks nothing more`() = runBlocking<Unit> {
        val chat = openMidTurn()
        chat.setVisible(false)
        val published = chat.state.value

        gateway.drop()
        gateway.lostDelta(" lost")
        gateway.connect()
        // The session is joined at once, so its events keep coming to the app while it is hidden; the
        // join is applied once its answer's turn is in the transcript.
        withTimeout(5_000) { while (gateway.count("session.resume") < 2 || answer(chat.working)?.text != "Hello there lost") delay(10) }
        gateway.delta(" after")
        gateway.ask("r1", "clarify", questions("Go on?"))
        gateway.deliverRequests()
        gateway.deliver()
        delay(300)
        assertThat(reads).isEmpty()
        assertThat(chat.state.value).isSameInstanceAs(published)
        val calls = gateway.calls.size

        chat.setVisible(true)
        val back = chat.state.value
        withTimeout(5_000) { while (reads.isEmpty()) delay(10) }

        assertThat(answer(back)!!.text).isEqualTo("Hello there lost after")
        assertThat(back.items.count { it is ChatItem.Assistant }).isEqualTo(1)
        assertThat(back.questions.single().id).isEqualTo("r1")
        assertThat(gateway.calls.size).isEqualTo(calls)
    }

    @Test
    fun `a chat shown in the middle of its load gets the rest of it on the screen`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("hi")
        gateway.delta("Hel")
        server.historyDelayMillis = 500
        // Opened behind a screen, as the app does after a cold start from a notification.
        val chat = chat(visible = false)
        withTimeout(5_000) { while (gateway.count("session.resume") < 1) delay(10) }
        assertThat(chat.state.value.items).isEmpty()

        chat.setVisible(true)
        chat.until { answer(it)?.text == "Hel" && !it.loading }
        gateway.delta("lo")
        val back = chat.until { answer(it)?.text == "Hello" }

        assertThat(back.items.count { it is ChatItem.Assistant }).isEqualTo(1)
    }

    @Test
    fun `a chat hidden in the middle of its load keeps the screen as it was and shows the end on return`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("hi")
        gateway.delta("Hel")
        server.historyDelayMillis = 500
        val chat = chat()
        chat.until { it.loading }

        chat.setVisible(false)
        val published = chat.state.value
        withTimeout(5_000) { while (answer(chat.working)?.text != "Hel") delay(10) }
        gateway.delta("lo")
        gateway.deliver()
        assertThat(chat.state.value).isSameInstanceAs(published)
        val calls = gateway.calls.size

        chat.setVisible(true)
        val back = chat.state.value

        assertThat(back.loading).isFalse()
        assertThat(answer(back)!!.text).isEqualTo("Hello")
        assertThat(gateway.calls.size).isEqualTo(calls)
    }

    @Test
    fun `a chat hidden and shown with nothing new does no work`() = runBlocking<Unit> {
        val chat = openMidTurn()
        val before = chat.state.value
        val calls = gateway.calls.size
        val requests = server.http.requestCount
        val seen = chat.publications()

        chat.setVisible(false)
        chat.setVisible(true)
        delay(300)

        assertThat(chat.state.value).isSameInstanceAs(before)
        assertThat(seen).isEmpty()
        assertThat(gateway.calls.size).isEqualTo(calls)
        assertThat(server.http.requestCount).isEqualTo(requests)
    }
}

private const val CARD = """{"op_id":"op9","seq":1,"deadline_at":1800000000,"timeout_seconds":600,"tool_call_id":"call_9","targets":[
    {"name":"gmail","kind":"connector","action":"connect","state":"initiated","connect_url":"https://connect.example/gmail"}]}"""
