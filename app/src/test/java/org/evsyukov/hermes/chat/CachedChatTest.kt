package org.evsyukov.hermes.chat

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import org.evsyukov.hermes.testing.ChatTestResources
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import mockwebserver3.MockResponse
import org.evsyukov.hermes.data.remote.NotCachedException
import org.evsyukov.hermes.data.remote.SignInRequiredException
import org.evsyukov.hermes.data.server.NativeResult
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
import org.evsyukov.hermes.ui.ListMode
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class CachedChatTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val resources = ChatTestResources()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wires = mutableListOf<MockWebServer>()
    private val cache = ResponseCache(db.responses(), scope, { Long.MAX_VALUE })

    @After
    fun tearDown() {
        val owner = scope.coroutineContext.job
        resources.finish(owner, wires.mapIndexed { i, wire -> "close cache wire $i" to { wire.close() } } + listOf("close database" to { db.close() }))
    }

    @Test
    fun `a chat kept on the phone opens with its history while the server is unreachable`() = runBlocking<Unit> {
        val strings = AppStrings(context.resources)
        // Nothing listens on port 9 of this machine: every request to the server fails.
        val profile = ServerProfile("home", "Home", "http://127.0.0.1:9")
        val session = ServerSession(profile, OkHttpClient(), resources.store(context, PlainCipher, scope, profile), strings, cache)
        cache.write("home", cacheKey("api/sessions/s1", emptyMap()), """{"id": "s1", "title": "Plans"}""")
        cache.write(
            "home",
            cacheKey("api/sessions/s1/messages", mapOf("limit" to 500, "order" to "oldest", "inline_images" to false)),
            """{"messages": [{"id": 1, "role": "user", "content": "hello"}]}""",
        )

        val chat = ChatController(session, resources.socket(GatewaySocket(session.http, scope, strings)), scope, strings, "s1")
        val failed = withTimeout(10_000) { chat.state.first { it.error != null } }
        chat.close()

        assertThat(failed.title).isEqualTo("Plans")
        assertThat(failed.items.map { (it as ChatItem.User).text }).containsExactly("hello")
    }

    @Test
    fun `a list kept on the phone keeps only the local chats whose creator is interactive`() = runBlocking<Unit> {
        val strings = AppStrings(context.resources)
        val profile = ServerProfile("home", "Home", "http://127.0.0.1:9")
        val session = ServerSession(profile, OkHttpClient(), resources.store(context, PlainCipher, scope, profile), strings, cache)
        val mode = ListMode.CHATS
        val query = mapOf(
            "limit" to 50,
            "offset" to 0,
            "order" to "recent",
            "archived" to "exclude",
            "min_messages" to 1,
            "sources" to null,
            "exclude_sources" to mode.excludeSources,
        )
        cache.write(
            "home",
            cacheKey("api/sessions", query),
            """{"sessions": [
                {"id": "talk", "source": "local", "created_source": "desktop"},
                {"id": "job", "source": "local", "created_source": "oneshot"}
            ], "total": 2}""",
        )

        // The exact key is already durable; an owned miss must not be mistaken for an unfinished write.
        assertThat(cache.read(profile.id, cacheKey("api/sessions", query))).isNotNull()
        assertThat(session.holds(session.owner())).isTrue()
        val page = session.chat.cached!!.list(archived = mode.archived, sources = mode.sources, excludeSources = mode.excludeSources)

        assertThat(page.chats.map { it.createdSource }).containsExactly("desktop", "oneshot").inOrder()
        assertThat(mode.keep(page.chats).map { it.id }).containsExactly("talk")
    }

    @Test
    fun `current cache writes and reads share authority while retired reads and actual writers are rejected`() = runBlocking<Unit> {
        val wire = MockWebServer().also { wires += it; it.start() }
        val profile = ServerProfile("cache-owned", "Owned cache", wire.url("/").toString())
        val store = resources.store(context, PlainCipher, scope, profile)
        val session = ServerSession(profile, OkHttpClient(), store, AppStrings(context.resources), cache)
        val origin = session.owner()
        assertThat(store.load().servers.map { it.id }).contains(profile.id)
        assertThat(origin.generation).isGreaterThan(0L)
        assertThat(session.holds(origin)).isTrue()
        val writers = cache.producers // Observe the actual keepOwned jobs before the requests.
        wire.enqueue(MockResponse.Builder().body("""{"sessions":[{"id":"owned","title":"from actual network"}],"total":1}""").build())
        val live = session.chatFor(origin).list()
        withTimeout(10_000) { writers.toList().forEach { it.join() } }
        val cached = session.chatFor(origin).cached!!.list()
        assertThat(live.chats.single().id).isEqualTo("owned")
        assertThat(cached.chats.single().title).isEqualTo("from actual network")
        val request = checkNotNull(wire.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS))
        val key = cacheKey(request.url.encodedPath, request.url.queryParameterNames.associateWith { request.url.queryParameter(it) })
        assertThat(cache.readOwned(origin, store.authority, key)).isNotNull()

        assertThat(session.signOut(profile.id, origin)).isInstanceOf(NativeResult.Applied::class.java)
        assertThat(session.holds(origin)).isFalse()
        val requests = wire.requestCount
        assertThat(runCatching { session.chatFor(origin).list() }.exceptionOrNull()).isInstanceOf(SignInRequiredException::class.java)
        assertThat(runCatching { session.chatFor(origin).cached!!.list() }.exceptionOrNull()).isInstanceOf(NotCachedException::class.java)
        assertThat(wire.requestCount).isEqualTo(requests)
        val staleWriter = cache.keepOwned(origin, store.authority, key, "retired bytes") { true }
        withTimeout(10_000) { staleWriter.join() }
        assertThat(cache.read(profile.id, key)).isNull()

        val fresh = session.owner()
        assertThat(session.holds(fresh)).isTrue()
        session.resumeLocalData(fresh)
        wire.enqueue(MockResponse.Builder().body("""{"sessions":[{"id":"fresh","title":"fresh actual network"}],"total":1}""").build())
        session.chatFor(fresh).list()
        withTimeout(10_000) { writers.toList().forEach { it.join() } }
        assertThat(session.chatFor(fresh).cached!!.list().chats.single().id).isEqualTo("fresh")
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}
