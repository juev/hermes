package org.evsyukov.hermes.data.server

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.CallbackPageText
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.auth.Tokens
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.remote.SignInRequiredException
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The native sign-in vertical: a real loopback exchange and dashboard token route, the real
 * DataStore and its authority, and one session that must never borrow credentials across auth.
 * The browser is this test answering the loopback address.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class OwnedNativeAuthTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val client = OkHttpClient()
    private val strings = AppStrings(context.resources)
    private val server = MockWebServer()
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    private val tokenCalls = AtomicInteger()
    private val firstTokenEntered = CountDownLatch(1)
    private val firstTokenRelease = CountDownLatch(1)
    @Volatile
    private var blockFirstToken = false
    @Volatile
    private var tokenAnswer: (Int) -> String = { "new-access" }

    private val storeThread = Executors.newSingleThreadExecutor { Thread(it, "auth-store") }
    private val storeDispatcher: ExecutorCoroutineDispatcher = storeThread.asCoroutineDispatcher()
    private val storeJob = SupervisorJob()
    private val storeScope = CoroutineScope(storeJob + storeDispatcher)
    private val callbacks = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pause = AtomicReference<CountDownLatch?>(null)
    private var store: ServerStore? = null
    private val ownProfiles = mutableSetOf<String>()
    private var priorActive: String? = null
    private val gateOutcomes = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Throwable?>>()
    private val gateFailures = CopyOnWriteArrayList<Throwable>()
    private fun waitGate(latch: CountDownLatch, label: String) {
        val outcome = gateOutcomes.getOrPut(label) { kotlinx.coroutines.CompletableDeferred() }
        var failure: Throwable? = null
        try { check(latch.await(10, TimeUnit.SECONDS)) { "$label gate deadline expired" } } catch (error: Throwable) { failure = error; gateFailures += error; throw error }
        finally { outcome.complete(failure) }
    }


    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += request
                return when (request.url.encodedPath) {
                    "/auth/native/token" -> {
                        val call = tokenCalls.incrementAndGet()
                        if (call == 1 && blockFirstToken) {
                            firstTokenEntered.countDown()
                            try { waitGate(firstTokenRelease, "token") } catch (_: Exception) { return MockResponse.Builder().code(503).body("gate refused").build() }
                        }
                        json("""{"access_token":"${tokenAnswer(call)}","refresh_token":"refresh-$call","expires_at":4102444800,"provider":"native"}""")
                    }
                    "/api/sessions/s1" -> json("""{"id":"s1","title":"S1","source":"android","started_at":40.0,"last_active":50.0,"message_count":2}""")
                    else -> MockResponse.Builder().code(404).body("{}").build()
                }
            }
        }
        server.start()
    }

    private val cleanup = org.evsyukov.hermes.testing.FixtureCleanup()
    @After
    fun tearDown() {
        val callbackJob = callbacks.coroutineContext[Job]!!
        cleanup.finish(
            listOf(callbackJob, storeJob),
            listOf("release gates" to {
                pause.get()?.countDown(); firstTokenRelease.countDown(); cipher.encryptRelease.countDown()
                cipher.blockEncrypt = false; cipher.failing = false
            }),
            listOf(
                "reset profiles" to { store?.let { current -> runBlocking { org.evsyukov.hermes.testing.resetFixtureProfiles(current, ownProfiles) } } },
                "restore active" to { store?.let { current -> runBlocking { priorActive?.let { active -> if (current.load().servers.any { it.id == active }) current.activate(active) } } } },
                "close dispatcher" to { storeDispatcher.close() },
                "close server" to { server.close() },
                "gate failures" to { gateFailures.firstOrNull()?.let { first -> gateFailures.drop(1).filter { it !== first }.forEach(first::addSuppressed); throw first } },
            ),
        )
    }

    @Test
    fun `native sign-in renews the owner and old captured API cannot borrow the new credentials`() = runBlocking<Unit> {
        val profile = profile("native-auth-renew", server)
        val store = newStore()
        seed(store, profile, "old-access")
        val session = ServerSession(profile, client, store, strings)
        val oldOwner = session.owner()
        val oldApi = session.chatFor(oldOwner)

        val result = signIn(session)

        assertThat(result).isInstanceOf(NativeResult.Applied::class.java)
        val applied = result as NativeResult.Applied
        val exchange = request("/auth/native/token")
        assertThat(exchange.method).isEqualTo("POST")
        val body = HermesJson.parseToJsonElement(exchange.body!!.utf8()).jsonObject
        assertThat(body["code"]!!.jsonPrimitive.content).isEqualTo("native-code")
        assertThat(body["code_verifier"]!!.jsonPrimitive.content).isNotEmpty()
        assertThat(store.load().tokens[profile.id]?.accessToken).isEqualTo("new-access")

        val newOwner = session.owner()
        assertThat(applied.owner).isEqualTo(newOwner)
        assertThat(newOwner).isNotEqualTo(oldOwner)
        assertThat(store.authority.credentials(profile.id, newOwner.generation)?.accessToken).isEqualTo("new-access")

        val fresh = session.chatFor(newOwner).get("s1")
        assertThat(fresh?.title).isEqualTo("S1")
        assertThat(authorization("/api/sessions/s1")).isEqualTo("Bearer new-access")

        val readsBefore = requestsTo("/api/sessions/s1")
        val refused = runCatching { oldApi.get("s1") }.exceptionOrNull()
        assertThat(refused).isInstanceOf(SignInRequiredException::class.java)
        assertThat(requestsTo("/api/sessions/s1")).isEqualTo(readsBefore)
    }

    @Test
    fun `an authenticated read uses the credentials just committed while the store state still lags`() = runBlocking<Unit> {
        val profile = profile("native-auth-fresh-bytes", server)
        val store = newStore()
        seed(store, profile, "old-access")
        withTimeout(10_000) { store.state.first { it.tokens[profile.id]?.accessToken == "old-access" } }
        holdStoreState()
        val session = ServerSession(profile, client, store, strings)

        val result = signIn(session)

        assertThat(result).isInstanceOf(NativeResult.Applied::class.java)
        assertThat(store.load().tokens[profile.id]?.accessToken).isEqualTo("new-access")
        // The collector of ServerStore.state is deliberately held, so the old value is still visible.
        assertThat(store.state.value.tokens[profile.id]?.accessToken).isEqualTo("old-access")

        val owner = session.owner()
        assertThat(session.chatFor(owner).get("s1")?.title).isEqualTo("S1")
        val read = request("/api/sessions/s1")
        assertThat(read.headers["Authorization"]).isEqualTo("Bearer new-access")
        assertThat(store.load().tokens[profile.id]?.accessToken).isEqualTo("new-access")
    }

    @Test
    fun `a competing sign-in rejects the stale attempt without writing its tokens`() = runBlocking<Unit> {
        val profile = profile("native-auth-competing", server)
        val store = newStore()
        seed(store, profile, "old-access")
        val session = ServerSession(profile, client, store, strings)
        val oldOwner = session.owner()
        blockFirstToken = true
        configureTokens { call -> if (call == 1) "first-access" else "second-access" }

        val first = async(Dispatchers.Default) { signIn(session) }
        assertThat(firstTokenEntered.await(10, TimeUnit.SECONDS)).isTrue()
        val second = async(Dispatchers.Default) { signIn(session) }
        val secondResult = second.await()
        assertThat(secondResult).isInstanceOf(NativeResult.Applied::class.java)
        firstTokenRelease.countDown()
        val firstResult = first.await()

        assertThat(firstResult).isEqualTo(NativeResult.Rejected)
        val secondOwner = (secondResult as NativeResult.Applied).owner
        assertThat(secondOwner).isNotEqualTo(oldOwner)
        assertThat(store.load().tokens[profile.id]?.accessToken).isEqualTo("second-access")
        assertThat(store.authority.credentials(profile.id, secondOwner.generation)?.accessToken).isEqualTo("second-access")
        assertThat(session.chatFor(secondOwner).get("s1")?.title).isEqualTo("S1")
        assertThat(authorization("/api/sessions/s1")).isEqualTo("Bearer second-access")
    }

    @Test
    fun `a sign-out during a native exchange rejects the stale sign-in`() = runBlocking<Unit> {
        val profile = profile("native-auth-retire", server)
        val store = newStore()
        seed(store, profile, "old-access")
        val session = ServerSession(profile, client, store, strings)
        val oldOwner = session.owner()
        blockFirstToken = true

        val attempt = async(Dispatchers.Default) { signIn(session) }
        assertThat(firstTokenEntered.await(10, TimeUnit.SECONDS)).isTrue()
        // The user signs out while the browser exchange of this attempt is still in flight.
        session.signOut(profile.id)
        val retiredOwner = session.owner()
        assertThat(retiredOwner).isNotEqualTo(oldOwner)
        firstTokenRelease.countDown()
        val result = attempt.await()

        assertThat(result).isEqualTo(NativeResult.Rejected)
        assertThat(store.load().tokens[profile.id]).isNull()
        assertThat(session.owner()).isEqualTo(retiredOwner)
        assertThat(store.authority.credentials(profile.id, retiredOwner.generation)).isNull()
        assertThat(requestsTo("/api/sessions/s1")).isEqualTo(0)
    }

    @Test
    fun `a storage failure keeps the previous owner and credentials`() = runBlocking<Unit> {
        val profile = profile("native-auth-storage-failure", server)
        val store = newStore()
        seed(store, profile, "old-access")
        val session = ServerSession(profile, client, store, strings)
        val oldOwner = session.owner()
        cipher.failing = true

        val error = runCatching { signIn(session) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(error).hasMessageThat().contains("storage is unavailable")
        assertThat(store.load().tokens[profile.id]?.accessToken).isEqualTo("old-access")
        assertThat(session.owner()).isEqualTo(oldOwner)
        assertThat(store.authority.credentials(profile.id, oldOwner.generation)?.accessToken).isEqualTo("old-access")
        assertThat(requestsTo("/api/sessions/s1")).isEqualTo(0)
    }

    @Test
    fun `an admitted commit publishes even when the caller is cancelled at the write`() = runBlocking<Unit> {
        val profile = profile("native-auth-cancel", server)
        val store = newStore()
        seed(store, profile, "old-access")
        val session = ServerSession(profile, client, store, strings)
        val oldOwner = session.owner()
        cipher.blockEncrypt = true

        val job = launch(Dispatchers.Default) { signIn(session) }
        assertThat(cipher.encryptEntered.await(10, TimeUnit.SECONDS)).isTrue()
        job.cancel()
        cipher.encryptRelease.countDown()
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(store.load().tokens[profile.id]?.accessToken).isEqualTo("new-access")
        val owner = session.owner()
        assertThat(owner).isNotEqualTo(oldOwner)
        assertThat(store.authority.credentials(profile.id, owner.generation)?.accessToken).isEqualTo("new-access")
        assertThat(session.chatFor(owner).get("s1")?.title).isEqualTo("S1")
        assertThat(authorization("/api/sessions/s1")).isEqualTo("Bearer new-access")
    }

    @Test fun `T1 actual native token gate refuses an unreleased deadline`() = runBlocking<Unit> {
        blockFirstToken = true
        val request = callbacks.async { runCatching { client.newCall(Request.Builder().url(server.url("/auth/native/token")).post("{}".toBodyForGate()).build()).execute().use { it.code } } }
        try {
            assertThat(firstTokenEntered.await(10, TimeUnit.SECONDS)).isTrue()
            val failure = withTimeout(11_000) { while (gateOutcomes["token"] == null) kotlinx.coroutines.delay(5); gateOutcomes.getValue("token").await() }
            try { assertThat(failure).isInstanceOf(IllegalStateException::class.java) } finally { if (failure != null) gateFailures.remove(failure) }
        } finally { firstTokenRelease.countDown(); request.cancel(); withTimeout(10_000) { request.join() } }
    }

    private fun String.toBodyForGate() = toRequestBody()
    @Test fun `T1 actual state dispatcher gate refuses an unreleased deadline`() = runBlocking<Unit> {
        holdStoreState()
        val outcome = withTimeout(11_000) { while (gateOutcomes["state"] == null) kotlinx.coroutines.delay(5); gateOutcomes.getValue("state").await() }
        try { assertThat(outcome).isInstanceOf(IllegalStateException::class.java) } finally { if (outcome != null) gateFailures.remove(outcome) }
    }
    @Test fun `T1 actual credential cipher gate refuses an unreleased deadline`() = runBlocking<Unit> {
        cipher.blockEncrypt = true
        val outcome = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { cipher.encrypt("synthetic credential bytes") }.exceptionOrNull() }
        try { assertThat(outcome).isInstanceOf(IllegalStateException::class.java) } finally { if (outcome != null) gateFailures.remove(outcome) }
    }
    @Test fun `T1 released native state and cipher gates complete normally`() = runBlocking<Unit> {
        firstTokenRelease.countDown(); blockFirstToken = true
        kotlinx.coroutines.withContext(Dispatchers.IO) { client.newCall(Request.Builder().url(server.url("/auth/native/token")).post("{}".toBodyForGate()).build()).execute().close() }
        holdStoreState(); pause.get()!!.countDown()
        cipher.blockEncrypt = true; cipher.encryptRelease.countDown(); cipher.encrypt("synthetic")
        withTimeout(10_000) { while (gateOutcomes.size != 3) kotlinx.coroutines.delay(5); gateOutcomes.values.forEach { assertThat(it.await()).isNull() } }
    }

    private suspend fun newStore(): ServerStore = ServerStore(context, cipher, storeScope).also {
        store = it
        priorActive = it.load().activeId
    }

    private suspend fun seed(store: ServerStore, profile: ServerProfile, accessToken: String) {
        ownProfiles += profile.id
        store.save(profile, makeActive = true)
        store.setTokens(profile.id, Tokens(accessToken, "old-refresh", 4102444800, "native"))
        withTimeout(10_000) { store.state.first { it.active?.id == profile.id } }
    }

    /** Occupies the store's only dispatcher thread so the stateIn collector cannot publish an update. */
    private fun holdStoreState() {
        val latch = CountDownLatch(1)
        pause.set(latch)
        storeThread.execute { try { waitGate(latch, "state") } catch (_: Exception) { /* reported through gateFailures and outcome */ } }
    }

    private suspend fun signIn(session: ServerSession): NativeResult = session.signIn(
        provider = null,
        returnUrl = RETURN_URL,
        page = CallbackPageText("Signed in", "Sign-in failed", "Back"),
    ) { url ->
        checkAuthorize(url)
        val parts = url.toHttpUrl()
        val state = parts.queryParameter("state").orEmpty()
        val callback = parts.queryParameter("redirect_uri").orEmpty() + "?code=native-code&state=" + state
        // The browser part: answer the loopback address once signIn starts waiting on it.
        callbacks.launch {
            client.newCall(Request.Builder().url(callback).build()).execute().close()
        }
    }

    private fun checkAuthorize(url: String) {
        val opened = url.toHttpUrl()
        assertThat(opened.encodedPath).isEqualTo("/auth/native/authorize")
        assertThat(opened.queryParameter("code_challenge")).isNotEmpty()
        assertThat(opened.queryParameter("code_challenge_method")).isEqualTo("S256")
        assertThat(opened.queryParameter("state")).isNotEmpty()
        assertThat(opened.queryParameter("redirect_uri")).startsWith("http://127.0.0.1:")
        assertThat(opened.queryParameter("redirect_uri")).endsWith("/callback")
    }

    private fun request(path: String): RecordedRequest {
        val call = calls.firstOrNull { it.url.encodedPath == path }
        assertThat(call).isNotNull()
        return call!!
    }

    private fun authorization(path: String): String? = request(path).headers["Authorization"]

    private fun requestsTo(path: String): Int = calls.count { it.url.encodedPath == path }

    private fun configureTokens(answer: (Int) -> String) {
        tokenAnswer = answer
    }

    private fun profile(id: String, server: MockWebServer) = ServerProfile(
        id = id,
        name = id,
        baseUrl = server.url("/").toString(),
        proxy = ProxySettings(ProxyType.NONE),
    )

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private val cipher = GatedCipher()

    private inner class GatedCipher : TokenCipher {
        @Volatile var failing = false
        @Volatile var blockEncrypt = false
        val encryptEntered = CountDownLatch(1)
        val encryptRelease = CountDownLatch(1)

        override fun encrypt(plain: String): String {
            if (blockEncrypt) {
                encryptEntered.countDown()
                waitGate(encryptRelease, "encrypt")
            }
            if (failing) throw IOException("storage is unavailable")
            return plain
        }

        override fun decrypt(sealed: String): String = sealed
    }

    private companion object {
        const val RETURN_URL = "org.evsyukov.hermes://signed-in"
    }
}
