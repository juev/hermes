package org.evsyukov.hermes.data.server

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.auth.Tokens
import org.evsyukov.hermes.data.cache.CacheDatabase
import org.evsyukov.hermes.data.cache.ResponseCache
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A response already on its way when the user signs out (or the profile leaves the phone) must not
 * put the server back into the cache after it was dropped: the session that received it is done.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class SessionRetiredWriterTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val cache = ResponseCache(db.responses(), scope, { Long.MAX_VALUE })
    private val server = MockWebServer()
    private val blockAnswer = AtomicBoolean(false)
    private val answerRequested = CountDownLatch(1)
    private val releaseAnswer = CountDownLatch(1)
    private val gateOutcome = kotlinx.coroutines.CompletableDeferred<Throwable?>()
    private val gateFailures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    internal val fixtureCleanup = org.evsyukov.hermes.testing.FixtureCleanup()
    internal fun fixtureProducerScope() = scope
    internal val fixtureDatabaseOpen get() = db.isOpen
    internal var resetFailure: RuntimeException? = null
    internal var closeFailure: RuntimeException? = null
    private fun waitAnswer() {
        var failure: Throwable? = null
        try { check(releaseAnswer.await(10, TimeUnit.SECONDS)) { "writer HTTP gate deadline expired" } } catch (error: Throwable) { failure = error; gateFailures += error; throw error }
        finally { gateOutcome.complete(failure) }
    }


    @Before
    fun setUp() {
        db.openHelper.writableDatabase
        cache.producers
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (blockAnswer.get()) {
                    answerRequested.countDown()
                    try { waitAnswer() } catch (_: Exception) { return MockResponse.Builder().code(503).body("gate refused").build() }
                }
                return MockResponse.Builder().addHeader("Content-Type", "application/json")
                    .body("""{"sessions":[],"total":0}""").build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        val owner = scope.coroutineContext[kotlinx.coroutines.Job]!!
        fixtureCleanup.finish(
            listOf(owner),
            listOf(
                "release answer" to { releaseAnswer.countDown() },
                "reset profile" to { resetFailure?.let { throw it }; runBlocking { org.evsyukov.hermes.testing.resetFixtureProfiles(ServerStore(context, PlainCipher, scope), listOf("retired-writer")) } },
            ),
            listOf(
                "close server" to { server.close() },
                "close Room" to { db.close() },
                "report close failure" to { closeFailure?.let { throw it } },
                "report gate failure" to { gateFailures.firstOrNull()?.let { throw it } },
            ),
        )
    }

    @Test
    fun `an answer that arrives after signing out is not kept again`() = runBlocking<Unit> {
        val profile = ServerProfile("retired-writer", "Home", server.url("/").toString())
        val store = ServerStore(context, PlainCipher, scope)
        store.save(profile, makeActive = true)
        store.setTokens(profile.id, Tokens("access"))
        val session = ServerSession(profile, OkHttpClient(), store, AppStrings(context.resources), cache)

        // The ordinary path still fills the cache before the sign-out.
        session.chat.list()
        withTimeout(10_000) { while (cache.size() == 0L) delay(10) }

        cache.clear()
        blockAnswer.set(true)
        val late = async(Dispatchers.IO) { session.chat.list() }
        assertThat(answerRequested.await(10, TimeUnit.SECONDS)).isTrue()

        // Signing out clears the server and retires this session while its answer is in flight.
        session.signOut(profile.id)
        releaseAnswer.countDown()
        late.await()
        // The ordinary path keeps its answer in the background; give such a write the chance to show.
        awaitActualCacheProducers()

        assertThat(cache.size()).isEqualTo(0)
        assertThat(store.load().tokens[profile.id]).isNull()
    }

    private suspend fun awaitActualCacheProducers() { withTimeout(10_000) { cache.producers.toList().forEach { it.join(); check(it.isCompleted) } } }

    @Test fun `T1 actual writer HTTP gate refuses an unreleased deadline`() = runBlocking<Unit> {
        blockAnswer.set(true)
        val request = async(Dispatchers.IO) { runCatching { OkHttpClient().newCall(okhttp3.Request.Builder().url(server.url("/gate")).build()).execute().use { it.code } } }
        try {
            assertThat(answerRequested.await(10, TimeUnit.SECONDS)).isTrue()
            val failure = withTimeout(11_000) { gateOutcome.await() }
            try { assertThat(failure).isInstanceOf(IllegalStateException::class.java) } finally { if (failure != null) gateFailures.remove(failure) }
        } finally { releaseAnswer.countDown(); request.cancel(); withTimeout(10_000) { request.join() } }
    }

    @Test fun `T2 negative cache oracle waits for actual held keepOwned producer`() = runBlocking<Unit> {
        val profile = ServerProfile("retired-writer", "Home", server.url("/").toString())
        val store = ServerStore(context, PlainCipher, scope)
        store.save(profile)
        val session = ServerSession(profile, OkHttpClient(), store, AppStrings(context.resources), cache)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        cache.producers
        cache.beforeOwnedWrite = { entered.complete(Unit); release.await() }
        session.chat.list()
        val check = async(Dispatchers.IO) { awaitActualCacheProducers() }
        try {
            withTimeout(10_000) { entered.await() }
            val completed = kotlinx.coroutines.withTimeoutOrNull(500) { check.join(); true } == true
            assertThat(completed).isFalse()
            assertThat(cache.producers.single().isCompleted).isFalse()
            release.complete(Unit)
            withTimeout(10_000) { check.join(); cache.producers.single().join() }
            assertThat(cache.producers.single().isCompleted).isTrue()
            assertThat(cache.size()).isGreaterThan(0L)
        } finally { release.complete(Unit); withTimeout(10_000) { check.join(); cache.producers.toList().forEach { it.join() } }; cache.beforeOwnedWrite = null }
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}
