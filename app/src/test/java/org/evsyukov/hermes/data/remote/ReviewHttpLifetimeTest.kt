package org.evsyukov.hermes.data.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.*
import okio.*
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.auth.Tokens
import org.evsyukov.hermes.data.server.*
import org.evsyukov.hermes.testing.ChatTestResources
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ReviewHttpLifetimeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resources = ChatTestResources()
    private val server = MockWebServer()
    private val received = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val bodyClosed = AtomicBoolean()
    private val gets = AtomicInteger()
    private val refreshes = AtomicInteger()
    @Volatile private var hold401 = false
    private val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code != 401) response else {
            val body = response.body
            val tracked = object : ResponseBody() {
                private val reader = object : ForwardingSource(body.source()) {
                    override fun close() { try { super.close() } finally { bodyClosed.set(true) } }
                }.buffer()
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = reader
            }
            received.countDown()
            if (hold401) check(release.await(10, TimeUnit.SECONDS)) { "401 gate not released" }
            response.newBuilder().body(tracked).build()
        }
    }.build()
    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url.encodedPath.endsWith("/refresh") -> { refreshes.incrementAndGet(); MockResponse.Builder().body("""{"access_token":"refreshed","refresh_token":"refresh","expires_at":4102444800}""").build() }
                gets.incrementAndGet() == 1 -> MockResponse.Builder().code(401).body("unauthorized").build()
                else -> MockResponse.Builder().body("""{"ok":true}""").build()
            }
        }
        server.start()
    }
    @After fun tearDown() {
        val owner = scope.coroutineContext.job
        release.countDown()
        resources.finish(owner, listOf("close server" to { server.close() }))
    }
    private suspend fun session(): Pair<ServerStore, ServerSession> {
        val profile = ServerProfile("review-http", "HTTP", server.url("/").toString())
        val store = resources.store(app, object : TokenCipher { override fun encrypt(plain: String) = plain; override fun decrypt(sealed: String) = sealed }, scope, profile)
        store.setTokens(profile.id, Tokens("access", "refresh", 4102444800, "native"))
        return store to ServerSession(profile, client, store, AppStrings(app.resources))
    }
    @Test fun `L3 retired 401 closes its body and sends no refresh or retry`() = runBlocking<Unit> {
        hold401 = true
        val (store, session) = session()
        val owner = session.owner()
        val request = scope.async { runCatching { session.http.getJson(owner, "api/test") } }
        try {
            assertThat(received.await(10, TimeUnit.SECONDS)).isTrue()
            store.setTokens(owner.profileId, Tokens("replacement"))
            release.countDown()
            val error = withTimeout(10_000) { request.await() }.exceptionOrNull()
            assertThat(error).isInstanceOf(SignInRequiredException::class.java)
            assertThat(gets.get()).isEqualTo(1)
            assertThat(refreshes.get()).isEqualTo(0)
            assertThat(bodyClosed.get()).isTrue()
        } finally { release.countDown(); request.cancelAndJoin() }
    }
    @Test fun `L3 cancelled post401 credential decision closes its response`() = runBlocking<Unit> {
        val reads = AtomicInteger()
        val cancellation = CancellationException("credential decision cancelled")
        val http = HermesHttp(server.url("/"), client, tokens = { if (reads.incrementAndGet() == 2) throw cancellation else Tokens("access") }, strings = AppStrings(app.resources))
        val error = runCatching { http.getJson("api/test") }.exceptionOrNull()
        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(gets.get()).isEqualTo(1)
        assertThat(bodyClosed.get()).isTrue()
    }
    @Test fun `L3 returned anonymous 401 remains caller owned until closed`() = runBlocking<Unit> {
        val http = HermesHttp(server.url("/"), client, tokens = { null }, strings = AppStrings(app.resources))
        val response = http.execute { url(server.url("/api/test")) }
        try { assertThat(response.code).isEqualTo(401); assertThat(bodyClosed.get()).isFalse() } finally { response.close() }
        assertThat(bodyClosed.get()).isTrue()
    }
    @Test fun `L3 current 401 closes first body refreshes and returns retry response`() = runBlocking<Unit> {
        val (_, session) = session()
        val answer = session.http.getJson(session.owner(), "api/test")
        assertThat(answer.toString()).isEqualTo("""{"ok":true}""")
        assertThat(bodyClosed.get()).isTrue()
        assertThat(refreshes.get()).isEqualTo(1)
        assertThat(gets.get()).isEqualTo(2)
    }
}
