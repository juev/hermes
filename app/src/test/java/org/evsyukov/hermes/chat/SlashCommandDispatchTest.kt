package org.evsyukov.hermes.chat

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.cache.CacheDatabase
import org.evsyukov.hermes.data.remote.RpcException
import org.evsyukov.hermes.data.remote.string
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.testing.ChatTestResources
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A recognized command takes its own path: the reviewed server command route, the connection's own
 * control verbs, or an honest refusal — never the model prompt, and never a recovery observation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class SlashCommandDispatchTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val resources = ChatTestResources()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: CacheDatabase
    private lateinit var gateway: LiveGateway
    private lateinit var server: LiveServer
    private lateinit var session: ServerSession
    private lateinit var controller: ChatController

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, CacheDatabase::class.java).allowMainThreadQueries().build()
        gateway = LiveGateway()
        server = LiveServer(gateway)
        session = server.session(app, scope, db)
        controller = ChatController(session, gateway, scope, AppStrings(app.resources), "s1")
        runBlocking {
            gateway.connect()
            withTimeout(10_000) { while (gateway.count("session.resume") == 0) delay(20) }
        }
    }

    @After
    fun tearDown() {
        controller.close()
        resources.finish(checkNotNull(scope.coroutineContext[kotlinx.coroutines.Job]), listOf("close server" to { server.close() }, "close db" to { db.close() }))
    }

    private fun lastCommand(): ChatItem.Command? = controller.state.value.items.filterIsInstance<ChatItem.Command>().lastOrNull()

    private fun awaitCommand(state: CommandState, timeoutMillis: Long = 10_000): ChatItem.Command = runBlocking {
        try {
            withTimeout(timeoutMillis) {
                var card = lastCommand()
                while (card == null || card.state != state) {
                    delay(20)
                    card = lastCommand()
                }
                checkNotNull(card)
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Waiting for $state; last command: ${lastCommand()}; items: ${controller.state.value.items.map { it::class.simpleName }}", timeout)
        }
    }

    @Test
    fun `a read command runs on the server path and shows its output`() {
        gateway.slashAnswer = buildJsonObject { put("type", "exec"); put("output", "Hermes v0.23") }

        controller.send("/status", emptyList())

        val card = awaitCommand(CommandState.OK)
        assertThat(card.name).isEqualTo("status")
        assertThat(card.output).isEqualTo("Hermes v0.23")
        assertThat(gateway.count("slash.exec")).isEqualTo(1)
        assertThat(gateway.calls.first { it.first == "slash.exec" }.second.string("command")).isEqualTo("/status")
        assertThat(gateway.count("prompt.submit")).isEqualTo(0)
        assertThat(controller.state.value.observations).isEmpty()
    }

    @Test
    fun `a refused command is shown and never sent to the model`() {
        gateway.slashError = RpcException(4064, "unsupported_command", null)

        controller.send("/pause", emptyList())

        val card = awaitCommand(CommandState.REFUSED)
        assertThat(card.output).contains("unsupported_command")
        assertThat(gateway.count("prompt.submit")).isEqualTo(0)
        assertThat(controller.state.value.observations).isEmpty()
    }

    @Test
    fun `a send directive submits the prompt the server built without dispatching it again`() {
        gateway.slashAnswer = buildJsonObject {
            put("type", "send")
            put("message", "/skill-review do it")
            put("display", "/review do it")
            put("notice", "reviewing")
        }

        controller.send("/review do it", emptyList())

        awaitCommand(CommandState.OK)
        runBlocking { withTimeout(10_000) { while (gateway.count("prompt.submit") == 0) delay(20) } }
        assertThat(gateway.count("slash.exec")).isEqualTo(1)
        assertThat(gateway.calls.first { it.first == "prompt.submit" }.second.string("text")).isEqualTo("/skill-review do it")
        val shown = controller.state.value.items.filterIsInstance<ChatItem.User>().last()
        assertThat(shown.text).isEqualTo("/review do it")
    }

    @Test
    fun `prose with a slash stays an ordinary prompt`() {
        controller.send("look at path/to/file", emptyList())

        runBlocking { withTimeout(10_000) { while (gateway.count("prompt.submit") == 0) delay(20) } }
        assertThat(gateway.count("slash.exec")).isEqualTo(0)
        assertThat(lastCommand()).isNull()
    }

    @Test
    fun `the channel offers only its stock equivalents`() {
        val profile = ServerProfile("channel-cmd", "Channel", server.http.url("/").toString(), androidChannel = true)
        val cipher = object : TokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(sealed: String) = sealed
        }
        val channelSession = ServerSession(profile, OkHttpClient(), resources.store(app, cipher, scope, profile), AppStrings(app.resources))
        val channel = ChatController(channelSession, gateway, scope, AppStrings(app.resources), "s1")
        runBlocking { withTimeout(10_000) { while (gateway.count("session.resume") < 2) delay(20) } }
        try {
            channel.send("/status", emptyList())
            runBlocking {
                withTimeout(10_000) {
                    while (channel.state.value.items.filterIsInstance<ChatItem.Command>().lastOrNull()?.state != CommandState.REFUSED) delay(20)
                }
            }
            assertThat(channel.state.value.items.filterIsInstance<ChatItem.Command>().last().output).isNotEmpty()
            assertThat(gateway.count("slash.exec")).isEqualTo(0)

            channel.send("/title Work", emptyList())
            runBlocking { withTimeout(10_000) { while (gateway.count("session.title") == 0) delay(20) } }
            assertThat(gateway.calls.last { it.first == "session.title" }.second.string("title")).isEqualTo("Work")

            channel.send("/help", emptyList())
            runBlocking {
                withTimeout(10_000) {
                    while (channel.state.value.items.filterIsInstance<ChatItem.Command>().lastOrNull()?.state != CommandState.OK) delay(20)
                }
            }
            assertThat(gateway.count("prompt.submit")).isEqualTo(0)
        } finally {
            channel.close()
        }
    }

    @Test
    fun `restart acknowledges, waits for the gateway and confirms once`() {
        controller.send("/restart", emptyList())

        runBlocking { withTimeout(10_000) { while (server.restarts.get() == 0) delay(20) } }
        gateway.drop()
        runBlocking { gateway.connect() }

        val card = awaitCommand(CommandState.OK)
        assertThat(card.name).isEqualTo("restart")
        assertThat(server.restarts.get()).isEqualTo(1)
        assertThat(gateway.count("prompt.submit")).isEqualTo(0)
    }

    @Test
    fun `a refused restart is reported without a retry`() {
        server.restartStatus = 403

        controller.send("/restart", emptyList())

        val card = awaitCommand(CommandState.REFUSED)
        assertThat(card.output).isNotEmpty()
        assertThat(server.restarts.get()).isEqualTo(1)
    }

    @Test
    fun `a lost restart acknowledgement is confirmed without resubmitting`() {
        server.restartDisconnect = true

        controller.send("/restart", emptyList())

        runBlocking { withTimeout(10_000) { while (server.restarts.get() == 0) delay(20) } }
        gateway.drop()
        runBlocking { gateway.connect() }

        // The drop may land before the confirmation starts watching: it then waits out its
        // short drop window and confirms over the status poll. Either schedule confirms once.
        val card = awaitCommand(CommandState.OK, timeoutMillis = 25_000)
        assertThat(card.name).isEqualTo("restart")
        assertThat(server.restarts.get()).isEqualTo(1)
    }

    @Test
    fun `a command mid-run still takes the command path, not a second prompt`() {
        gateway.slashAnswer = buildJsonObject { put("type", "exec"); put("output", "status while busy") }
        controller.send("work on it", emptyList())
        runBlocking { withTimeout(10_000) { while (gateway.count("prompt.submit") == 0) delay(20) } }

        controller.send("/status", emptyList())

        val card = awaitCommand(CommandState.OK)
        assertThat(card.output).isEqualTo("status while busy")
        assertThat(gateway.count("slash.exec")).isEqualTo(1)
        assertThat(gateway.count("prompt.submit")).isEqualTo(1)
    }
}
