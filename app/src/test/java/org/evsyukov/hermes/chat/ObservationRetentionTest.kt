package org.evsyukov.hermes.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.auth.Tokens
import org.evsyukov.hermes.data.server.*
import org.evsyukov.hermes.testing.ChatTestResources
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ObservationRetentionTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + Dispatchers.IO)
    private val resources = ChatTestResources()
    private val a = ServerProfile("observation-a", "A", "https://a.example.org")
    private val b = ServerProfile("observation-b", "B", "https://b.example.org")
    private val store = resources.store(context, PlainCipher, scope, a)
    private val session = ServerSession(a, OkHttpClient(), store, AppStrings(context.resources), null)
    @After fun close() { store.authority.beforeCleanupAdmission = null; resources.finish(root, emptyList()) }
    private suspend fun saved(profile: ServerProfile = a, chat: String = "same"): LocalObservation {
        if (profile == b) resources.store(context, PlainCipher, scope, b)
        val owner = store.authority.owner(profile.id)
        val row = LocalObservation(profile = profile.id, lineage = profile.url.toString(), session = chat, generation = owner.generation,
            keys = listOf("local"), items = listOf(ChatItem.User("local", "prompt", emptyList()), ChatItem.Assistant("live", listOf(Part.Text("complete reply")), false)), completed = true,
            files = listOf(ObservationFile("note.txt", "text/plain", "AQ==", "local")))
        store.observations.put(owner, store.observations.capture(owner.profileId, row.lineage, chat), row)
        return row
    }
    private suspend fun load(profile: ServerProfile = a, chat: String = "same") = store.observations.load(store.authority.owner(profile.id), profile.url.toString(), chat)
    @Test fun `a second store wrapper recovers complete local observations and presentation without cache`() = runBlocking {
        val row = saved()
        val owner = session.owner()
        val ticket = store.observations.capture(a.id, row.lineage, row.session)
        store.observations.presentation(owner, ticket, row.id, true)
        val replacement = ServerStore(context, PlainCipher, scope)
        val recoveredRows = replacement.observations.load(owner, row.lineage, row.session)
        assertThat(recoveredRows).hasSize(1)
        val recovered = recoveredRows.single()
        assertThat(recovered.items).isEqualTo(row.items)
        assertThat(recovered.files).isEqualTo(row.files)
        assertThat(recovered.collapsed).isTrue()
        replacement.observations.presentation(owner, ticket, row.id, false)
        assertThat(load().single().collapsed).isFalse()
        assertThat(load().single().items).isEqualTo(row.items)
    }
    @Test fun `explicit chat clear rejects the captured writer and keeps same ID on another profile`() = runBlocking {
        val row = saved(); val other = saved(b); val sibling = saved(chat = "other")
        val owner = session.owner(); val ticket = store.observations.capture(a.id, row.lineage, row.session)
        session.forgetChat("same", owner)
        store.observations.stage(owner, ticket, row.copy(revision = 9))
        store.observations.put(owner, ticket, row.copy(revision = 9))
        assertThat(load()).isEmpty()
        assertThat(load(b).single().id).isEqualTo(other.id)
        assertThat(load(chat = "other").single().id).isEqualTo(sibling.id)
        val fresh = saved()
        assertThat(load().single().id).isEqualTo(fresh.id)
    }
    @Test fun `signout clears only captured profile and retired producer cannot recreate it`() = runBlocking {
        val row = saved(); val other = saved(b)
        val owner = session.owner(); val ticket = store.observations.capture(a.id, row.lineage, row.session)
        val result = session.signOut(a.id, owner) as NativeResult.Applied
        assertThat(result.cleanupError).isNull(); assertThat(result.cleanupSuperseded).isFalse()
        store.observations.put(owner, ticket, row.copy(revision = 9))
        assertThat(load()).isEmpty(); assertThat(load(b).single().id).isEqualTo(other.id)
        assertThat(session.holds(owner)).isFalse()
    }
    @Test fun `new authorization wins before old signout cleanup admission without losing fresh data`() = runBlocking {
        saved()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val old = session.owner()
        store.authority.beforeCleanupAdmission = { owner, removed -> if (!removed && owner.profileId == a.id) { entered.complete(Unit); release.await() } }
        val job = async(Dispatchers.IO) { session.signOut(a.id, old) }
        try {
            withTimeout(10_000) { entered.await() }
            assertThat(store.setTokens(a.id, Tokens("synthetic-new"))).isInstanceOf(NativeResult.Applied::class.java)
            val fresh = saved()
            release.complete(Unit)
            val result = withTimeout(10_000) { job.await() } as NativeResult.Applied
            assertThat(result.cleanupSuperseded).isTrue()
            assertThat(load().map { it.id }).contains(fresh.id)
        } finally { release.complete(Unit); withTimeout(10_000) { job.cancelAndJoin() } }
    }
    @Test fun `cancelled old cleanup propagates while retaining recoverable local data`() = runBlocking {
        val row = saved()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        store.authority.beforeCleanupAdmission = { _, _ -> entered.complete(Unit); release.await() }
        val job = async(Dispatchers.IO) { session.signOut(a.id, session.owner()) }
        try {
            withTimeout(10_000) { entered.await() }
            job.cancel()
            withTimeout(10_000) { job.join() }
            assertThat(job.isCancelled).isTrue()
            assertThat(load().single().id).isEqualTo(row.id)
        } finally { release.complete(Unit); withTimeout(10_000) { job.cancelAndJoin() } }
    }
    @Test fun `profile removal clears durable observations before same ID can be readded`() = runBlocking {
        val row = saved(); val old = session.owner(); val ticket = store.observations.capture(a.id, row.lineage, row.session)
        val result = store.remove(a.id, old) as NativeResult.Applied
        assertThat(result.cleanupError).isNull()
        store.save(a)
        store.observations.put(old, ticket, row)
        assertThat(load()).isEmpty()
        assertThat(load().map { it.id }).doesNotContain(row.id)
    }
    @Test fun `partial cache cleanup still clears observations and reports the original error`() = runBlocking {
        saved(); val other = saved(b)
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, org.evsyukov.hermes.data.cache.CacheDatabase::class.java).allowMainThreadQueries().build()
        val cache = org.evsyukov.hermes.data.cache.ResponseCache(db.responses(), scope, { Long.MAX_VALUE })
        val error = CleanupFailure(Any())
        cache.onForgetStarting = { throw error }
        val actual = ServerSession(a, OkHttpClient(), store, AppStrings(context.resources), cache)
        try {
            val owner = actual.owner()
            val result = actual.signOut(a.id, owner) as NativeResult.Applied
            assertThat(result.cleanupError).isSameInstanceAs(error)
            assertThat(actual.holds(owner)).isFalse()
            assertThat(load()).isEmpty()
            assertThat(load(b).single().id).isEqualTo(other.id)
        } finally { cache.onForgetStarting = null; db.close() }
    }
    @Test fun `chat cache cleanup error does not skip independent observation clear`() = runBlocking {
        saved(); val other = saved(b)
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, org.evsyukov.hermes.data.cache.CacheDatabase::class.java).allowMainThreadQueries().build()
        val cache = org.evsyukov.hermes.data.cache.ResponseCache(db.responses(), scope, { Long.MAX_VALUE })
        val error = CleanupFailure(Any())
        cache.onForgetStarting = { throw error }
        val actual = ServerSession(a, OkHttpClient(), store, AppStrings(context.resources), cache)
        try {
            assertThat(runCatching { actual.forgetChat("same", actual.owner()) }.exceptionOrNull()).isSameInstanceAs(error)
            assertThat(load()).isEmpty()
            assertThat(load(b).single().id).isEqualTo(other.id)
        } finally { cache.onForgetStarting = null; db.close() }
    }
    // Extra state prevents coroutine stack-trace recovery from cloning the injected primary.
    private class CleanupFailure(val marker: Any) : java.io.IOException("synthetic cache cleanup failed")
    private object PlainCipher : TokenCipher { override fun encrypt(plain: String) = plain; override fun decrypt(sealed: String) = sealed }
}
