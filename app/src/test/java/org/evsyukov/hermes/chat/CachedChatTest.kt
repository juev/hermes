package org.evsyukov.hermes.chat

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.cache.CacheDatabase
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.remote.cacheKey
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.ServerStore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class CachedChatTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ResponseCache(db.responses(), scope, { Long.MAX_VALUE })

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun `a chat kept on the phone opens with its history while the server is unreachable`() = runBlocking<Unit> {
        val strings = AppStrings(context.resources)
        // Nothing listens on port 9 of this machine: every request to the server fails.
        val profile = ServerProfile("home", "Home", "http://127.0.0.1:9")
        val session = ServerSession(profile, OkHttpClient(), ServerStore(context, PlainCipher, scope), strings, cache)
        cache.write("home", cacheKey("api/sessions/s1", emptyMap()), """{"id": "s1", "title": "Plans"}""")
        cache.write(
            "home",
            cacheKey("api/sessions/s1/messages", mapOf("limit" to 500, "order" to "oldest", "inline_images" to false)),
            """{"messages": [{"id": 1, "role": "user", "content": "hello"}]}""",
        )

        val chat = ChatController(session, GatewaySocket(session.http, scope, strings), scope, strings, "s1")
        val failed = withTimeout(10_000) { chat.state.first { it.error != null } }
        chat.close()

        assertThat(failed.title).isEqualTo("Plans")
        assertThat(failed.items.map { (it as ChatItem.User).text }).containsExactly("hello")
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}
