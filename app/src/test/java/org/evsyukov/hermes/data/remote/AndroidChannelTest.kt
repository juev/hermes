package org.evsyukov.hermes.data.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.Tokens
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.testing.ChatTestResources
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class AndroidChannelTest {
    @Test
    fun `native admission identity survives the real socket event decoder`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val open = CompletableDeferred<WebSocket>()
            server.enqueue(MockResponse.Builder().body("""{"ticket":"fixture-ticket","channel_version":1}""").build())
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    open.complete(webSocket)
                    webSocket.send("""{"method":"event","params":{"type":"gateway.ready","payload":{}}}""")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = HermesJson.parseToJsonElement(text).jsonObject
                    webSocket.send("""{"id":${frame["id"]},"result":{}}""")
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            }).build())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val strings = AppStrings(ApplicationProvider.getApplicationContext<Application>().resources)
            val http = HermesHttp(server.url("/"), OkHttpClient(), tokens = { Tokens("fixture-access") }, strings = strings, androidChannel = true)
            val socket = GatewaySocket(http, scope, strings)
            try {
                withTimeout(5_000) { socket.awaitReady() }
                val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { socket.events.first { it.type == "message.complete" } } }
                check(open.await().send("""{"method":"event","params":{"type":"message.complete","session_id":"own","seq":7,"admission_id":"native-a1","payload":{"status":"complete","text":"done"}}}"""))
                assertThat(received.await().admissionId).isEqualTo("native-a1")
            } finally {
                socket.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun `minimal history carries separate exact receipt rows without showing tool lineage`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{
                "projection":"android-minimal-v1","session_id":"s1","profile":"default",
                "messages":[{"id":1,"role":"user","content":"same"},{"id":2,"role":"assistant","content":""},{"id":3,"role":"tool","content":"","display_kind":"hidden"},{"id":4,"role":"assistant","content":"done"}],
                "receipt_rows":[{"id":1,"role":"user","content":"same"},{"id":2,"role":"assistant","content":"","tool_calls":[{"id":"c1"}]},{"id":3,"role":"tool","content":"","tool_call_id":"c1"},{"id":4,"role":"assistant","content":"done"}]
            }""").build())
            val strings = AppStrings(ApplicationProvider.getApplicationContext<Application>().resources)
            val http = HermesHttp(server.url("/"), OkHttpClient(), tokens = { null }, strings = strings, androidChannel = true)
            val page = ChatApi(http).history("s1")
            assertThat(page.receiptRows.single { it.rowId == 3L }.hidden).isFalse()
            assertThat(page.receiptRows.single { it.rowId == 2L }.toolCalls.single().id).isEqualTo("c1")
            assertThat(page.rows.single { it.rowId == 3L }.hidden).isTrue()
            assertThat(page.rows.flatMap { it.toolCalls }).isEmpty()
        }
    }

    @Test
    fun `saved channel profiles wire real session routes and isolate local observation origins`() = runBlocking<Unit> {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val resources = ChatTestResources()
        server.start()
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val profile = ServerProfile("channel-profile", "Channel", server.url("/").toString(), androidChannel = true)
            val cipher = object : TokenCipher {
                override fun encrypt(plain: String) = plain
                override fun decrypt(sealed: String) = sealed
            }
            val store = resources.store(app, cipher, scope, profile)
            val session = ServerSession(profile, OkHttpClient(), store, AppStrings(app.resources))
            assertThat(session.http.androidChannel).isTrue()
            assertThat(session.observationServer).isNotEqualTo(profile.url.toString())
            assertThat(session.observationServer).contains("api/plugins/hermes-android")
            server.enqueue(MockResponse.Builder().body("""{"sessions":[],"total":0}""").build())
            session.chatFor(session.owner()).list()
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/api/plugins/hermes-android/api/sessions")
        } finally {
            resources.finish(checkNotNull(scope.coroutineContext[kotlinx.coroutines.Job]), listOf("close server" to { server.close() }))
        }
    }

    @Test
    fun `an incompatible plugin version refuses before upgrade without legacy fallback`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"ticket":"fixture-plugin-ticket","channel_version":999}""").build())
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":true}}}""")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = HermesJson.parseToJsonElement(text).jsonObject
                    webSocket.send("""{"id":${frame["id"]},"result":{}}""")
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            }).build())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val strings = AppStrings(ApplicationProvider.getApplicationContext<Application>().resources)
            val http = HermesHttp(server.url("/"), OkHttpClient(), tokens = { Tokens("fixture-access") }, strings = strings, androidChannel = true)
            val socket = GatewaySocket(http, scope, strings, firstPause = 30_000)
            try {
                val failure = runCatching { withTimeout(5_000) { socket.awaitReady() } }.exceptionOrNull()
                assertThat(failure).isInstanceOf(java.io.IOException::class.java)
                assertThat(failure?.message).contains("version")
                assertThat(server.requestCount).isEqualTo(1)
            } finally {
                socket.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun `channel sockets mint their own versioned ticket and never open the generic socket`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"ticket":"fixture-plugin-ticket","channel_version":1}""").build())
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":true}}}""")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = HermesJson.parseToJsonElement(text).jsonObject
                    webSocket.send("""{"id":${frame["id"]},"result":{}}""")
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            }).build())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val strings = AppStrings(ApplicationProvider.getApplicationContext<Application>().resources)
            val http = HermesHttp(server.url("/"), OkHttpClient(), tokens = { Tokens("fixture-access") }, strings = strings, androidChannel = true)
            val socket = GatewaySocket(http, scope, strings)
            try {
                withTimeout(5_000) { socket.awaitReady() }
                val mint = server.takeRequest()
                val upgrade = server.takeRequest()
                assertThat(mint.url.encodedPath).isEqualTo("/api/plugins/hermes-android/ws-ticket")
                assertThat(mint.headers["Authorization"]).isEqualTo("Bearer fixture-access")
                assertThat(upgrade.url.encodedPath).isEqualTo("/api/plugins/hermes-android/ws")
                assertThat(upgrade.url.queryParameter("ticket")).isEqualTo("fixture-plugin-ticket")
            } finally {
                socket.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun `channel chat reads use distinct routes instead of legacy cache keys`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"sessions":[],"total":0}""").build())
            val strings = AppStrings(ApplicationProvider.getApplicationContext<Application>().resources)
            val http = HermesHttp(server.url("/"), OkHttpClient(), tokens = { null }, strings = strings, androidChannel = true)
            ChatApi(http).list()
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/api/plugins/hermes-android/api/sessions")
            assertThat(http.chatPath("api/sessions/own/messages")).isEqualTo("api/plugins/hermes-android/api/sessions/own/messages")
            assertThat(http.chatPath("api/config")).isEqualTo("api/config")
            val legacy = HermesHttp(server.url("/"), http.client, tokens = { null }, strings = strings)
            assertThat(legacy.chatPath("api/sessions")).isEqualTo("api/sessions")
        }
    }
}
