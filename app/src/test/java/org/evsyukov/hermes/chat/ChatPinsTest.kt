package org.evsyukov.hermes.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.remote.ChatApi
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.remote.HermesException
import org.evsyukov.hermes.data.remote.HermesHttp
import org.evsyukov.hermes.ui.ListMode
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ChatPinsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What the mutations asked to pin on the phone, in order. */
    private val local = mutableListOf<Pair<String, Boolean>>()
    private lateinit var mutations: ChatMutations

    @Before
    fun setUp() {
        server.start()
        val strings = AppStrings(context.resources)
        val http = HermesHttp(server.url("/"), OkHttpClient(), tokens = { null }, saveTokens = {}, strings = strings)
        mutations = ChatMutations(ChatApi(http), GatewaySocket(http, scope, strings), liveOf = { null }, pinLocally = { id, pinned -> local += id to pinned })
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    @Test
    fun `a chat the server answers not_found for is pinned on the phone`() = runBlocking<Unit> {
        server.enqueue(snapshot())
        server.enqueue(MockResponse.Builder().code(404).body("""{"detail": "not_found"}""").build())

        mutations.pin("app-chat", true)

        assertThat(local).containsExactly("app-chat" to true)
    }

    @Test
    fun `a chat the server pins stays off the phone, and its unpin clears a pin there too`() = runBlocking<Unit> {
        server.enqueue(snapshot())
        server.enqueue(MockResponse.Builder().body("""{"ok": true}""").build())
        mutations.pin("telegram-chat", true)
        assertThat(local).isEmpty()
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/api/sessions/telegram-chat/mutation-snapshot")
        assertThat(server.takeRequest().body?.utf8()).contains(""""pinned":true""")

        server.enqueue(snapshot())
        server.enqueue(MockResponse.Builder().body("""{"ok": true}""").build())
        mutations.pin("telegram-chat", false)
        assertThat(local).containsExactly("telegram-chat" to false)
    }

    @Test
    fun `another refusal of the server is reported and pins nothing on the phone`() {
        server.enqueue(snapshot())
        server.enqueue(MockResponse.Builder().code(409).body("""{"detail": "stale_revision"}""").build())

        val error = assertThrows(HermesException::class.java) { runBlocking { mutations.pin("chat", true) } }

        assertThat(error.status).isEqualTo(409)
        assertThat(local).isEmpty()
    }

    @Test
    fun `a deleted chat leaves the pins of the phone`() = runBlocking<Unit> {
        server.enqueue(snapshot())
        server.enqueue(MockResponse.Builder().body("""{"ok": true}""").build())

        mutations.delete("chat")

        assertThat(local).containsExactly("chat" to false)
    }

    @Test
    fun `pins on the phone are kept per server`() = runBlocking<Unit> {
        val pins = LocalPins(context)
        pins.set("home", "a", true)
        pins.set("home", "b", true)
        pins.set("work", "c", true)
        pins.set("home", "a", false)

        assertThat(LocalPins(context).of("home")).containsExactly("b")
        assertThat(LocalPins(context).of("work")).containsExactly("c")
        assertThat(LocalPins(context).of("other")).isEmpty()
    }

    @Test
    fun `a list marks the chats pinned on the phone and brings those beyond its page`() = runBlocking<Unit> {
        val page = listOf(chat("listed"), chat("pinned-here"), chat("pinned-there", pinned = true))
        val far = mapOf("old" to chat("old"), "archived" to chat("archived", archived = true))
        val fetched = mutableListOf<String>()

        val chats = withLocalPins(page, setOf("pinned-here", "old", "archived", "gone"), ListMode.CHATS::admits) { id ->
            fetched += id
            far[id]
        }

        assertThat(chats.map { it.id to it.pinned }).containsExactly(
            "listed" to false,
            "pinned-here" to true,
            "pinned-there" to true,
            "old" to true,
        ).inOrder()
        assertThat(fetched).containsExactly("old", "archived", "gone")
    }

    @Test
    fun `a list takes the chats its filters take`() {
        assertThat(ListMode.CHATS.admits(chat("a", source = "android"))).isTrue()
        assertThat(ListMode.CHATS.admits(chat("a", source = "cron"))).isFalse()
        assertThat(ListMode.CHATS.admits(chat("a", archived = true))).isFalse()
        assertThat(ListMode.ARCHIVE.admits(chat("a", archived = true))).isTrue()
        assertThat(ListMode.CRON.admits(chat("a", source = "cron"))).isTrue()
        assertThat(ListMode.CRON.admits(chat("a", source = "telegram"))).isFalse()
    }

    private fun snapshot() = MockResponse.Builder().body("""{"runtime_revision": 3, "runtime_generation": 1}""").build()

    private fun chat(id: String, pinned: Boolean = false, archived: Boolean = false, source: String = "android") =
        ChatSummary(id, id, "", source, null, 0.0, 0.0, 2, pinned = pinned, archived = archived, active = false)
}
