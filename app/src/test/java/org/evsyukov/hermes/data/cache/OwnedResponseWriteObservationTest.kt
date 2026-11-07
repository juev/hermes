package org.evsyukov.hermes.data.cache

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.remote.cacheKey
import org.evsyukov.hermes.data.server.*
import org.evsyukov.hermes.testing.resetFixtureProfiles
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class OwnedResponseWriteObservationTest {
    private data class Notice(val owner: WorkOwner, val key: String, val job: Job)
    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }

    @Test
    fun ownedResponseWriteReportsPreadmissionProvenance() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val roots = listOf(SupervisorJob(), SupervisorJob(), SupervisorJob())
        val cacheScope = CoroutineScope(roots[0] + Dispatchers.IO)
        val storeScope = CoroutineScope(roots[1] + Dispatchers.IO)
        val operations = CoroutineScope(roots[2] + Dispatchers.IO)
        val jobs = CopyOnWriteArrayList<Job>()
        val notices = CopyOnWriteArrayList<Notice>()
        val events = CopyOnWriteArrayList<String>()
        val selectedSecond = CompletableDeferred<Job>()
        val held = CompletableDeferred<Notice>()
        val release = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        var db: CacheDatabase? = null
        var server: MockWebServer? = null
        var store: ServerStore? = null
        var cache: ResponseCache? = null
        val client = OkHttpClient()
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() } catch (t: Throwable) {
                val primary = failure
                if (primary == null) failure = t else if (primary !== t) primary.addSuppressed(t)
            }
        }
        suspend fun normal(job: Job) {
            val outcome = CompletableDeferred<Throwable?>()
            job.invokeOnCompletion { outcome.complete(it) }
            withTimeout(10_000) { job.join() }
            check(job.isCompleted && !job.isCancelled) { "actual producer did not complete normally" }
            outcome.await()?.let { throw it }
        }
        fun key(offset: Int) = cacheKey("api/sessions", mapOf(
            "limit" to 50, "offset" to offset, "order" to "recent",
            "archived" to "exclude", "min_messages" to 1,
            "sources" to null, "exclude_sources" to null,
        ))
        try {
            withTimeout(45_000) {
            val database = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java)
                .allowMainThreadQueries().build().also { db = it }
            val http = MockWebServer().also { server = it; it.start() }
            val ownedStore = ServerStore(context, PlainCipher, storeScope).also { store = it }
            val a = ServerProfile("observer-synthetic-a", "Observer A", http.url("/").toString())
            val b = ServerProfile("observer-synthetic-b", "Observer B", http.url("/").toString())
            resetFixtureProfiles(ownedStore, listOf(a.id, b.id))
            ownedStore.save(a)
            ownedStore.save(b)
            val responses = ResponseCache(database.responses(), cacheScope, { Long.MAX_VALUE }).also { cache = it }
            // Opt in before any eager producer can launch.
            val producers = responses.producers
            val sessionA = ServerSession(a, client, ownedStore, AppStrings(context.resources), responses)
            val sessionB = ServerSession(b, client, ownedStore, AppStrings(context.resources), responses)
            val ownerA = sessionA.owner()
            val ownerB = sessionB.owner()
            responses.beforeOwnedWriteObservation = { owner, request, child ->
                val notice = Notice(owner, request, child)
                notices += notice
                if (owner === ownerB && request == key(1)) {
                    val selected = withTimeout(10_000) { selectedSecond.await() }
                    if (child === selected && producers.any { it === child }) {
                        events += "B-held"
                        held.complete(notice)
                        release.await()
                        events += "B-released"
                    }
                }
            }
            val firstBody = "{\"sessions\":[],\"total\":11}"
            http.enqueue(MockResponse.Builder().body(firstBody).build())
            val firstRequest = operations.async { sessionB.chatFor(ownerB).list() }.also { jobs += it }
            assertThat(withTimeout(10_000) { firstRequest.await() }.total).isEqualTo(11)
            val first = producers.single()
            normal(first)
            assertThat(database.responses().response(b.id, key(0))?.request).isEqualTo(key(0))
            assertThat(database.responses().parts(b.id, key(0)).joinToString("")).isEqualTo(firstBody)
            assertThat(responses.read(b.id, key(0))).isEqualTo(firstBody)
            println("OPPORTUNITY: actual B producer normal; DAO and cache full query key/body verified: ${key(0)}")
            val matches = notices.filter { it.owner === ownerB && it.key == key(0) && it.job === first }
            assertWithMessage("OWNED_RESPONSE_WRITE_PREADMISSION_OBSERVATION_ABSENT")
                .that(matches).isNotEmpty()

            // Frozen positive continuation: reached only after a real matching notification exists.
            val firstNotice = matches.single()
            assertThat(firstNotice.owner).isSameInstanceAs(ownerB)
            assertThat(firstNotice.job).isSameInstanceAs(first)
            assertThat(firstNotice.key).isEqualTo(key(0))
            http.enqueue(MockResponse.Builder().body(firstBody).build())
            val seed = operations.async { sessionA.chatFor(ownerA).list() }.also { jobs += it }
            withTimeout(10_000) { seed.await() }
            normal(producers.last())
            assertThat(database.responses().response(a.id, key(0))).isNotNull()
            val secondBody = "{\"sessions\":[],\"total\":22}"
            http.enqueue(MockResponse.Builder().body(secondBody).build())
            val prior = producers.toList()
            val secondRequest = operations.async { sessionB.chatFor(ownerB).list(offset = 1) }.also { jobs += it }
            assertThat(withTimeout(10_000) { secondRequest.await() }.total).isEqualTo(22)
            val second = producers.filter { candidate -> prior.none { it === candidate } }.single()
            selectedSecond.complete(second)
            val secondNotice = withTimeout(10_000) { held.await() }
            assertThat(secondNotice.owner).isSameInstanceAs(ownerB)
            assertThat(secondNotice.job).isSameInstanceAs(second)
            assertThat(secondNotice.key).isEqualTo(key(1))
            assertThat(second.isCompleted).isFalse()
            assertThat(database.responses().response(b.id, key(1))).isNull()
            val retirement = operations.async { sessionA.signOut(a.id, ownerA) }.also { jobs += it }
            val result = withTimeout(10_000) { retirement.await() }
            check(result is NativeResult.Applied && result.cleanupError == null && !result.cleanupSuperseded)
            normal(retirement)
            assertThat(database.responses().response(a.id, key(0))).isNull()
            events += "A-cleanup-effect"
            assertThat(release.isCompleted).isFalse()
            assertThat(second.isCompleted).isFalse()
            assertThat(ownedStore.authority.holds(ownerB.profileId, ownerB.generation)).isTrue()
            release.complete(Unit)
            normal(second)
            assertThat(database.responses().parts(b.id, key(1)).joinToString("")).isEqualTo(secondBody)
            assertThat(responses.read(b.id, key(1))).isEqualTo(secondBody)
            events += "B-write-effect"
            assertThat(events.toList()).containsExactly("B-held", "A-cleanup-effect", "B-released", "B-write-effect").inOrder()
            }
        } catch (t: Throwable) {
            failure = t
        } finally {
            // Release independently before joining; retain body primary and ordered cleanup failures.
            release.complete(Unit)
            selectedSecond.cancel()
            var joined = true
            attempt {
                withTimeout(10_000) {
                    jobs.forEach { it.join() }
                    cache?.producers?.toList()?.forEach { it.join() }
                }
            }
            joined = jobs.all { it.isCompleted } && cache?.producers?.all { it.isCompleted } != false
            if (joined) attempt { withTimeout(10_000) { store?.let { resetFixtureProfiles(it, listOf("observer-synthetic-a", "observer-synthetic-b")) } } }
            roots.forEach { it.cancel() }
            attempt { withTimeout(10_000) { roots.forEach { it.join() } } }
            joined = joined && roots.all { it.isCompleted }
            if (joined) {
                attempt { db?.close() }
                attempt { server?.close() }
                attempt { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            } else attempt { error("dependent Room/HTTP close refused: producer/root remains unjoined") }
        }
        failure?.let { throw it }
    }
}
