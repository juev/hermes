package org.evsyukov.hermes.data.remote

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.cache.CacheDatabase
import org.evsyukov.hermes.data.cache.ResponseCache
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ReadsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ResponseCache(db.responses(), scope, { Long.MAX_VALUE })

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        server.close()
    }

    private fun apis(): Pair<ChatApi, AdminApi> {
        val http = HermesHttp(server.url("/"), OkHttpClient(), tokens = { null }, saveTokens = {}, strings = AppStrings(context.resources))
        val network = NetworkReads(http, cache, "home")
        val cached = CachedReads(cache, "home")
        return ChatApi(http, network, cached) to AdminApi(http, network, cached)
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private suspend fun awaitKept(request: String) = withTimeout(5_000) {
        while (cache.read("home", request) == null) delay(20)
    }

    @Test
    fun `history the server sent reads back from the phone without the server`() = runBlocking {
        server.enqueue(json("""{"messages": [{"id": 7, "role": "user", "content": "hello", "timestamp": 1.0}]}"""))
        val (chat, _) = apis()
        val fromServer = chat.messages("s1")
        awaitKept(cacheKey("api/sessions/s1/messages", mapOf("limit" to 500, "order" to "oldest", "inline_images" to false)))
        server.close()

        val fromPhone = chat.cached!!.messages("s1")
        assertThat(fromPhone).isEqualTo(fromServer)
        assertThat(fromPhone.single().text).isEqualTo("hello")
    }

    @Test
    fun `a chat list page is kept per query`() = runBlocking {
        server.enqueue(json("""{"sessions": [{"id": "s1", "title": "First", "source": "android"}], "total": 1}"""))
        val (chat, _) = apis()
        chat.list(limit = 50, excludeSources = "cron")
        awaitKept(cacheKey("api/sessions", mapOf("limit" to 50, "offset" to 0, "order" to "recent", "archived" to "exclude", "min_messages" to 1, "exclude_sources" to "cron")))

        assertThat(chat.cached!!.list(limit = 50, excludeSources = "cron").chats.map { it.title }).containsExactly("First")
        assertThat(runCatching { chat.cached!!.list(limit = 50, sources = "cron") }.exceptionOrNull()).isInstanceOf(NotCachedException::class.java)
    }

    @Test
    fun `secrets and logs never reach the phone`() = runBlocking {
        server.enqueue(json("""{"OPENAI_API_KEY": {"is_set": true}}"""))
        server.enqueue(json("""{"lines": ["boot"]}"""))
        server.enqueue(json("""{"jobs": []}"""))
        val (_, admin) = apis()
        admin.env()
        admin.logs(LogQuery())
        admin.cronJobs()
        awaitKept("api/cron/jobs")

        assertThat(cache.read("home", "api/env")).isNull()
        assertThat(runCatching { admin.cached!!.env() }.exceptionOrNull()).isInstanceOf(NotCachedException::class.java)
        assertThat(runCatching { admin.cached!!.logs(LogQuery()) }.exceptionOrNull()).isInstanceOf(NotCachedException::class.java)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `the cache key does not depend on the order of the parameters and skips unset ones`() {
        assertThat(cacheKey("/api/x", mapOf("b" to 2, "a" to 1, "c" to null))).isEqualTo("api/x?a=1&b=2")
        assertThat(cacheKey("api/x", emptyMap())).isEqualTo("api/x")
    }
}
