package org.evsyukov.hermes.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.server.*
import org.evsyukov.hermes.testing.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ObservationCommitTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + Dispatchers.IO)
    private val resources = ChatTestResources()
    private val fault = FaultJournal(scope)
    private val profile = ServerProfile("commit-test", "Commit", "https://synthetic.example.org")
    private val store = resources.store(context, Cipher, scope, profile, fault.data)
    private val owner get() = store.authority.owner(profile.id)
    private val journal get() = store.observations
    private fun row() = LocalObservation(profile = profile.id, lineage = profile.url.toString(), session = "s", generation = owner.generation,
        items = listOf(ChatItem.Assistant("local-answer", listOf(Part.Text("complete local reply")), false)), completed = true, revision = 1)
    private fun ticket() = journal.capture(profile.id, profile.url.toString(), "s")
    @After fun finish() { fault.readRelease.countDown(); fault.release.countDown(); fault.fail = false; resources.finish(root, listOf("join journal" to { runBlocking { withTimeout(10_000) { fault.close() } } })) }
    @Test fun `post-transform serializer failure keeps pending content then retry commits it`() = runBlocking {
        val row = row(); val ticket = ticket()
        journal.stage(owner, ticket, row)
        fault.fail = true
        assertThat(runCatching { journal.put(owner, ticket, row) }.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
        assertThat(fault.writes.get()).isGreaterThan(0)
        assertThat(fault.committedRows()).isEmpty()
        assertThat(journal.pending(owner, row.lineage, "s")).hasSize(1)
        assertThat(journal.pending(owner, row.lineage, "s").single().items).isEqualTo(row.items)
        assertThat(journal.hasFailures(profile.id, row.lineage, "s")).isTrue()
        fault.fail = false
        journal.put(owner, ticket, row)
        assertThat(journal.pending(owner, row.lineage, "s")).isEmpty()
        assertThat(journal.hasFailures(profile.id, row.lineage, "s")).isFalse()
        assertThat(fault.committedRows().single().items).isEqualTo(row.items)
        val replacement = LocalObservationStore(context, store.authority, fault.data)
        assertThat(replacement.load(owner, row.lineage, "s").single().items).isEqualTo(row.items)
    }
    @Test fun `failed durable clear preserves newer memory and reports failure before successful retry`() = runBlocking {
        val row = row(); val ticket = ticket()
        journal.put(owner, ticket, row)
        val newer = row.copy(revision = 2, items = row.items + ChatItem.User("later", "retained", emptyList()))
        journal.stage(owner, ticket, newer)
        fault.fail = true
        assertThat(runCatching { store.authority.withOwner(owner) { journal.clearAdmitted(profile.id, row.lineage, "s") } }.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
        assertThat(journal.pending(owner, row.lineage, "s")).hasSize(1)
        assertThat(journal.pending(owner, row.lineage, "s").single().items).isEqualTo(newer.items)
        assertThat(journal.valid(ticket, "s")).isFalse()
        assertThat(fault.committedRows().single().revision).isEqualTo(1)
        fault.fail = false
        store.authority.withOwner(owner) { journal.clearAdmitted(profile.id, row.lineage, "s") }
        assertThat(journal.load(owner, row.lineage, "s")).isEmpty()
    }
    @Test fun `ordinary staged work is pending without a failure warning`() = runBlocking {
        val row = row(); journal.stage(owner, ticket(), row)
        assertThat(journal.hasUnsaved(profile.id, row.lineage, "s")).isTrue()
        assertThat(journal.hasFailures(profile.id, row.lineage, "s")).isFalse()
    }
    @Test fun `presentation failure survives content success until its own committed retry`() = runBlocking {
        val row = row(); val ticket = ticket(); journal.put(owner, ticket, row)
        fault.fail = true
        assertThat(runCatching { journal.presentation(owner, ticket, row.id, true) }.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
        fault.fail = false
        journal.put(owner, ticket, row)
        assertThat(journal.hasFailures(profile.id, row.lineage, "s")).isTrue()
        journal.presentation(owner, ticket, row.id, true)
        assertThat(journal.hasFailures(profile.id, row.lineage, "s")).isFalse()
        assertThat(journal.load(owner, row.lineage, "s").single().collapsed).isTrue()
    }
    @Test fun `default resume keeps current streaming instance and preserves authoritative whitespace on a completed tail`() {
        val tail = ChatItem.Assistant("a2", listOf(Part.Text("partial")), false)
        val resumed = resumeTurn(listOf(tail), "partial ", { "unused" })
        assertThat((resumed.last() as ChatItem.Assistant).parts.filterIsInstance<Part.Text>().joinToString("") { it.text }).isEqualTo("partial ")
        val streaming = listOf(tail.copy(streaming = true))
        assertThat(resumeTurn(streaming, "different", { "unused" })).isSameInstanceAs(streaming)
    }
    @Test fun `post clear authorized staging while commit waits survives old clear acknowledgement`() = runBlocking {
        val old = row(); journal.put(owner, ticket(), old)
        fault.events.receive()
        fault.hold = true
        val clear = async(Dispatchers.IO) { store.authority.withOwner(owner) { journal.clearAdmitted(profile.id, old.lineage, "s") } }
        var primary: Throwable? = null
        try {
            withTimeout(10_000) { fault.events.receive() }
            val fresh = row(); val admitted = ticket()
            journal.stage(owner, admitted, fresh)
            fault.release.countDown(); withTimeout(10_000) { clear.await() }
            assertThat(journal.pending(owner, old.lineage, "s").single().id).isEqualTo(fresh.id)
            journal.put(owner, admitted, fresh)
            assertThat(journal.load(owner, old.lineage, "s").single().id).isEqualTo(fresh.id)
        } catch (error: Throwable) { primary = error; throw error }
        finally { finishOwned(primary, clear) }
    }
    @Test fun `cancelled write caller retains newer staged content until its committed retry`() = runBlocking {
        val old = row(); val ticket = ticket(); fault.hold = true
        journal.stage(owner, ticket, old)
        val write = async(Dispatchers.IO) { journal.put(owner, ticket, old) }
        var primary: Throwable? = null
        try {
            withTimeout(10_000) { fault.events.receive() }
            val newer = old.copy(revision = 2, items = old.items + ChatItem.User("new", "newer", emptyList()))
            journal.stage(owner, ticket, newer)
            write.cancel(); fault.release.countDown(); withTimeout(10_000) { write.join() }
            assertThat(journal.pending(owner, old.lineage, "s").single().items).isEqualTo(newer.items)
            journal.put(owner, ticket, newer)
            assertThat(journal.load(owner, old.lineage, "s").single().items).isEqualTo(newer.items)
        } catch (error: Throwable) { primary = error; throw error }
        finally { finishOwned(primary, write) }
    }
    @Test fun `journal read releases admission on IO while caller Main remains deliberately paused`() = runBlocking {
        fault.prefillEmpty(); fault.holdRead = true
        val main = kotlinx.coroutines.test.StandardTestDispatcher()
        val reader = CoroutineScope(root + main).async { journal.load(owner, profile.url.toString(), "s") }
        var writer: Deferred<Unit>? = null
        var primary: Throwable? = null
        try {
            main.scheduler.runCurrent()
            check(fault.readEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Actual journal serializer read did not enter" }
            fault.readRelease.countDown()
            writer = scope.async { store.save(profile); Unit }
            val completed = withTimeoutOrNull(500) { writer.await() }
            assertThat(completed).isNotNull()
            assertThat(reader.isCompleted).isFalse()
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            fault.readRelease.countDown()
            var cleanup: Throwable? = null
            try { withTimeout(10_000) { while (!reader.isCompleted || writer?.isCompleted == false) { main.scheduler.runCurrent(); yield() } } }
            catch (error: Throwable) { cleanup = error; reader.cancel(); writer?.cancel(); main.scheduler.runCurrent() }
            listOfNotNull(reader, writer).forEach { job ->
                try { withTimeout(10_000) { while (!job.isCompleted) { main.scheduler.runCurrent(); yield() }; job.await() } }
                catch (error: Throwable) { if (error !is CancellationException) { val first = cleanup; if (first == null) cleanup = error else if(first !== error) first.addSuppressed(error) } }
            }
            cleanup?.let { error -> val first = primary; if(first == null) throw error else if(first !== error) first.addSuppressed(error) }
        }
    }
    @Test fun `successful held presentation preserves newer pending content without its cancelled writer`() = runBlocking {
        val old = row(); val admitted = ticket(); journal.put(owner, admitted, old)
        fault.events.receive(); fault.hold = true
        val presentation = async(Dispatchers.IO) { journal.presentation(owner, admitted, old.id, true) }
        var primary: Throwable? = null
        var writer: Job? = null
        try {
            withTimeout(10_000) { fault.events.receive() }
            val newer = old.copy(revision = 2, items = old.items + ChatItem.User("newer", "complete newer bytes", emptyList()))
            journal.stage(owner, admitted, newer)
            writer = launch(start = CoroutineStart.UNDISPATCHED) { journal.put(owner, admitted, newer) }
            writer.cancelAndJoin()
            fault.release.countDown(); withTimeout(10_000) { presentation.await() }
            assertThat(journal.pending(owner, old.lineage, "s").single()).isEqualTo(newer.copy(collapsed = true))
            assertThat(fault.committedRows().single().revision).isEqualTo(1)
            val replacement = LocalObservationStore(context, store.authority, fault.data)
            assertThat(replacement.load(owner, old.lineage, "s").single()).isEqualTo(newer.copy(collapsed = true))
            assertThat(journal.hasUnsaved(profile.id, old.lineage, "s")).isTrue()
            assertThat(journal.hasFailures(profile.id, old.lineage, "s")).isFalse()
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            var cleanup: Throwable? = null
            listOfNotNull(presentation, writer).forEach { job ->
                try { finishOwned(null, job) }
                catch (error: Throwable) { val first = cleanup; if (first == null) cleanup = error else if(first !== error) first.addSuppressed(error) }
            }
            cleanup?.let { error -> val first = primary; if (first == null) throw error else if(first !== error) first.addSuppressed(error) }
        }
    }
    private suspend fun finishOwned(primary: Throwable?, job: Job) {
        fault.release.countDown()
        try { withTimeout(10_000) { job.cancelAndJoin() } }
        catch (cleanup: Throwable) { if (primary == null) throw cleanup else if (primary !== cleanup) primary.addSuppressed(cleanup) }
    }
    private object Cipher : TokenCipher { override fun encrypt(plain: String) = plain; override fun decrypt(sealed: String) = sealed }
}
