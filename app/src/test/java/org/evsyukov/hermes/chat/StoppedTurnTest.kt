package org.evsyukov.hermes.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.remote.string
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.ServerStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Stopping a turn settles the chat and reaches the server: a stop pressed before the join names the
 * session is sent when it does, the interrupted turn's terminal frame lands in the answer the stop
 * closed, and a question sheet the server no longer holds does not stay over the chat.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class StoppedTurnTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = LiveServer.database(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = LiveGateway()
    private val server = LiveServer(gateway)
    private val strings = AppStrings(context.resources)
    private lateinit var cache: ResponseCache
    private lateinit var home: ServerProfile
    private lateinit var store: ServerStore

    @Before
    fun setUp() {
        db.openHelper.writableDatabase
        cache = ResponseCache(db.responses(), scope, { Long.MAX_VALUE })
        home = ServerProfile("stopped-turn-home", "Home", server.http.url("/").toString())
        store = ServerStore(context, PlainCipher, scope)
        runBlocking { store.save(home, makeActive = true) }
    }

    @After
    fun tearDown() {
        val owner = scope.coroutineContext.job
        server.finish(owner, listOf("close server" to { server.close() }, "close database" to { db.close() }))
    }

    private fun chat(storedId: String? = "s1") = ChatController(
        ServerSession(home, OkHttpClient(), store, strings, cache),
        gateway, scope, strings, storedId,
    ).also { it.continuationProducers }

    private fun answer(state: ChatState) = state.items.lastOrNull() as? ChatItem.Assistant

    private suspend fun ChatController.until(condition: (ChatState) -> Boolean): ChatState =
        withTimeout(10_000) { state.first(condition) }

    /** A chat opened on a turn under way: "Hello" streamed before it opened, " there" while it is on the screen. */
    private suspend fun openMidTurn(): ChatController {
        gateway.connect()
        gateway.start("hi")
        gateway.delta("Hello")
        val chat = chat()
        chat.until { answer(it)?.text == "Hello" && !it.loading }
        gateway.delta(" there")
        chat.until { answer(it)?.text == "Hello there" }
        return chat
    }

    @Test
    fun `a stop pressed before the join names the session is sent when it does`() = runBlocking<Unit> {
        gateway.connect()
        server.holdCreate()
        val chat = chat(storedId = null)
        try {
            chat.send("hello", emptyList())
            withTimeout(10_000) { gateway.createArrived.await() }
            chat.stop()
            chat.until { !it.running }

            // The session is not joined yet, so there is nothing to interrupt: the stop waits for it.
            assertThat(gateway.count("session.interrupt")).isEqualTo(0)

            server.releaseCreate()
            withTimeout(10_000) { while (gateway.count("session.interrupt") == 0) delay(10) }
            val interrupt = gateway.calls.first { it.first == "session.interrupt" }.second
            assertThat(interrupt.string("session_id")).isEqualTo("live1")
        } finally {
            server.releaseCreate()
            chat.close()
        }
    }

    @Test
    fun `the interrupted turn's terminal frame lands in the answer the stop closed`() = runBlocking<Unit> {
        val chat = openMidTurn()
        try {
            chat.stop()
            chat.until { !it.running }

            // The server's rendering of the interrupted turn is its own: it must land in the answer
            // the stop closed, not in a second bubble under it.
            gateway.event("message.complete", buildJsonObject { put("text", "Hello there!"); put("status", "interrupted") })
            chat.until { state -> answer(state)?.text == "Hello there!" && state.items.count { it is ChatItem.Assistant } == 1 }

            val answers = chat.state.value.items.filterIsInstance<ChatItem.Assistant>()
            assertThat(answers).hasSize(1)
            assertThat(answers.single().text).isEqualTo("Hello there!")
            assertThat(answers.single().interrupted).isTrue()
        } finally {
            chat.close()
        }
    }

    @Test
    fun `a join withdraws an approval sheet the server no longer holds`() = runBlocking<Unit> {
        val chat = openMidTurn()
        try {
            gateway.ask(
                "r1",
                "approval",
                buildJsonObject {
                    put("command", "rm -rf /tmp/x")
                    put("description", "dangerous")
                    put("choices", JsonArray(listOf(JsonPrimitive("once"), JsonPrimitive("deny"))))
                },
            )
            gateway.deliverRequests()
            chat.until { it.prompt != null }

            // The server stops waiting while the socket is down: the reconnect lists no open request.
            gateway.answeredElsewhere("r1")
            gateway.drop()
            gateway.connect()

            chat.until { it.prompt == null }
        } finally {
            chat.close()
        }
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}
