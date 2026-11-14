package org.evsyukov.hermes.data.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.testing.ChatTestResources
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A channel profile reads and mutates the stock api_server routes directly: no plugin prefix, no
 * mutation snapshot, the stock envelopes. The live turn itself is ApiServerGateway's contract.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class StockChannelWiringTest {

    private fun session(server: MockWebServer, scope: CoroutineScope, resources: ChatTestResources): ServerSession {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val profile = ServerProfile("channel-stock", "Channel", server.url("/").toString(), androidChannel = true)
        val cipher = object : TokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(sealed: String) = sealed
        }
        val store = resources.store(app, cipher, scope, profile)
        return ServerSession(profile, OkHttpClient(), store, AppStrings(app.resources))
    }

    @Test
    fun `a channel profile keeps the plain server lineage and stock list envelope`() = runBlocking<Unit> {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val resources = ChatTestResources()
        server.start()
        try {
            val session = session(server, scope, resources)
            assertThat(session.observationServer).isEqualTo(session.profile.url.toString())
            server.enqueue(
                MockResponse.Builder().body(
                    """{"object":"list","data":[{"id":"api_1","title":"Work","source":"api_server","last_active":5.0,"message_count":2}]}""",
                ).build(),
            )
            val page = session.chatFor(session.owner()).list()
            val request = server.takeRequest()
            assertThat(request.url.encodedPath).isEqualTo("/api/sessions")
            assertThat(page.chats.single().id).isEqualTo("api_1")
            assertThat(page.chats.single().source).isEqualTo("api_server")
        } finally {
            resources.finish(checkNotNull(scope.coroutineContext[kotlinx.coroutines.Job]), listOf("close server" to { server.close() }))
        }
    }

    @Test
    fun `channel history reads stock rows and hides what the server told the model`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body(
                    """
                    {"object":"list","session_id":"api_1","data":[
                      {"id":"1","role":"user","content":"hello","timestamp":1.0},
                      {"id":"2","role":"user","content":"process done","timestamp":2.0,"display_kind":"process_complete"},
                      {"id":"3","role":"assistant","content":"Hi","timestamp":3.0,"display_kind":"final"}
                    ]}
                    """.trimIndent(),
                ).build(),
            )
            val strings = AppStrings(ApplicationProvider.getApplicationContext<Application>().resources)
            val http = HermesHttp(server.url("/"), OkHttpClient(), tokens = { null }, strings = strings, androidChannel = true)
            val page = ChatApi(http).history("api_1")
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/api/sessions/api_1/messages")
            assertThat(page.rows.map { it.rowId }).containsExactly(1L, 2L, 3L).inOrder()
            assertThat(page.rows.single { it.rowId == 2L }.hidden).isTrue()
            assertThat(page.rows.single { it.rowId == 3L }.hidden).isFalse()
            assertThat(page.receiptRows).isEqualTo(page.rows)
        }
    }

    @Test
    fun `channel mutations are the stock PATCH and DELETE without a snapshot fence`() = runBlocking<Unit> {
        val server = MockWebServer()
        val requests = ConcurrentLinkedQueue<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse.Builder().body("""{"object":"hermes.session","session":{"id":"api_1"}}""").build()
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val resources = ChatTestResources()
        server.start()
        try {
            val session = session(server, scope, resources)
            val chat = session.chatFor(session.owner())
            chat.mutate("api_1", title = "Renamed", archived = true)
            chat.delete("api_1")
            assertThat(requests.map { it.method to it.url.encodedPath }).containsExactly(
                "PATCH" to "/api/sessions/api_1",
                "DELETE" to "/api/sessions/api_1",
            ).inOrder()
            val patch = requests.first().body!!.utf8()
            assertThat(patch).contains("\"title\":\"Renamed\"")
            assertThat(patch).contains("\"archived\":true")
            assertThat(patch).doesNotContain("expected_revision")
            assertThat(requests.last().url.encodedPath).isEqualTo("/api/sessions/api_1")
        } finally {
            resources.finish(checkNotNull(scope.coroutineContext[kotlinx.coroutines.Job]), listOf("close server" to { server.close() }))
        }
    }
}
