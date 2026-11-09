package org.evsyukov.hermes.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.remote.HermesJson
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A question of the agent is a card of the chat, not a prompt that something can dismiss: it waits
 * until the user answers or cancels it on the card, or the server withdraws it, and every answer goes
 * once, for the request it belongs to. A reconnect brings back what the server still holds open and
 * closes what it no longer does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class QuestionChatTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = LiveServer.database(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = LiveGateway()
    private val server = LiveServer(gateway)

    @After
    fun tearDown() {
        val owner = scope.coroutineContext.job
        server.finish(owner, listOf("close server" to { server.close() }, "close database" to { db.close() }))
    }

    /** The questions of this connection, as the app keeps them for every chat it opens on it. */
    private val memory = QuestionMemory()

    /** A chat as the app opens it: a new controller each time, over the memory of the connection. */
    private fun chat(id: String = "s1", channel: org.evsyukov.hermes.data.remote.GatewayChannel = gateway, own: CoroutineScope = scope) = ChatController(server.session(context, own, db), channel, own, AppStrings(context.resources), id, questionMemory = memory)

    private suspend fun ChatController.until(condition: (ChatState) -> Boolean): ChatState =
        withTimeout(5_000) { state.first(condition) }

    /** A chat opened on a turn that runs the clarify tool `t1`. */
    private suspend fun opened(): ChatController {
        gateway.connect()
        gateway.start("hi")
        gateway.delta("Let me ask.")
        val chat = chat()
        chat.until { !it.loading && (it.items.lastOrNull() as? ChatItem.Assistant)?.streaming == true }
        gateway.event("tool.start", buildJsonObject { put("tool_id", "t1"); put("name", "clarify") })
        chat.until { state -> (state.items.last() as ChatItem.Assistant).parts.any { it is Part.Tool && it.run.id == "t1" } }
        return chat
    }

    private suspend fun ChatController.asked(id: String, vararg text: String): QuestionCard {
        gateway.ask(id, "clarify", questions(*text))
        return until { state -> state.questions.any { it.id == id } }.questions.single { it.id == id }
    }

    private fun card(chat: ChatController, id: String) = chat.state.value.questions.single { it.id == id }

    private fun json(text: String) = HermesJson.parseToJsonElement(text)

    @Test
    fun `a question stands in the chat as a pending card under the answer that asked it`() = runBlocking<Unit> {
        val chat = opened()

        val card = chat.asked("r1", "Which file?", "Which branch?")

        assertThat(card.state).isEqualTo(QuestionState.PENDING)
        assertThat(card.questions.map { it.text }).containsExactly("Which file?", "Which branch?").inOrder()
        assertThat(card.toolCallId).isEqualTo("t1")
        assertThat(chat.state.value.prompt).isNull()
    }

    @Test
    fun `an answer goes once, for its own request, and the card keeps what was answered`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Which file?", "Which branch?")

        chat.answerQuestion("r1", mapOf("q0" to "notes.md", "q1" to "  "))
        chat.answerQuestion("r1", mapOf("q0" to "other.md"))

        assertThat(gateway.responses).containsExactly(
            LiveGateway.Response("r1", json("""{"answers":{"q0":"notes.md","q1":null},"outcome":"submitted"}"""), null),
        )
        val card = chat.until { s -> s.questions.single().state == QuestionState.SUBMITTED }.questions.single()
        assertThat(card.answers).containsExactly("q0", "notes.md", "q1", null)
    }

    @Test
    fun `cancelling takes the explicit call, which sends one cancellation`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Which file?")

        // What the screen does in ordinary use: go away, come back, read the chat again.
        chat.setVisible(false)
        chat.setVisible(true)
        chat.reload()
        withTimeout(5_000) { while (gateway.count("session.resume") < 1) delay(10) }
        delay(200)
        assertThat(gateway.responses).isEmpty()
        assertThat(card(chat, "r1").state).isEqualTo(QuestionState.PENDING)

        chat.cancelQuestion("r1")
        chat.cancelQuestion("r1")
        chat.answerQuestion("r1", mapOf("q0" to "late"))

        assertThat(gateway.responses).containsExactly(LiveGateway.Response("r1", json("""{"outcome":"cancelled"}"""), null))
        assertThat(chat.until { s -> s.questions.single().state == QuestionState.CANCELLED }.questions.single().answers).isEmpty()
    }

    @Test
    fun `a callback of a closed card answers neither it nor the question after it`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Which file?")
        gateway.withdraw("r1", "timeout")
        chat.until { s -> s.questions.single().state == QuestionState.EXPIRED }
        chat.asked("r2", "Which branch?")

        chat.answerQuestion("r1", mapOf("q0" to "stale"))
        chat.cancelQuestion("r1")

        assertThat(gateway.responses).isEmpty()
        assertThat(card(chat, "r2").state).isEqualTo(QuestionState.PENDING)
    }

    @Test
    fun `the server expiring or withdrawing a question closes its card`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "One?")
        chat.asked("r2", "Two?")
        chat.asked("r3", "Three?")

        gateway.withdraw("r1", "timeout")
        gateway.withdraw("r2", "interrupted")
        gateway.withdraw("r3", "resolved")

        val states = chat.until { s -> s.questions.none { it.state == QuestionState.PENDING } }.questions.associate { it.id to it.state }
        assertThat(states).containsExactly("r1", QuestionState.EXPIRED, "r2", QuestionState.CLOSED, "r3", QuestionState.CLOSED)
        assertThat(gateway.responses).isEmpty()
    }

    @Test
    fun `a question asked again on the connection that asked it stays one card`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Which file?")
        gateway.ask("r1", "clarify", questions("Which file?"))
        gateway.deliverRequests()
        assertThat(chat.state.value.questions).hasSize(1)

        chat.answerQuestion("r1", mapOf("q0" to "a.md"))
        gateway.ask("r1", "clarify", questions("Which file?"))
        gateway.deliverRequests()

        assertThat(chat.state.value.questions.single().state).isEqualTo(QuestionState.SUBMITTED)
        assertThat(gateway.responses).hasSize(1)
    }

    @Test
    fun `a reconnect brings back an open question once and closes the one the server dropped`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Still open?")
        chat.asked("r2", "Answered elsewhere?")

        gateway.drop()
        gateway.answeredElsewhere("r2")
        gateway.connect()
        withTimeout(5_000) { while (gateway.count("session.resume") < 2) delay(10) }

        val back = chat.until { s -> s.questions.any { it.state == QuestionState.CLOSED } }
        assertThat(back.questions.map { it.id to it.state }).containsExactly("r1" to QuestionState.PENDING, "r2" to QuestionState.CLOSED).inOrder()
        assertThat(gateway.responses).isEmpty()
    }

    @Test
    fun `an answer lost with its connection is asked again with the words it had`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Which file?")
        gateway.losesResponses = true
        chat.answerQuestion("r1", mapOf("q0" to "notes.md"))
        chat.until { s -> s.questions.single().state == QuestionState.SUBMITTED }

        gateway.drop()
        gateway.connect()

        val again = chat.until { s -> s.questions.single().state == QuestionState.PENDING }.questions.single()
        assertThat(again.prefilled).containsExactly("q0", "notes.md")
        chat.answerQuestion("r1", mapOf("q0" to "notes.md"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r1", "r1")
    }

    @Test
    fun `an answer lost on a closed chat is asked again by the first join of the connection that replaced it`() = runBlocking<Unit> {
        val first = opened()
        first.asked("r1", "Which file?")
        gateway.losesResponses = true
        first.answerQuestion("r1", mapOf("q0" to "notes.md"))
        first.until { s -> s.questions.single().state == QuestionState.SUBMITTED }
        first.close()

        gateway.drop()
        // The new connection is READY and carries the join; the gateway counts it only later.
        gateway.establish()
        val again = chat()

        // The join lists r1 still open, and its answer came over a connection later than the one the
        // answer went over: the card opens again before the new connection is announced.
        settle(5_000) { !again.working.loading && again.working.questions.singleOrNull()?.state == QuestionState.PENDING }
        val back = again.working.questions.single()
        assertThat(back.state).isEqualTo(QuestionState.PENDING)
        assertThat(back.prefilled).containsExactly("q0", "notes.md")

        gateway.announce()
        settle(2_000) { !again.working.loading }
        assertThat(again.working.questions.single().state).isEqualTo(QuestionState.PENDING)

        // The request the server still waits for is answered once on the connection that confirmed it.
        gateway.losesResponses = false
        again.answerQuestion("r1", mapOf("q0" to "notes.md"))
        again.answerQuestion("r1", mapOf("q0" to "again"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r1", "r1")
        assertThat(again.working.questions.single().state).isEqualTo(QuestionState.SUBMITTED)
    }

    @Test
    fun `a connection that replaces the answer's own while its count is read is joined again`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("hi")
        gateway.ask("r1", "clarify", questions("Which file?"))
        // The socket takes a new connection and is counted while the chat books the count of the join
        // it has just received; no collector has heard of the replacement yet.
        gateway.afterResume = {
            gateway.afterResume = null
            gateway.replaceOnNextCount()
        }
        val chat = chat()

        settle(5_000) { gateway.count("session.resume") >= 2 && !chat.working.loading }
        gateway.publishReplacement()
        delay(200)

        assertThat(gateway.resumedOn.toList()).containsExactly(1, 2).inOrder()
        assertThat(chat.joinedTransport).isEqualTo(gateway.transport.value)
        val card = chat.state.value.questions.single()
        assertThat(card.state).isEqualTo(QuestionState.PENDING)
        assertThat(card.confirmed).isTrue()
        chat.answerQuestion("r1", mapOf("q0" to "notes.md"))
        chat.answerQuestion("r1", mapOf("q0" to "again"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r1")
    }

    @Test
    fun `a stored chat whose join is replaced before the first count is joined again although the count matches`() = runBlocking<Unit> {
        gateway.start("hi")
        // A is READY but the gateway has never counted it: the chat opens with no count behind it.
        gateway.establish()
        val first = chat()
        gateway.ask("r1", "clarify", questions("Which file?"))
        first.until { s -> s.questions.singleOrNull()?.confirmed == true }
        first.close()

        // A load whose count of connections is 0 falls back to the count after its resume answers. B
        // replaces A and is counted as 1 during that read, so the fallback finds a count of B's.
        val secondJoin = CompletableDeferred<Unit>()
        var resumes = 0
        gateway.afterResume = {
            when (++resumes) {
                1 -> gateway.replaceOnNextCount()
                2 -> secondJoin.await()
            }
        }
        val second = chat()

        settle(5_000) { gateway.count("session.resume") >= 3 }
        // The join the chat holds came over A and is retired; r1 comes back only with B's join.
        assertThat(second.working.questions.single().confirmed).isFalse()
        second.answerQuestion("r1", mapOf("q0" to "stale"))
        assertThat(gateway.responses).isEmpty()

        gateway.publishReplacement()
        secondJoin.complete(Unit)
        settle(5_000) { !second.working.loading && second.working.questions.singleOrNull()?.confirmed == true }

        assertThat(gateway.resumedOn.toList()).containsExactly(0, 0, 1).inOrder()
        assertThat(second.joinedTransport).isEqualTo(gateway.transport.value)
        second.answerQuestion("r1", mapOf("q0" to "notes.md"))
        second.answerQuestion("r1", mapOf("q0" to "again"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r1")
    }

    @Test
    fun `a reconnect starts the card with the answers the server already locked`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("hi")
        val params = JsonObject(questions("One?", "Two?") + ("answers" to json("""{"q0":"yes"}""")))
        gateway.ask("r1", "clarify", params)
        val chat = chat()

        val card = chat.until { it.questions.isNotEmpty() }.questions.single()
        assertThat(card.prefilled).containsExactly("q0", "yes")
        assertThat(card.state).isEqualTo(QuestionState.PENDING)
    }

    @Test
    fun `an answer without a connection is not sent and the card stays open`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Which file?")
        gateway.drop()

        chat.answerQuestion("r1", mapOf("q0" to "notes.md"))
        chat.cancelQuestion("r1")

        assertThat(gateway.responses).isEmpty()
        val state = chat.until { it.error != null }
        assertThat(state.questions.single().state).isEqualTo(QuestionState.PENDING)
    }

    @Test
    fun `the answered card stays through a reload of the history`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Which file?")
        chat.answerQuestion("r1", mapOf("q0" to "notes.md"))
        gateway.persistRound()
        val reads = server.http.requestCount

        chat.reload()
        withTimeout(5_000) { while (server.http.requestCount < reads + 2) delay(10) }
        val after = chat.until { !it.loading }

        assertThat(after.questions.single().state).isEqualTo(QuestionState.SUBMITTED)
        assertThat(after.questions.single().answers).containsExactly("q0", "notes.md")
    }

    /** Drops the connection, delays the history of the join that follows and withdraws [id] once that join is answered. */
    private suspend fun timeoutWhileTheRejoinReadsHistory(id: String) {
        server.historyDelayMillis = 1_500
        val resumes = gateway.count("session.resume")
        gateway.drop()
        gateway.connect()
        withTimeout(5_000) { while (gateway.count("session.resume") <= resumes) delay(10) }
        // The answer of the join, with the request still open in it, waits for the history meanwhile.
        delay(100)
        gateway.withdraw(id, "timeout")
    }

    @Test
    fun `a timeout while a rejoin waits for its history keeps the question expired`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Which file?")

        timeoutWhileTheRejoinReadsHistory("r1")
        chat.until { s -> s.questions.single().state == QuestionState.EXPIRED }
        withTimeout(10_000) { while (chat.working.loading || gateway.count("session.resume") < 2) delay(10) }
        delay(200)

        assertThat(chat.state.value.questions.single().state).isEqualTo(QuestionState.EXPIRED)
        chat.answerQuestion("r1", mapOf("q0" to "late"))
        assertThat(gateway.responses).isEmpty()
    }

    @Test
    fun `a question the server ended before the chat showed it never takes an answer, even when it comes again`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("hi")
        // Asked while no chat was open: only the join of the chat lists it.
        gateway.ask("r1", "clarify", questions("Which file?"))
        server.historyDelayMillis = 1_500
        val chat = chat()
        withTimeout(5_000) { while (gateway.count("session.resume") < 1) delay(10) }
        delay(100)
        gateway.withdraw("r1", "timeout")
        withTimeout(10_000) { while (chat.working.loading || chat.working.questions.isEmpty()) delay(10) }
        delay(200)

        assertThat(chat.state.value.questions.map { it.id to it.state }).containsExactly("r1" to QuestionState.EXPIRED)
        // The same request again, live or with a later join, is still the one the server ended.
        gateway.ask("r1", "clarify", questions("Which file?"))
        gateway.deliverRequests()
        gateway.drop()
        gateway.connect()
        withTimeout(10_000) { while (gateway.count("session.resume") < 2 || chat.working.loading) delay(10) }
        delay(200)
        assertThat(chat.state.value.questions.map { it.id to it.state }).containsExactly("r1" to QuestionState.EXPIRED)
        chat.answerQuestion("r1", mapOf("q0" to "late"))
        assertThat(gateway.responses).isEmpty()
    }

    @Test
    fun `a chat opened again after another one has its questions, outcomes and written answers as they were`() = runBlocking<Unit> {
        val first = opened()
        first.asked("r1", "Which file?")
        first.answerQuestion("r1", mapOf("q0" to "notes.md"))
        first.asked("r2", "Which branch?")
        first.answersOf(first.state.value.questions.single { it.id == "r2" })["q0"] = "feature/x"
        first.close()

        // Another chat of the same connection shows none of them.
        val other = chat("s2")
        delay(300)
        assertThat(other.state.value.questions).isEmpty()
        other.close()

        val again = chat()
        val back = again.until { s -> !s.loading && s.questions.size == 2 }
        assertThat(back.questions.map { it.id to it.state }).containsExactly("r1" to QuestionState.SUBMITTED, "r2" to QuestionState.PENDING).inOrder()
        assertThat(back.questions.first().answers).containsExactly("q0", "notes.md")
        assertThat(again.answersOf(back.questions.last())).containsExactly("q0", "feature/x")
        withTimeout(5_000) { while (gateway.count("session.resume") < 2) delay(10) }
        delay(200)
        assertThat(again.state.value.questions.map { it.state }).containsExactly(QuestionState.SUBMITTED, QuestionState.PENDING).inOrder()
        assertThat(gateway.responses.map { it.id }).containsExactly("r1")
    }

    /** Waits until [chat] has joined and put its history and the open requests of the join on the screen. */
    private suspend fun joined(chat: ChatController, resumes: Int) {
        withTimeout(10_000) { while (gateway.count("session.resume") < resumes || chat.working.loading) delay(10) }
        delay(200)
    }

    @Test
    fun `questions that ended while another chat was open close when their chat opens again on the same connection`() = runBlocking<Unit> {
        val first = opened()
        first.asked("r1", "Which file?")
        first.asked("r2", "Which branch?")
        first.answersOf(first.state.value.questions.first())["q0"] = "notes.md"
        first.close()
        // While the chat is closed the server stops waiting: one at its deadline, one answered elsewhere.
        gateway.withdraw("r1", "timeout")
        gateway.answeredElsewhere("r2")
        chat("s2").close()

        val again = chat()
        joined(again, resumes = 2)

        assertThat(again.state.value.questions.map { it.id to it.state }).containsExactly("r1" to QuestionState.CLOSED, "r2" to QuestionState.CLOSED).inOrder()
        assertThat(again.answersOf(again.state.value.questions.first())).containsExactly("q0", "notes.md")
        first.answerQuestion("r1", mapOf("q0" to "notes.md"))
        first.cancelQuestion("r2")
        again.answerQuestion("r1", mapOf("q0" to "notes.md"))
        assertThat(gateway.responses).isEmpty()
    }

    @Test
    fun `a question kept from an earlier visit takes no answer until the join of its chat confirms it`() = runBlocking<Unit> {
        val first = opened()
        first.asked("r1", "Which file?")
        first.close()
        server.historyDelayMillis = 1_500

        val again = chat()
        val restored = again.state.value.questions.single()
        again.answerQuestion("r1", mapOf("q0" to "notes.md"))
        again.cancelQuestion("r1")

        assertThat(restored.state).isEqualTo(QuestionState.PENDING)
        assertThat(restored.confirmed).isFalse()
        assertThat(gateway.responses).isEmpty()
        val confirmed = again.until { s -> s.questions.single().confirmed }
        assertThat(confirmed.questions.single().state).isEqualTo(QuestionState.PENDING)
        again.answerQuestion("r1", mapOf("q0" to "notes.md"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r1")
    }

    @Test
    fun `a new connection takes the answer off an open question until the join confirms it again`() = runBlocking<Unit> {
        val chat = opened()
        assertThat(chat.asked("r1", "Which file?").confirmed).isTrue()
        server.historyDelayMillis = 1_500

        gateway.drop()
        gateway.connect()
        withTimeout(5_000) { while (chat.working.questions.single().confirmed) delay(10) }
        chat.answerQuestion("r1", mapOf("q0" to "early"))
        assertThat(gateway.responses).isEmpty()

        chat.until { s -> s.questions.single().confirmed }
        chat.answerQuestion("r1", mapOf("q0" to "notes.md"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r1")
    }

    @Test
    fun `a withdrawal that comes while a rejoin is still waiting for its answer is not lost`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Which file?")
        // The join's answer lists r1, and the server lets r1 go before that answer reaches the chat.
        gateway.afterResume = { gateway.withdraw("r1", "timeout") }

        gateway.drop()
        gateway.connect()
        joined(chat, resumes = 2)

        assertThat(chat.state.value.questions.map { it.id to it.state }).containsExactly("r1" to QuestionState.EXPIRED)
        gateway.afterResume = null
        gateway.ask("r1", "clarify", questions("Which file?"))
        gateway.deliverRequests()
        assertThat(chat.state.value.questions.map { it.id to it.state }).containsExactly("r1" to QuestionState.EXPIRED)
        chat.answerQuestion("r1", mapOf("q0" to "late"))
        assertThat(gateway.responses).isEmpty()
    }

    @Test
    fun `a withdrawal while the first join is on its way ends a question the chat has not shown, and another session's does not`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("hi")
        gateway.ask("r1", "clarify", questions("Which file?"))
        gateway.ask("r2", "clarify", questions("Which branch?"))
        gateway.afterResume = {
            gateway.withdraw("r1", "timeout")
            // The same request id withdrawn in another live session of the dashboard is not this chat's.
            gateway.foreign("request.cancel", buildJsonObject { put("id", "r2"); put("method", "clarify"); put("reason", "timeout") })
            gateway.deliver()
        }

        val chat = chat()
        joined(chat, resumes = 1)

        assertThat(chat.state.value.questions.map { it.id to it.state }).containsExactly("r1" to QuestionState.EXPIRED, "r2" to QuestionState.PENDING).inOrder()
        assertThat(chat.state.value.questions.last().confirmed).isTrue()
        chat.answerQuestion("r1", mapOf("q0" to "late"))
        assertThat(gateway.responses).isEmpty()
    }

    @Test
    fun `a question asked while the join is on its way is not closed by the join's older answer`() = runBlocking<Unit> {
        val chat = opened()
        server.historyDelayMillis = 1_500
        // One question comes before the join answers, another once it has and the history is still on its way.
        gateway.afterResume = { gateway.ask("r2", "clarify", questions("Asked during the join?")) }

        gateway.drop()
        gateway.connect()
        withTimeout(5_000) { while (gateway.count("session.resume") < 2) delay(10) }
        delay(100)
        gateway.afterResume = null
        gateway.ask("r3", "clarify", questions("Asked before the history?"))
        gateway.ask("r3", "clarify", questions("Asked before the history?"))
        gateway.deliverRequests()
        joined(chat, resumes = 2)

        val questions = chat.state.value.questions
        assertThat(questions.map { it.id to it.state }).containsExactly("r2" to QuestionState.PENDING, "r3" to QuestionState.PENDING).inOrder()
        assertThat(questions.all { it.confirmed }).isTrue()
        assertThat(gateway.responses).isEmpty()
    }

    @Test
    fun `a kept question of a chat the gateway no longer hosts closes rather than waiting for a join`() = runBlocking<Unit> {
        val first = opened()
        first.asked("r1", "Which file?")
        first.close()
        gateway.complete()
        gateway.hosted = false

        val again = chat()

        assertThat(again.until { s -> s.questions.single().state == QuestionState.CLOSED }.questions.single().confirmed).isFalse()
        assertThat(gateway.count("session.resume")).isEqualTo(1)
        again.answerQuestion("r1", mapOf("q0" to "late"))
        assertThat(gateway.responses).isEmpty()
    }

    @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    private class HeldCountCollection(private val source: kotlinx.coroutines.flow.StateFlow<Int>, private val release: CompletableDeferred<Unit>) : kotlinx.coroutines.flow.StateFlow<Int> by source {
        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Int>): Nothing {
            release.await()
            source.collect(object : kotlinx.coroutines.flow.FlowCollector<Int> {
                override suspend fun emit(value: Int) {
                    println("QUESTION_COUNT_ENTER $value")
                    collector.emit(value)
                    println("QUESTION_COUNT_RETURN $value")
                }
            })
        }
    }

    @Test fun `a confirmed first card can precede completion of its actual history load`() = runBlocking<Unit> {
        gateway.start("hi"); gateway.establish()
        val history = server.holdHistory(); val summary = server.holdSummary()
        val own = CoroutineScope(SupervisorJob(scope.coroutineContext.job) + Dispatchers.IO + CoroutineName("held-first"))
        val first = chat(own = own)
        val bound = CompletableDeferred<Unit>(); first.afterLiveBound = { bound.complete(Unit) }
        var primary: Throwable? = null
        try {
            check(history.entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "First actual history did not enter" }
            summary.release.countDown(); withTimeout(5_000) { bound.await() }
            gateway.ask("r1", "clarify", questions("Which file?"))
            first.until { it.questions.singleOrNull()?.confirmed == true }
            val load = checkNotNull(first.loadCompletion)
            assertThat(load.isCompleted).isFalse()
            assertThat(gateway.resumedOn.toList()).containsExactly(0)
            history.release.countDown(); withTimeout(5_000) { load.await() }
            assertThat(first.state.value.questions.single().confirmed).isTrue()
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            history.release.countDown(); summary.release.countDown()
            var cleanup: Throwable? = null
            try { first.close() } catch (error: Throwable) { cleanup = error }
            try { withTimeout(5_000) { own.coroutineContext.job.cancelAndJoin() } }
            catch (error: Throwable) { val old = cleanup; if(old == null) cleanup = error else if(old !== error) old.addSuppressed(error) }
            cleanup?.let { error -> if(primary == null) throw error else if(primary !== error) primary.addSuppressed(error) }
        }
    }

    @Test fun `a delayed replacement count decision cannot invalidate an already settled replacement join`() = runBlocking<Unit> {
        val firstScope = CoroutineScope(SupervisorJob(scope.coroutineContext.job) + Dispatchers.IO + CoroutineName("first"))
        val secondScope = CoroutineScope(SupervisorJob(scope.coroutineContext.job) + Dispatchers.IO + CoroutineName("second"))
        val collection = CompletableDeferred<Unit>(); val secondJoin = CompletableDeferred<Unit>(); val joined = CompletableDeferred<Unit>()
        val decision = CompletableDeferred<Unit>(); val releaseDecision = CompletableDeferred<Unit>(); val decided = CompletableDeferred<Unit>()
        val controllers = mutableListOf<ChatController>()
        val trace = java.util.concurrent.ConcurrentLinkedQueue<String>()
        var primary: Throwable? = null
        try {
            gateway.start("hi"); gateway.establish()
            val first = chat(own = firstScope).also { controllers += it }
            gateway.ask("r1", "clarify", questions("Which file?"))
            first.until { it.questions.singleOrNull()?.confirmed == true }
            withTimeout(5_000) { checkNotNull(first.loadCompletion).await() }
            trace += "first-load-complete"
            first.close(); withTimeout(5_000) { firstScope.coroutineContext.job.cancelAndJoin() }
            trace += "first-root-joined"
            var resumes = 0
            gateway.afterResume = {
                trace += "resume:${kotlin.coroutines.coroutineContext[CoroutineName]?.name}:${gateway.resumedOn.toList()}:transport=${gateway.transport.value}"
                when (++resumes) { 1 -> gateway.replaceOnNextCount(); 2 -> { joined.complete(Unit); secondJoin.await() } }
            }
            val wire = object : org.evsyukov.hermes.data.remote.GatewayChannel by gateway {
                override val connections = HeldCountCollection(gateway.connections, collection)
            }
            val second = chat(channel = wire, own = secondScope).also { controllers += it }
            second.beforeReconnectEnqueue = { trace += "initial-count-decision-enter"; decision.complete(Unit); releaseDecision.await() }
            second.afterReconnectEnqueue = { trace += "initial-count-decision-return"; decided.complete(Unit) }
            withTimeout(5_000) { joined.await() }
            val replacementLoad = checkNotNull(second.loadCompletion)
            collection.complete(Unit); gateway.publishReplacement()
            withTimeout(5_000) { decision.await() }
            assertThat(second.working.questions.single().confirmed).isFalse()
            second.answerQuestion("r1", mapOf("q0" to "stale")); assertThat(gateway.responses).isEmpty()
            secondJoin.complete(Unit)
            withTimeout(5_000) { replacementLoad.await() }
            second.until { !it.loading && it.questions.singleOrNull()?.confirmed == true }
            trace += "second-replacement-load-complete:${second.joinedTransport}"
            val requests = second.acceptedReloadRequests.get()
            val extra = server.holdSummary()
            releaseDecision.complete(Unit); withTimeout(5_000) { decided.await() }
            val accepted = second.acceptedReloadRequests.get() - requests
            trace += "accepted-after-settled=$accepted"
            if (accepted > 0) {
                check(extra.entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Admitted diagnostic load did not enter actual HTTP" }
                val load = checkNotNull(second.loadCompletion)
                assertThat(load).isNotSameInstanceAs(replacementLoad)
                extra.release.countDown(); withTimeout(5_000) { load.await() }
                trace += "extra-load-complete"
            }
            // Original exact transport/count expectation and response assertions, not >= or duplicate tolerance.
            assertThat(gateway.resumedOn.toList()).containsExactly(0, 0, 1).inOrder()
            assertThat(second.joinedTransport).isEqualTo(gateway.transport.value)
            second.answerQuestion("r1", mapOf("q0" to "notes.md"))
            second.answerQuestion("r1", mapOf("q0" to "again"))
            assertThat(gateway.responses.map { it.id }).containsExactly("r1")
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            println("QUESTION_JOIN_TRACE " + trace.joinToString(" | "))
            collection.complete(Unit); secondJoin.complete(Unit); releaseDecision.complete(Unit); server.releaseGates(); gateway.afterResume = null
            var cleanup: Throwable? = null
            fun failed(error: Throwable) { val old = cleanup; if(old == null) cleanup = error else if(old !== error) old.addSuppressed(error) }
            controllers.forEach { try { it.close() } catch(error: Throwable) { failed(error) } }
            listOf(firstScope, secondScope).forEach { try { withTimeout(5_000) { it.coroutineContext.job.cancelAndJoin() } } catch(error: Throwable) { failed(error) } }
            cleanup?.let { error -> if(primary == null) throw error else if(primary !== error) primary.addSuppressed(error) }
        }
    }

    /** Waits up to [millis] for [condition] without failing: the checks that follow say what went wrong. */
    private suspend fun settle(millis: Long = 8_000, condition: () -> Boolean) {
        withTimeoutOrNull(millis) { while (!condition()) delay(20) }
        delay(200)
    }

    /**
     * Reconnects, and once the answer of that join has come and its history is still on its way, the
     * connection drops and comes back once more; [whileAway] runs while it is down.
     */
    private suspend fun secondReconnectWhileHistoryWaits(whileAway: () -> Unit = {}) {
        server.historyDelayMillis = 1_500
        gateway.drop()
        gateway.connect()
        withTimeout(5_000) { while (gateway.count("session.resume") < 2) delay(10) }
        delay(100)
        server.historyDelayMillis = 0
        gateway.drop()
        whileAway()
        gateway.connect()
    }

    @Test
    fun `a second reconnect while a rejoin waits for its history joins the newest connection, where a question that ended stays closed`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Which file?")

        secondReconnectWhileHistoryWaits { gateway.answeredElsewhere("r1") }
        settle { gateway.count("session.resume") >= 3 && !chat.working.loading && chat.working.questions.single().state != QuestionState.PENDING }

        assertThat(gateway.resumedOn.toList()).containsExactly(1, 2, 3).inOrder()
        assertThat(chat.joinedTransport).isEqualTo(gateway.transport.value)
        assertThat(chat.state.value.questions.single().state).isEqualTo(QuestionState.CLOSED)
        chat.answerQuestion("r1", mapOf("q0" to "late"))
        chat.cancelQuestion("r1")
        assertThat(gateway.responses).isEmpty()
    }

    @Test
    fun `a question still open across a second reconnect while a rejoin waits for its history is answered once on the newest join`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Which file?")

        secondReconnectWhileHistoryWaits()
        settle { gateway.count("session.resume") >= 3 && !chat.working.loading && chat.working.questions.single().confirmed }

        assertThat(gateway.resumedOn.toList()).containsExactly(1, 2, 3).inOrder()
        assertThat(chat.joinedTransport).isEqualTo(gateway.transport.value)
        val card = chat.state.value.questions.single()
        assertThat(card.state).isEqualTo(QuestionState.PENDING)
        assertThat(card.confirmed).isTrue()
        chat.answerQuestion("r1", mapOf("q0" to "notes.md"))
        chat.answerQuestion("r1", mapOf("q0" to "notes.md"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r1")
    }

    @Test
    fun `a connection that is ready but not yet announced takes no answer until its own join confirms the question`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Which file?")
        chat.setVisible(false)
        val published = chat.state.value

        gateway.drop()
        chat.answerQuestion("r1", mapOf("q0" to "offline"))
        // READY on a new connection, with `client.capabilities` still unanswered: the gateway is not counted yet.
        gateway.establish()
        chat.answerQuestion("r1", mapOf("q0" to "too early"))
        chat.cancelQuestion("r1")
        assertThat(gateway.responses).isEmpty()
        settle(2_000) { !chat.working.questions.single().confirmed }
        assertThat(chat.working.questions.single().confirmed).isFalse()
        chat.answerQuestion("r1", mapOf("q0" to "still too early"))
        assertThat(gateway.responses).isEmpty()
        assertThat(gateway.count("session.resume")).isEqualTo(1)

        gateway.announce()
        settle { gateway.count("session.resume") == 2 && !chat.working.loading && chat.working.questions.single().confirmed }
        assertThat(chat.state.value).isSameInstanceAs(published)
        chat.setVisible(true)
        assertThat(chat.state.value.questions.single().confirmed).isTrue()
        assertThat(chat.joinedTransport).isEqualTo(gateway.transport.value)
        chat.answerQuestion("r1", mapOf("q0" to "notes.md"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r1")
    }

    @Test
    fun `a new chat that loses its connection before its first message joins on the newest one, and answers there`() = runBlocking<Unit> {
        gateway.connect()
        val chat = ChatController(server.session(context, scope, db), gateway, scope, AppStrings(context.resources), null, questionMemory = memory)
        withTimeout(5_000) { while (gateway.count("session.create") < 1) delay(10) }
        gateway.drop()
        gateway.establish()
        gateway.announce()

        chat.send("hi", emptyList())
        withTimeout(5_000) { while (gateway.count("prompt.submit") < 1) delay(10) }
        gateway.ask("r1", "clarify", questions("Which file?"))
        chat.until { s -> s.questions.singleOrNull()?.confirmed == true }
        chat.answerQuestion("r1", mapOf("q0" to "notes.md"))

        assertThat(gateway.responses.map { it.id }).containsExactly("r1")
        // The session made in advance belonged to the lost connection; the chat's session is one made on this one.
        assertThat(gateway.createdOn.last()).isEqualTo(gateway.transport.value)
        assertThat(gateway.calls.filter { it.first == "session.create" }.map { it.second["close_on_disconnect"] }.toSet()).containsExactly(JsonPrimitive(false))
        assertThat(chat.joinedTransport).isEqualTo(gateway.transport.value)
    }

    @Test
    fun `a load in progress can confirm the replacement before its count is announced`() = runBlocking<Unit> {
        val chat = opened()
        withTimeout(5_000) { while (chat.completedLoadCycles.get() == 0L) delay(10) }
        chat.asked("r1", "Which file?")
        val history = server.holdHistory()
        try {
            chat.reload()
            check(history.entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val oldLoad = checkNotNull(chat.loadCompletion)
            gateway.replaceUnheard()
            chat.answerQuestion("r1", mapOf("q0" to "stale"))
            chat.cancelQuestion("r1")
            assertThat(gateway.responses).isEmpty()
            gateway.heard()
            chat.until { !it.questions.single().confirmed }
            history.release.countDown()
            withTimeout(5_000) { oldLoad.await() }
            chat.until { it.questions.single().confirmed && chat.joinedTransport == gateway.transport.value }
            withTimeout(5_000) { checkNotNull(chat.loadCompletion).await() }
            // READY permits this load's genuine replacement join, even before capabilities/count.
            // Unconfirmed is not a stable state while that older load can still finish.
            assertThat(gateway.connections.value).isEqualTo(1)
            assertThat(gateway.count("session.resume")).isEqualTo(2)
            chat.answerQuestion("r1", mapOf("q0" to "notes.md"))
            chat.answerQuestion("r1", mapOf("q0" to "duplicate"))
            assertThat(gateway.responses.map { it.id }).containsExactly("r1")
        } finally {
            history.release.countDown()
        }
    }

    @Test
    fun `an answer the moment its connection is replaced, before anything has heard of it, does not go`() = runBlocking<Unit> {
        val chat = opened()
        // This scenario isolates replacement of a settled join. `opened` observes visible
        // streaming state, not completion of the initial load loop: a still-finishing load may
        // legitimately resume the READY replacement before its count is announced.
        withTimeout(5_000) { while (chat.completedLoadCycles.get() == 0L) delay(10) }
        assertThat(checkNotNull(chat.loadCompletion).isCompleted).isTrue()
        assertThat(gateway.count("session.resume")).isEqualTo(1)
        chat.asked("r1", "Which file?")

        // Lost and replaced at once: the socket is READY on a new connection, the card still shows confirmed.
        gateway.replaceUnheard()
        assertThat(chat.state.value.questions.single().confirmed).isTrue()
        chat.answerQuestion("r1", mapOf("q0" to "stale"))
        chat.cancelQuestion("r1")
        assertThat(gateway.responses).isEmpty()

        gateway.heard()
        settle(2_000) { !chat.working.questions.single().confirmed }
        assertThat(chat.working.questions.single().confirmed).isFalse()
        gateway.announce()
        settle { gateway.count("session.resume") == 2 && chat.working.questions.single().confirmed }
        chat.answerQuestion("r1", mapOf("q0" to "notes.md"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r1")
    }

    @Test
    fun `an answered question that a gateway then withdraws as resolved stays answered and never opens again`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Which file?")
        chat.answerQuestion("r1", mapOf("q0" to "notes.md"))
        // A gateway that owns the session withdraws a settled request from every viewer, the one that answered too.
        gateway.event("request.cancel", buildJsonObject { put("id", "r1"); put("method", "clarify"); put("reason", "resolved") })
        gateway.deliver()

        gateway.drop()
        gateway.connect()
        joined(chat, resumes = 2)

        assertThat(chat.state.value.questions.single().state).isEqualTo(QuestionState.SUBMITTED)
        assertThat(chat.state.value.questions.single().answers).containsExactly("q0", "notes.md")
        assertThat(gateway.responses.map { it.id }).containsExactly("r1")
    }

    @Test
    fun `a request of a lost connection that reaches the chat after the join over the new one is not taken, and a current one is`() = runBlocking<Unit> {
        val chat = opened()
        val lost = gateway.transport.value
        gateway.drop()
        gateway.connect()
        joined(chat, resumes = 2)

        // Asked over the lost connection and never listed by the join over this one; one with no connection known.
        gateway.askLate(lost, "r1", questions("From the lost connection?"))
        gateway.askLate(0L, "r0", questions("From no known connection?"))
        gateway.deliverRequests()
        delay(200)

        assertThat(chat.state.value.questions).isEmpty()
        chat.answerQuestion("r1", mapOf("q0" to "stale"))
        assertThat(gateway.responses).isEmpty()
        assertThat(chat.asked("r2", "Asked now?").confirmed).isTrue()
        chat.answerQuestion("r2", mapOf("q0" to "yes"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r2")
    }

    @Test
    fun `a withdrawal of a lost connection that reaches the chat late does not end a question of the new one`() = runBlocking<Unit> {
        val chat = opened()
        val lost = gateway.transport.value
        gateway.drop()
        gateway.connect()
        joined(chat, resumes = 2)
        chat.asked("r3", "Asked now?")

        gateway.eventLate(lost, "request.cancel", buildJsonObject { put("id", "r3"); put("method", "clarify"); put("reason", "timeout") })
        gateway.deliver()
        delay(200)

        val card = chat.state.value.questions.single()
        assertThat(card.state).isEqualTo(QuestionState.PENDING)
        assertThat(card.confirmed).isTrue()
        chat.answerQuestion("r3", mapOf("q0" to "yes"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r3")
    }

    @Test
    fun `a request of a lost connection heard while the join is on its way is dropped once the join answers`() = runBlocking<Unit> {
        val chat = opened()
        val lost = gateway.transport.value
        gateway.afterResume = {
            gateway.askLate(lost, "r1", questions("Late from the lost connection?"))
            gateway.ask("r2", "clarify", questions("Asked during the join?"))
        }

        gateway.drop()
        gateway.connect()
        joined(chat, resumes = 2)

        assertThat(chat.state.value.questions.map { it.id to it.confirmed }).containsExactly("r2" to true)
        chat.answerQuestion("r1", mapOf("q0" to "stale"))
        chat.answerQuestion("r2", mapOf("q0" to "yes"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r2")
    }

    @Test
    fun `late observations of a lost connection that come before the chat has heard of the new one neither spare nor end anything`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Still open?")
        val lost = gateway.transport.value

        // The socket is on a new connection already; nothing in the chat has heard of it yet.
        gateway.replaceUnheard()
        gateway.askLate(lost, "r2", questions("Unseen on the lost connection?"))
        gateway.eventLate(lost, "request.cancel", buildJsonObject { put("id", "r1"); put("method", "clarify"); put("reason", "timeout") })
        gateway.deliverRequests()
        gateway.deliver()

        assertThat(chat.state.value.questions.map { it.id }).containsExactly("r1")
        assertThat(memory.ended("s1", "r1")).isNull()
        gateway.heard()
        gateway.announce()
        joined(chat, resumes = 2)

        // The join over the new connection lists r1, still open, and nothing of r2.
        assertThat(chat.state.value.questions.map { Triple(it.id, it.state, it.confirmed) }).containsExactly(Triple("r1", QuestionState.PENDING, true))
        chat.answerQuestion("r2", mapOf("q0" to "stale"))
        chat.answerQuestion("r1", mapOf("q0" to "yes"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r1")
    }

    @Test
    fun `what comes over the newest connection while its join is on its way, with an older join still known, is applied once that join answers`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Will it expire?")
        server.historyDelayMillis = 1_500
        gateway.drop()
        gateway.connect()
        withTimeout(5_000) { while (gateway.count("session.resume") < 2) delay(10) }
        delay(100)
        server.historyDelayMillis = 0
        // The join over the newest connection lists r1; then r1 expires, r2 is asked and another session's r2 ends.
        gateway.afterResume = {
            gateway.withdraw("r1", "timeout")
            gateway.ask("r2", "clarify", questions("Asked during the newest join?"))
            gateway.deliverRequests()
            gateway.foreign("request.cancel", buildJsonObject { put("id", "r2"); put("method", "clarify"); put("reason", "timeout") })
            gateway.deliver()
        }
        gateway.drop()
        gateway.connect()
        settle { gateway.count("session.resume") >= 3 && !chat.working.loading && chat.working.questions.size == 2 && chat.working.questions.all { it.state != QuestionState.PENDING || it.confirmed } }
        gateway.afterResume = null

        assertThat(gateway.resumedOn.toList()).containsExactly(1, 2, 3).inOrder()
        assertThat(chat.state.value.questions.map { Triple(it.id, it.state, it.confirmed) })
            .containsExactly(Triple("r1", QuestionState.EXPIRED, false), Triple("r2", QuestionState.PENDING, true)).inOrder()
        chat.answerQuestion("r1", mapOf("q0" to "late"))
        chat.answerQuestion("r2", mapOf("q0" to "yes"))
        chat.answerQuestion("r2", mapOf("q0" to "again"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r2")
    }

    @Test
    fun `a question asked over a connection that is ready before the chat joins it is shown once the join comes`() = runBlocking<Unit> {
        val chat = opened()
        gateway.drop()
        gateway.establish()
        gateway.ask("r2", "clarify", questions("Asked before the join?"))
        gateway.deliverRequests()
        chat.answerQuestion("r2", mapOf("q0" to "early"))
        assertThat(gateway.responses).isEmpty()

        gateway.announce()
        joined(chat, resumes = 2)

        assertThat(chat.state.value.questions.map { it.id to it.confirmed }).containsExactly("r2" to true)
        chat.answerQuestion("r2", mapOf("q0" to "yes"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r2")
    }

    @Test
    fun `everything that comes over the connection while its join is on its way is kept, however many other sessions' frames come with it`() = runBlocking<Unit> {
        val chat = opened()
        chat.asked("r1", "Will it expire?")
        // The join's answer lists r1. Before it reaches the chat r2 is asked, r1 expires, and many
        // withdrawals of another session's requests follow.
        gateway.afterResume = {
            gateway.ask("r2", "clarify", questions("Asked during the join?"))
            gateway.deliverRequests()
            gateway.withdraw("r1", "timeout")
            repeat(70) { gateway.foreign("request.cancel", buildJsonObject { put("id", "other$it"); put("method", "clarify"); put("reason", "timeout") }) }
            gateway.deliver()
        }

        gateway.drop()
        gateway.connect()
        joined(chat, resumes = 2)
        gateway.afterResume = null

        assertThat(chat.state.value.questions.map { Triple(it.id, it.state, it.confirmed) })
            .containsExactly(Triple("r1", QuestionState.EXPIRED, false), Triple("r2", QuestionState.PENDING, true)).inOrder()
        chat.answerQuestion("r1", mapOf("q0" to "late"))
        chat.answerQuestion("r2", mapOf("q0" to "yes"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r2")
    }

    @Test
    fun `a question heard before the join asked, and answered elsewhere without a word, closes when the join does not list it`() = runBlocking<Unit> {
        val chat = opened()
        gateway.drop()
        gateway.establish()
        gateway.ask("r2", "clarify", questions("Asked before the join?"))
        gateway.deliverRequests()
        // Another client answers it; the dashboard settles it without telling anyone.
        gateway.answeredElsewhere("r2")
        gateway.announce()
        joined(chat, resumes = 2)

        assertThat(gateway.listedOn.last()).doesNotContain("r2")
        assertThat(chat.state.value.questions.map { it.id to it.state }).containsExactly("r2" to QuestionState.CLOSED)
        chat.answerQuestion("r2", mapOf("q0" to "stale"))
        chat.cancelQuestion("r2")
        assertThat(gateway.responses).isEmpty()
    }

    @Test
    fun `a question heard after the join asked stays open although the join's answer does not list it`() = runBlocking<Unit> {
        val chat = opened()
        gateway.drop()
        gateway.establish()
        gateway.afterResume = {
            gateway.ask("r3", "clarify", questions("Asked after the join asked?"))
            gateway.deliverRequests()
        }
        gateway.announce()
        joined(chat, resumes = 2)
        gateway.afterResume = null

        assertThat(gateway.listedOn.last()).doesNotContain("r3")
        assertThat(chat.state.value.questions.map { Triple(it.id, it.state, it.confirmed) }).containsExactly(Triple("r3", QuestionState.PENDING, true))
        chat.answerQuestion("r3", mapOf("q0" to "yes"))
        chat.answerQuestion("r3", mapOf("q0" to "again"))
        assertThat(gateway.responses.map { it.id }).containsExactly("r3")
    }
}
