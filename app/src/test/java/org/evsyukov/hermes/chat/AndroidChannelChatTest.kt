package org.evsyukov.hermes.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import mockwebserver3.*
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.remote.GatewayChannel
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.WorkOwner
import org.evsyukov.hermes.testing.ChatTestResources
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class AndroidChannelChatTest {
    @Test
    fun `an owned channel chat joins without consulting the generic active session list`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val resources = ChatTestResources()
        val server = MockWebServer()
        val gateway = LiveGateway()
        val calls = CopyOnWriteArrayList<String>()
        val channel = object : GatewayChannel by gateway {
            override suspend fun call(method: String, params: JsonObject, timeoutMillis: Long): JsonElement {
                calls += method
                return if (method == "session.active_list") HermesJson.parseToJsonElement("""{"sessions":[]}""")
                else gateway.call(method, params, timeoutMillis)
            }
            override suspend fun callOn(owner: WorkOwner, transport: Long, method: String, params: JsonObject, timeoutMillis: Long): JsonElement =
                call(method, params, timeoutMillis)
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().body(
                if (request.url.encodedPath.endsWith("/messages")) """{"session_id":"s1","messages":[{"id":1,"role":"user","content":"Stored user"}]}"""
                else """{"id":"s1","title":"Owned","source":"android","last_active":1}"""
            ).build()
        }
        server.start()
        var chat: ChatController? = null
        try {
            gateway.connect()
            val context = ApplicationProvider.getApplicationContext<Application>()
            val profile = ServerProfile("channel-chat", "Channel", server.url("/").toString(), androidChannel = true)
            val cipher = object : TokenCipher {
                override fun encrypt(plain: String) = plain
                override fun decrypt(sealed: String) = sealed
            }
            val store = resources.store(context, cipher, scope, profile)
            val strings = AppStrings(context.resources)
            val session = ServerSession(profile, OkHttpClient(), store, strings)
            val read = CompletableDeferred<Unit>()
            chat = ChatController(session, channel, scope, strings, "s1", onRead = { _, _, _ -> read.complete(Unit) })
            withTimeout(5_000) {
                read.await()
                checkNotNull(chat.loadCompletion).await()
            }
            assertThat(gateway.count("session.resume")).isAtLeast(1)
            assertThat(calls).doesNotContain("session.active_list")
            assertThat(chat.state.value.items.filterIsInstance<ChatItem.User>().single().text).isEqualTo("Stored user")
        } finally {
            chat?.close()
            resources.finish(scope.coroutineContext.job, listOf("close server" to { server.close() }))
        }
    }
}
