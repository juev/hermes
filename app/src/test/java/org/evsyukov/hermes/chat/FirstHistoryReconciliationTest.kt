package org.evsyukov.hermes.chat

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import mockwebserver3.*
import okhttp3.*
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.cache.CacheDatabase
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.remote.SignInRequiredException
import org.evsyukov.hermes.data.server.*
import org.evsyukov.hermes.testing.ChatTestResources
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class FirstHistoryReconciliationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + Dispatchers.IO)
    private val resources = ChatTestResources()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val wire = MockWebServer()
    private val backend = HistoryBackend()
    private val adoptions = Channel<Unit>(Channel.UNLIMITED)
    private lateinit var session: ServerSession
    private lateinit var socket: GatewaySocket
    private var chat: ChatController? = null
    private var controllerRoot: Job? = null
    private val submitted = Channel<Unit>(Channel.UNLIMITED)
    private var journal: org.evsyukov.hermes.testing.FaultJournal? = null

    private fun finish() {
        backend.release.countDown()
        backend.resumeRelease?.countDown()
        backend.secondRelease.countDown()
        backend.ackRelease.countDown()
        backend.attachRelease.countDown()
        journal?.release?.countDown()
        var closing: Throwable? = null
        try { chat?.close() } catch (error: Throwable) { closing = error }
        resources.finish(root, listOf(
            "close journal" to { runBlocking { journal?.close() } }, "close HTTP server" to { wire.close() }, "close Room" to { db.close() },
            "report controller close" to { closing?.let { throw it } },
            "close adoption channel" to { adoptions.close() },
        ))
    }

    @After fun tearDown() = finish()

    private fun scenario(body: suspend () -> Unit) = runBlocking<Unit> {
        var primary: Throwable? = null
        try { body() } catch (error: Throwable) { primary = error; throw error }
        finally {
            try { finish() } catch (cleanup: Throwable) {
                val first = primary
                if (first == null) throw cleanup else if (first !== cleanup) first.addSuppressed(cleanup)
            }
        }
    }

    private suspend fun open(stored: String? = "s1"): ChatController = withTimeout(10_000) {
        wire.dispatcher = backend
        wire.start()
        val profile = ServerProfile("first-history", "First history", wire.url("/").toString())
        session = ServerSession(profile, OkHttpClient(), resources.store(context, PlainCipher, scope, profile, journal?.data), AppStrings(context.resources), ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
        socket = resources.socket(GatewaySocket(session.http, scope, AppStrings(context.resources)))
        socket.awaitReady()
        socket.connections.first { it > 0 }
        val child = SupervisorJob(root)
        controllerRoot = child
        ChatController(session, socket, CoroutineScope(child + Dispatchers.IO), AppStrings(context.resources), stored, onLive = { _, _, _ -> adoptions.trySend(Unit) }, onTurnStart = { object : org.evsyukov.hermes.background.TurnHold {
            override fun live(id: String) = Unit
            override fun submitted() { submitted.trySend(Unit) }
            override fun release() = Unit
        } }).also { chat = it }
    }

    private suspend fun initialLoad(current: ChatController) = withTimeout(10_000) {
        check(backend.firstEntered.await(10, TimeUnit.SECONDS)) { "First history request did not arrive" }
        checkNotNull(current.loadCompletion).await()
        adoptions.receive()
    }

    private suspend fun reconnectFirstHistory(failed: Boolean, running: Boolean) {
        backend.failFirst = failed
        backend.autoComplete = false
        val current = open()
        initialLoad(current)
        if (failed) assertThat(current.state.value.error).isNotNull() else assertThat(current.state.value.error).isNull()
        current.send("more", emptyList())
        withTimeout(10_000) { current.state.first { (it.items.lastOrNull() as? ChatItem.Assistant)?.text == "partial" } }
        assertThat((current.state.value.items.last() as ChatItem.Assistant).streaming).isTrue()
        val resumeRelease = CountDownLatch(1)
        backend.resumeRelease = resumeRelease
        backend.disconnect()
        if (running) backend.inflight = "partial lost" else backend.finishWithoutEvent()
        resumeRelease.countDown()
        withTimeout(10_000) { adoptions.receive() }
        System.err.println("FIRST_SUCCESS failedInitial=$failed running=$running history=${backend.histories.get()} resumes=${backend.resumes.get()} working=${current.working.items}")
        assertThat(current.state.value.loading).isFalse()
        if (running) {
            assertThat(backend.lastResumedText).isEqualTo("partial lost")
            assertThat((current.working.items.last() as ChatItem.Assistant).text).isEqualTo("partial lost")
        }
        else {
            assertThat(current.working.running).isFalse()
            assertThat(current.working.items.filterIsInstance<ChatItem.Assistant>().any { it.streaming }).isFalse()
        }
    }

    @Test fun `R1 first successful history accepts fresh running inflight after initial read failure`() = scenario { reconnectFirstHistory(true, true) }
    @Test fun `R1 first successful history accepts fresh idle after initial read failure`() = scenario { reconnectFirstHistory(true, false) }
    @Test fun `R1 established history still accepts a fresh running inflight`() = scenario { reconnectFirstHistory(false, true) }

    private suspend fun overlap(after: Boolean, identical: Boolean = false, attachment: Boolean = false, receipt: Boolean = false, ackLast: Boolean = false) {
        backend.receipts = receipt
        backend.ackLast = ackLast
        backend.holdFirst = true
        backend.captureAfterGate = after
        if (identical) backend.seedIdentical()
        val current = open()
        check(backend.firstEntered.await(10, TimeUnit.SECONDS)) { "History generation gate did not arrive" }
        val load = checkNotNull(current.loadCompletion)
        val before = backend.completed.get()
        val files = if (attachment) listOf(LocalAttachment("note.txt", "text/plain", byteArrayOf(1))) else emptyList()
        current.send(if (attachment) "" else "more", files)
        withTimeout(10_000) { current.state.first { (it.items.lastOrNull() as? ChatItem.Assistant)?.let { a -> a.text == "done" && !a.streaming } == true } }
        backend.ackRelease.countDown()
        withTimeout(10_000) { submitted.receive() }
        assertThat(backend.completed.get()).isEqualTo(before + 1)
        backend.release.countDown()
        withTimeout(10_000) { load.await() }
        val answers = current.state.value.items.filterIsInstance<ChatItem.Assistant>()
        System.err.println("HISTORY_GENERATION after=$after identical=$identical attachment=$attachment stored=${backend.completed.get()} captured=${backend.firstCapturedCompletions.get()} answers=${answers.map { it.key to it.text }}")
        assertThat(backend.firstCapturedCompletions.get()).isEqualTo(if (after) before + 1 else before)
        val correlated = receipt && !backend.partialReceipt
        if (!after || correlated) assertThat(answers.count { it.text == "done" }).isEqualTo(if (identical) 2 else 1)
        if (correlated) {
            assertThat(current.state.value.observations.single().reconciled).isTrue()
        } else {
            // No receipt identifies these copies, even when their text happens to match.
            val local = current.state.value.observations.single()
            assertThat(local.reconciled).isFalse()
            assertThat(local.items.filterIsInstance<ChatItem.Assistant>().single().text).isEqualTo("done")
            assertThat(local.items.filterIsInstance<ChatItem.User>().single().text).isEqualTo(if (attachment) "" else "more")
            assertThat(local.files.size).isEqualTo(if (attachment) 1 else 0)
            val stored = checkNotNull(current.state.value.storedHistory)
            assertThat(stored.filterIsInstance<ChatItem.Assistant>().count { it.text == "done" }).isEqualTo((if (identical) 1 else 0) + (if (after) 1 else 0))
            assertThat(backend.completed.get()).isEqualTo(before + 1)
        }
        assertThat(current.state.value.items.map { it.key }.distinct()).hasSize(current.state.value.items.size)
    }

    @Test fun `R2 first history generated after storage includes completed reply exactly once`() = scenario { overlap(true) }
    @Test fun `R2 history generated before send retains its early completed reply`() = scenario { overlap(false) }
    @Test fun `R2 identical replies to distinct turns remain distinct before history generation`() = scenario { overlap(false, identical = true) }
    @Test fun `R2 identical replies to distinct turns appear once per turn after storage`() = scenario { overlap(true, identical = true) }
    @Test fun `R2 attachment-only early reply survives a predating payload`() = scenario { overlap(false, attachment = true) }
    @Test fun `R2 attachment-only stored early reply is not duplicated`() = scenario { overlap(true, attachment = true) }

    @Test fun `current anonymous setup works and a captured retired HTTP root cannot enqueue`() = scenario {
        val current = open()
        initialLoad(current)
        val origin = session.owner()
        assertThat(session.holds(origin)).isTrue()
        assertThat(session.signOut(session.profile.id, origin)).isInstanceOf(NativeResult.Applied::class.java)
        val requests = wire.requestCount
        assertThat(runCatching { session.chatFor(origin).list() }.exceptionOrNull()).isInstanceOf(SignInRequiredException::class.java)
        assertThat(wire.requestCount).isEqualTo(requests)
        assertThat(session.probe(session.owner())).isTrue()
    }

    @Test fun `correlated full first history retires the local reply with ACK first`() = scenario { overlap(true, receipt = true) }
    @Test fun `correlated full first history retires the local reply with completion first`() = scenario { overlap(true, receipt = true, ackLast = true) }

    @Test fun `partial receipt through the real wire retains explicit overlapping local and stored replies`() = scenario {
        backend.partialReceipt = true
        overlap(true, receipt = true)
    }

    @Test fun `correlated attachment-only reply retires only after actual row coverage`() = scenario { overlap(true, attachment = true, receipt = true) }

    @Test fun `correlated tool-rich reply requires every raw folded member`() = scenario {
        backend.toolRich = true
        overlap(true, receipt = true)
        val row = checkNotNull(chat).state.value.observations.single()
        assertThat(row.items.filterIsInstance<ChatItem.Assistant>().single().parts.filterIsInstance<Part.Tool>().single().run.done).isTrue()
        assertThat(row.frames.single { it.type == "tool.complete" }.payload["result"]?.jsonPrimitive?.content).isEqualTo("full tool result ".repeat(200))
    }

    @Test fun `fresh first history keeps resumed prefix and a later current transport delta`() = scenario {
        backend.failFirst = true
        backend.autoComplete = false
        val current = open()
        initialLoad(current)
        current.send("more", emptyList())
        withTimeout(10_000) { current.state.first { (it.items.lastOrNull() as? ChatItem.Assistant)?.text == "partial" } }
        val oldTransport = socket.transport.value
        val bound = CompletableDeferred<Unit>()
        current.afterLiveBound = { on -> if (on != oldTransport) bound.complete(Unit) }
        backend.holdSecond = true
        backend.resumeRelease = CountDownLatch(1)
        backend.disconnect()
        backend.inflight = "partial lost"
        backend.resumeRelease!!.countDown()
        withTimeout(10_000) { bound.await() }
        check(backend.secondEntered.await(10, TimeUnit.SECONDS)) { "Second history did not arrive" }
        val load = checkNotNull(current.loadCompletion)
        backend.delta(" newest")
        withTimeout(10_000) { current.state.first { (it.items.lastOrNull() as? ChatItem.Assistant)?.text?.endsWith(" newest") == true } }
        backend.secondRelease.countDown()
        withTimeout(10_000) { load.await(); adoptions.receive() }
        assertThat((current.working.items.last() as ChatItem.Assistant).text).isEqualTo("partial lost newest")
        current.afterLiveBound = null
    }

    @Test fun `controller replacement recovers local reply files and review state without resubmitting`() = scenario {
        overlap(true, attachment = true)
        val original = checkNotNull(chat)
        val retained = original.state.value.observations.single()
        val producers = original.continuationProducers
        original.reviewObservation(retained.id, true)
        original.close()
        withTimeout(10_000) { producers.toList().joinAll(); checkNotNull(controllerRoot).cancelAndJoin() }
        val replacementRoot = SupervisorJob(root)
        controllerRoot = replacementRoot
        val replacement = ChatController(session, socket, CoroutineScope(replacementRoot + Dispatchers.IO), AppStrings(context.resources), "s1", onLive = { _, _, _ -> adoptions.trySend(Unit) })
        chat = replacement
        withTimeout(10_000) { replacement.observationRecovery.join(); checkNotNull(replacement.loadCompletion).await(); adoptions.receive() }
        val recovered = replacement.state.value.observations.single()
        assertThat(recovered.id).isEqualTo(retained.id)
        assertThat(recovered.items).isEqualTo(retained.items)
        assertThat(recovered.files).isEqualTo(retained.files)
        assertThat(recovered.collapsed).isTrue()
        assertThat(replacement.state.value.storedHistory).isNotEmpty()
        assertThat(backend.prompts.get()).isEqualTo(1)
        val writes = replacement.continuationProducers
        replacement.reviewObservation(retained.id, false)
        val previousLoad = replacement.loadCompletion
        backend.holdSecond = true
        replacement.reload()
        check(backend.secondEntered.await(10, TimeUnit.SECONDS)) { "Explicit refresh did not enter HTTP" }
        val refresh = checkNotNull(replacement.loadCompletion)
        assertThat(refresh).isNotSameInstanceAs(previousLoad)
        backend.secondRelease.countDown()
        withTimeout(10_000) { refresh.await(); writes.toList().joinAll() }
        assertThat(replacement.state.value.observations.single().collapsed).isFalse()
        assertThat(backend.prompts.get()).isEqualTo(1)
        replacement.send("second identical prompt", emptyList())
        withTimeout(10_000) { replacement.state.first { it.observations.size == 2 && it.observations.last().completed }; writes.toList().joinAll() }
        val newCopy = replacement.state.value.observations.last()
        assertThat(newCopy.items.map { it.key }.intersect(retained.items.map { it.key }.toSet())).isEmpty()
        assertThat(newCopy.items.filterIsInstance<ChatItem.Assistant>().single().text).isEqualTo("done")
        assertThat(backend.prompts.get()).isEqualTo(2)
        val owner = session.owner()
        session.forgetChat("s1", owner)
        replacement.close()
        withTimeout(10_000) { writes.toList().joinAll(); replacementRoot.cancelAndJoin() }
        assertThat(session.observations.load(owner, session.profile.url.toString(), "s1")).isEmpty()
    }

    @Test fun `created session clear while attachment waits removes its retained draft copy and rejects later writes`() = scenario {
        backend.holdAttach = true
        val current = open(null)
        val writes = current.continuationProducers
        current.send("new prompt", listOf(LocalAttachment("note.txt", "text/plain", byteArrayOf(1))))
        check(backend.attachEntered.await(10, TimeUnit.SECONDS)) { "Actual file attach did not enter" }
        assertThat(current.working.storedId).isEqualTo("s1")
        val owner = session.owner()
        session.forgetChat("s1", owner)
        backend.attachRelease.countDown()
        withTimeout(10_000) { submitted.receive(); current.state.first { it.observations.any { row -> row.completed } }; writes.toList().joinAll() }
        assertThat(session.observations.load(owner, session.profile.url.toString(), "s1")).isEmpty()
        assertThat(session.observations.load(owner, session.profile.url.toString(), "")).isEmpty()
    }

    @Test fun `close waits for authority without holding the controller publication lock`() = scenario {
        val current = open()
        initialLoad(current)
        current.send("more", emptyList())
        withTimeout(10_000) { current.state.first { it.observations.any { row -> row.completed } }; submitted.receive() }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
        val closeThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val holder = executor.submit { session.publish(session.owner()) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) { "Authority holder not released" } } }
        var closer: java.util.concurrent.Future<*>? = null
        var visibility: java.util.concurrent.Future<*>? = null
        var primary: Throwable? = null
        try {
            check(entered.await(10, TimeUnit.SECONDS)) { "Authority holder did not enter" }
            closer = executor.submit { closeThread.set(Thread.currentThread()); current.close() }
            withTimeout(10_000) { while (closeThread.get()?.let { it.state == Thread.State.BLOCKED && it.stackTrace.any { frame -> frame.className.endsWith("ProfileLifetime") && frame.methodName == "publish" } } != true) delay(1) }
            val applied = CountDownLatch(1)
            visibility = executor.submit { current.setVisible(false); applied.countDown() }
            assertThat(applied.await(500, TimeUnit.MILLISECONDS)).isTrue()
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            release.countDown()
            var cleanup: Throwable? = null
            listOfNotNull(holder, closer, visibility).forEach { future -> try { future.get(10, TimeUnit.SECONDS) } catch (error: Throwable) { val first = cleanup; if (first == null) cleanup = error else if (first !== error) first.addSuppressed(error) } }
            executor.shutdown()
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) { val error = AssertionError("Owned publication workers did not finish"); val first = cleanup; if (first == null) cleanup = error else first.addSuppressed(error) }
            cleanup?.let { error -> val first = primary; if (first == null) throw error else if (first !== error) first.addSuppressed(error) }
        }
    }

    private suspend fun startupSnapshot(type: String?, running: Boolean = true, nested: Boolean = false, failed: Boolean = false) {
        backend.running = running; backend.inflight = "remote "; backend.infoRunning = nested
        backend.holdFirst = !failed; backend.failFirst = failed
        backend.resumeRelease = CountDownLatch(1)
        if (failed) backend.holdSecond = true
        val current = open()
        val bound = CompletableDeferred<Unit>(); current.afterLiveBound = { bound.complete(Unit) }
        backend.resumeRelease!!.countDown()
        withTimeout(10_000) { bound.await() }
        if (failed) {
            withTimeout(10_000) { checkNotNull(current.loadCompletion).await(); adoptions.receive() }
            val before = current.loadCompletion
            val oldTransport = socket.transport.value
            val rebound = CompletableDeferred<Unit>()
            current.afterLiveBound = { if (it != oldTransport) rebound.complete(Unit) }
            backend.resumeRelease = CountDownLatch(1)
            backend.disconnect()
            backend.resumeRelease!!.countDown()
            withTimeout(10_000) { rebound.await() }
            check(backend.secondEntered.await(10, TimeUnit.SECONDS))
            assertThat(current.loadCompletion).isNotSameInstanceAs(before)
        } else check(backend.firstEntered.await(10, TimeUnit.SECONDS))
        val load = checkNotNull(current.loadCompletion)
        if (type != null) {
            val consumed = CompletableDeferred<Unit>(); current.afterCurrentEvent = { if (it == type) consumed.complete(Unit) }
            backend.input(type, buildJsonObject { when(type) {
                "message.delta" -> put("text", "new")
                "message.complete" -> { put("text", "finished"); put("status", "complete") }
                "error" -> put("message", "current failure")
                "session.info" -> put("running", true)
            } })
            withTimeout(10_000) { consumed.await() }
        }
        backend.release.countDown(); backend.secondRelease.countDown()
        withTimeout(10_000) { load.await(); adoptions.receive() }
        when(type) {
            "message.delta" -> assertThat((current.working.items.last() as ChatItem.Assistant).text).isEqualTo("remote new")
            "message.complete" -> { assertThat(current.working.running).isFalse(); assertThat((current.working.items.last() as ChatItem.Assistant).streaming).isFalse() }
            "error" -> { assertThat(current.working.running).isFalse(); assertThat(current.working.error).isEqualTo("current failure") }
            "message.start", "session.info" -> assertThat(current.working.running).isTrue()
            null -> assertThat(current.working.running).isEqualTo(running)
        }
        current.afterCurrentEvent = null; current.afterLiveBound = null
    }
    @Test fun `remote startup delta survives held first history without a local send`() = scenario { startupSnapshot("message.delta") }
    @Test fun `nested running startup delta survives held first history`() = scenario { startupSnapshot("message.delta", nested = true) }
    @Test fun `remote delta after initial read failure survives its first successful history`() = scenario { startupSnapshot("message.delta", failed = true) }
    @Test fun `current terminal after bind is not reopened by delayed adoption`() = scenario { startupSnapshot("message.complete") }
    @Test fun `current error after bind is not reopened by delayed adoption`() = scenario { startupSnapshot("error") }
    @Test fun `current start after idle bind is not overwritten idle`() = scenario { startupSnapshot("message.start", running = false) }
    @Test fun `current running info after idle bind is not overwritten idle`() = scenario { startupSnapshot("session.info", running = false) }
    @Test fun `fresh remote running snapshot succeeds without intervening event`() = scenario { startupSnapshot(null) }
    @Test fun `fresh remote idle snapshot succeeds without intervening event`() = scenario { startupSnapshot(null, running = false) }

    internal fun withScreenController(configure: (HistoryBackend) -> Unit, body: suspend (ChatController, HistoryBackend) -> Unit) = scenario {
        configure(backend)
        val controller = open()
        body(controller, backend)
    }

    private suspend fun freshPrefix(prefix: String) {
        backend.failFirst = true; backend.autoComplete = false
        val current = open(); initialLoad(current)
        current.send("more", emptyList())
        withTimeout(10_000) { current.state.first { (it.items.lastOrNull() as? ChatItem.Assistant)?.text == "partial" } }
        val old = socket.transport.value; val bound = CompletableDeferred<Unit>()
        current.afterLiveBound = { if (it != old) bound.complete(Unit) }
        backend.holdSecond = true; backend.resumeRelease = CountDownLatch(1)
        backend.disconnect(); backend.inflight = prefix; backend.resumeRelease!!.countDown()
        withTimeout(10_000) { bound.await() }
        check(backend.secondEntered.await(10, TimeUnit.SECONDS))
        val load = checkNotNull(current.loadCompletion); val consumed = CompletableDeferred<Unit>()
        current.afterCurrentEvent = { if (it == "message.delta") consumed.complete(Unit) }
        backend.delta("next"); withTimeout(10_000) { consumed.await() }
        backend.secondRelease.countDown(); withTimeout(10_000) { load.await(); adoptions.receive() }
        assertThat((current.working.items.last() as ChatItem.Assistant).text).isEqualTo(prefix + "next")
    }
    @Test fun `fresh authoritative prefix retains trailing whitespace before a later delta`() = scenario { freshPrefix("partial ") }
    @Test fun `different authoritative prefix replaces stale text while preserving uncertain copy`() = scenario { freshPrefix("different ") }

    private fun terminalOf(current: ChatController): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { done ->
        current.afterCurrentEvent = { if (it == "message.complete") done.complete(Unit) }
    }
    @Test fun `held journal write does not delay actual prompt or classify pending as failure`() = scenario {
        journal = org.evsyukov.hermes.testing.FaultJournal(scope)
        val current = open(); initialLoad(current)
        journal!!.hold = true
        val writes = current.continuationProducers
        val firstDone = terminalOf(current)
        current.send("immediate", emptyList())
        check(journal!!.entered.await(10, TimeUnit.SECONDS)) { "Actual serializer did not enter" }
        assertThat(backend.promptEntered.await(500, TimeUnit.MILLISECONDS)).isTrue()
        withTimeout(10_000) { submitted.receive(); firstDone.await() }
        assertThat(backend.prompts.get()).isEqualTo(1)
        assertThat(current.state.value.observationStorageError).isFalse()
        val second = terminalOf(current)
        current.editLast("second")
        // Even concurrent stage/queued-write activity cannot add a disk prerequisite to admission.
        assertThat(backend.secondPromptEntered.await(500, TimeUnit.MILLISECONDS)).isTrue()
        withTimeout(10_000) { submitted.receive(); second.await() }
        journal!!.release.countDown(); withTimeout(10_000) { writes.toList().joinAll() }
        assertThat(session.observations.load(session.owner(), session.profile.url.toString(), "s1")).hasSize(2)
    }
    @Test fun `journal failure retry clears only resolved controller warning without resubmission`() = scenario {
        journal = org.evsyukov.hermes.testing.FaultJournal(scope)
        val current = open(); initialLoad(current); val writes = current.continuationProducers
        journal!!.fail = true
        val done = terminalOf(current)
        current.send("retain", emptyList())
        withTimeout(10_000) { done.await(); submitted.receive(); writes.toList().joinAll() }
        assertThat(current.state.value.observationStorageError).isTrue()
        val retained = current.state.value.observations.single().items
        journal!!.fail = false; backend.holdSecond = true
        val before = current.loadCompletion; current.reload()
        check(backend.secondEntered.await(10, TimeUnit.SECONDS)); val refresh = checkNotNull(current.loadCompletion)
        assertThat(refresh).isNotSameInstanceAs(before); backend.secondRelease.countDown()
        withTimeout(10_000) { refresh.await(); writes.toList().joinAll() }
        assertThat(current.state.value.observationStorageError).isFalse()
        assertThat(current.state.value.observations.single().items).isEqualTo(retained)
        assertThat(backend.prompts.get()).isEqualTo(1)
    }
    @Test fun `queued same live start after clear inherits revoked ticket but new send remains authorized`() = scenario {
        val current = open(); initialLoad(current); val writes = current.continuationProducers
        val firstDone = terminalOf(current)
        current.send("first", emptyList())
        withTimeout(10_000) { firstDone.await(); submitted.receive(); writes.toList().joinAll() }
        session.forgetChat("s1", session.owner())
        val consumed = CompletableDeferred<Unit>(); current.afterCurrentEvent = { if(it == "message.complete") consumed.complete(Unit) }
        backend.input("message.start", buildJsonObject {})
        backend.input("message.complete", buildJsonObject { put("text", "old producer"); put("status", "complete") })
        withTimeout(10_000) { consumed.await(); writes.toList().joinAll() }
        assertThat(session.observations.load(session.owner(), session.profile.url.toString(), "s1")).isEmpty()
        val freshDone = terminalOf(current)
        current.send("fresh public root", emptyList())
        withTimeout(10_000) { freshDone.await(); submitted.receive(); writes.toList().joinAll() }
        assertThat(session.observations.load(session.owner(), session.profile.url.toString(), "s1")).isNotEmpty()
    }

    private suspend fun renewedGroup(rewrite: Boolean) {
        backend.autoComplete = false
        val current = open(); initialLoad(current); val writes = current.continuationProducers
        current.send("old pending", emptyList()); withTimeout(10_000) { submitted.receive(); current.state.first { (it.items.lastOrNull() as? ChatItem.Assistant)?.text == "partial" } }
        val previous = current.state.value.observations.single().id
        session.forgetChat("s1", session.owner())
        backend.autoComplete = true
        val done = terminalOf(current)
        if (rewrite) current.editLast("fresh rewritten") else current.send("fresh additional", emptyList())
        withTimeout(10_000) { done.await(); submitted.receive(); writes.toList().joinAll() }
        val saved = session.observations.load(session.owner(), session.profile.url.toString(), "s1")
        assertThat(saved).hasSize(1)
        assertThat(saved.single().id).isNotEqualTo(previous)
        assertThat(saved.single().keys).hasSize(1)
        assertThat(saved.single().items.filterIsInstance<ChatItem.User>().single().text).isEqualTo(if (rewrite) "fresh rewritten" else "fresh additional")
    }
    @Test fun `new send after clear cannot reuse a pre clear active observation`() = scenario { renewedGroup(false) }
    @Test fun `new rewrite after clear cannot reauthorize a pre clear active observation`() = scenario { renewedGroup(true) }

    private suspend fun capturedRootClear(rewrite: Boolean) {
        val current = open(); initialLoad(current); val writes = current.continuationProducers
        val first = terminalOf(current); current.send("before clear", emptyList())
        withTimeout(10_000) { first.await(); submitted.receive(); checkNotNull(current.submitCompletion).join(); writes.toList().joinAll() }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        current.afterPublicRootCaptured = { action ->
            check(action == if (rewrite) "rewrite" else "send")
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) { "Public root capture gate not released" }
        }
        val oldDone = terminalOf(current)
        val root = scope.launch { if (rewrite) current.editLast("captured rewrite") else current.send("captured send", emptyList()) }
        var primary: Throwable? = null
        try {
            check(entered.await(10, TimeUnit.SECONDS)) { "Actual public root did not capture" }
            session.forgetChat("s1", session.owner())
            current.afterPublicRootCaptured = null; release.countDown()
            withTimeout(10_000) { root.join(); oldDone.await(); submitted.receive(); checkNotNull(current.submitCompletion).join(); writes.toList().joinAll() }
            assertThat(session.observations.load(session.owner(), session.profile.url.toString(), "s1")).isEmpty()
            val freshDone = terminalOf(current)
            if (rewrite) current.editLast("fresh rewrite") else current.send("fresh send", emptyList())
            withTimeout(10_000) { freshDone.await(); submitted.receive(); checkNotNull(current.submitCompletion).join(); writes.toList().joinAll() }
            val saved = session.observations.load(session.owner(), session.profile.url.toString(), "s1")
            assertThat(saved).hasSize(1)
            assertThat(saved.single().items.filterIsInstance<ChatItem.User>().single().text).isEqualTo(if (rewrite) "fresh rewrite" else "fresh send")
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            current.afterPublicRootCaptured = null; release.countDown()
            try { withTimeout(10_000) { root.cancelAndJoin() } }
            catch (cleanup: Throwable) { if (primary == null) throw cleanup else if(primary !== cleanup) primary.addSuppressed(cleanup) }
        }
    }
    @Test fun `send captured before clear cannot install a renewed journal ticket`() = scenario { capturedRootClear(false) }
    @Test fun `rewrite captured before clear cannot install a renewed journal ticket`() = scenario { capturedRootClear(true) }

    private suspend fun overlappingClearRoot(rewrite: Boolean) {
        backend.autoComplete = false
        val current = open(); initialLoad(current); val writes = current.continuationProducers
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        current.afterPublicRootCaptured = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) { "Overlapping root gate not released" } }
        val stale = scope.async {
            if (rewrite) current.editLast("revoked rewrite secret")
            else current.send("revoked send secret", listOf(LocalAttachment("revoked-secret.bin", "application/octet-stream", byteArrayOf(91, 92, 93))))
        }
        var primary: Throwable? = null
        try {
            check(entered.await(10, TimeUnit.SECONDS)) { "Old root did not capture" }
            session.forgetChat("s1", session.owner())
            current.afterPublicRootCaptured = null
            val partial = CompletableDeferred<Unit>()
            current.afterCurrentEvent = { if (it == "message.delta") partial.complete(Unit) }
            current.send("fresh B", listOf(LocalAttachment("fresh.bin", "application/octet-stream", byteArrayOf(7, 8, 9))))
            withTimeout(10_000) { submitted.receive(); partial.await(); checkNotNull(current.submitCompletion).join(); writes.toList().joinAll() }
            val freshId = current.state.value.currentObservationId
            assertThat(current.state.value.observations.single { it.id == freshId }.completed).isFalse()
            release.countDown()
            withTimeout(10_000) { stale.await(); writes.toList().joinAll() }
            val terminal = terminalOf(current)
            backend.delta(" later"); backend.running = false
            backend.input("message.complete", buildJsonObject { put("text", "partial later"); put("status", "complete") })
            withTimeout(10_000) { terminal.await(); writes.toList().joinAll() }
            val saved = session.observations.load(session.owner(), session.profile.url.toString(), "s1")
            val encoded = saved.joinToString { HermesJson.encodeToString(LocalObservation.serializer(), it) }
            assertThat(encoded).doesNotContain("revoked")
            assertThat(encoded).doesNotContain(java.util.Base64.getEncoder().encodeToString(byteArrayOf(91, 92, 93)))
            assertThat(backend.prompts.get()).isEqualTo(1)
            val fresh = saved.single { it.id == freshId }
            assertThat(fresh.completed).isTrue()
            assertThat(fresh.items.filterIsInstance<ChatItem.User>().single().text).isEqualTo("fresh B")
            assertThat(fresh.items.filterIsInstance<ChatItem.Assistant>().last().text).isEqualTo("partial later")
            assertThat(fresh.files.single().base64).isEqualTo(java.util.Base64.getEncoder().encodeToString(byteArrayOf(7, 8, 9)))
            current.close(); withTimeout(10_000) { writes.toList().joinAll(); checkNotNull(controllerRoot).cancelAndJoin() }
            val child = SupervisorJob(root); controllerRoot = child
            val replacement = ChatController(session, socket, CoroutineScope(child + Dispatchers.IO), AppStrings(context.resources), "s1", onLive = { _, _, _ -> adoptions.trySend(Unit) })
            chat = replacement
            withTimeout(10_000) { replacement.observationRecovery.join(); checkNotNull(replacement.loadCompletion).await(); adoptions.receive() }
            assertThat(replacement.state.value.observations.single { it.id == freshId }.items).isEqualTo(fresh.items)
            assertThat(replacement.state.value.observations.single { it.id == freshId }.files).isEqualTo(fresh.files)
            assertThat(backend.prompts.get()).isEqualTo(1)
            backend.autoComplete = true
            val done = terminalOf(replacement); replacement.send("another authorized action", emptyList())
            withTimeout(10_000) { done.await(); checkNotNull(replacement.submitCompletion).join(); replacement.continuationProducers.toList().joinAll() }
            assertThat(session.observations.load(session.owner(), session.profile.url.toString(), "s1").flatMap { it.items }.filterIsInstance<ChatItem.User>().any { it.text == "another authorized action" }).isTrue()
            assertThat(backend.prompts.get()).isEqualTo(2)
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            current.afterPublicRootCaptured = null; release.countDown()
            try { withTimeout(10_000) { stale.cancelAndJoin() } }
            catch (error: Throwable) { if (primary == null) throw error else if(primary !== error) primary.addSuppressed(error) }
        }
    }
    @Test fun `old send cannot borrow an intervening fresh incomplete group`() = scenario { overlappingClearRoot(false) }
    @Test fun `old rewrite cannot poison an intervening fresh incomplete group`() = scenario { overlappingClearRoot(true) }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}

/** Synthetic external server state, populated by actual prompt RPCs before completion events. */
internal class HistoryBackend : mockwebserver3.Dispatcher() {
    val firstEntered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val secondEntered = CountDownLatch(1)
    val secondRelease = CountDownLatch(1)
    val ackRelease = CountDownLatch(1)
    val attachEntered = CountDownLatch(1)
    val attachRelease = CountDownLatch(1)
    @Volatile var holdAttach = false
    @Volatile var holdSecond = false
    @Volatile var receipts = false
    @Volatile var partialReceipt = false
    @Volatile var toolRich = false
    private val addressed = mutableListOf<Int>()
    @Volatile var ackLast = false
    private var userRowId: Int? = null
    private var assistantRowId: Int? = null
    val histories = AtomicInteger()
    val resumes = AtomicInteger()
    val completed = AtomicInteger()
    @Volatile var expireConfig = false
    val configs = AtomicInteger()
    @Volatile var configRecovered = CountDownLatch(1)
    val prompts = AtomicInteger()
    val promptEntered = CountDownLatch(1)
    val secondPromptEntered = CountDownLatch(1)
    val firstCapturedCompletions = AtomicInteger(-1)
    @Volatile var failFirst = false
    @Volatile var holdFirst = false
    @Volatile var freezeFirst = false
    @Volatile var frozen: String? = null
    @Volatile var captureAfterGate = false
    @Volatile var autoComplete = true
    @Volatile var resumeRelease: CountDownLatch? = null
    @Volatile var lastResumedText = ""
    @Volatile var running = false
    @Volatile var infoRunning = false
    @Volatile var inflight = ""
    @Volatile private var socket: WebSocket? = null
    private val rows = mutableListOf(row(1, "user", "hello"), row(2, "assistant", "hi"))
    private var prompt = ""
    private var nextRow = 3

    @Synchronized fun seedIdentical() {
        rows[0] = row(1, "user", "more")
        rows[1] = row(2, "assistant", "done")
        completed.set(1)
    }
    @Synchronized private fun snapshot(): String = """{"session_id":"s1","profile":"synthetic","messages":[${rows.joinToString(",")}]}"""
    @Synchronized fun finishWithoutEvent() {
        if (userRowId == null) rows += row(nextRow++, "user", prompt)
        if (toolRich) {
            val assistant = nextRow++; val tool = nextRow++
            rows += buildJsonObject { put("id", assistant); put("role", "assistant"); put("content", ""); putJsonArray("tool_calls") {
                add(buildJsonObject { put("id", "tool-1"); putJsonObject("function") { put("name", "terminal"); put("arguments", "{}") } })
            } }.toString()
            rows += buildJsonObject { put("id", tool); put("role", "tool"); put("tool_call_id", "tool-1"); put("content", "full tool result ".repeat(200)) }.toString()
            addressed += assistant; addressed += tool
        }
        assistantRowId = nextRow++
        rows += row(assistantRowId!!, "assistant", "done")
        completed.incrementAndGet()
        running = false
    }
    fun input(type: String, payload: JsonObject) { event(checkNotNull(socket), type, "live1", payload.toString()) }
    fun delta(text: String) { inflight += text; event(checkNotNull(socket), "message.delta", "live1", buildJsonObject { put("text", text) }.toString()) }
    fun disconnect() { checkNotNull(socket).close(1001, "synthetic connection loss") }

    override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
        "/api/ws" -> MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                event(webSocket, "gateway.ready", null, "{}")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = HermesJson.parseToJsonElement(text).jsonObject
                val id = frame["id"]?.jsonPrimitive?.content ?: return
                val params = frame["params"]?.jsonObject
                when (frame["method"]?.jsonPrimitive?.content) {
                    "session.active_list" -> reply(webSocket, id, """{"sessions":[{"id":"live1","session_key":"s1"}]}""")
                    "session.resume" -> {
                        resumes.incrementAndGet()
                        resumeRelease?.let { check(it.await(10, TimeUnit.SECONDS)) { "Fresh resume gate was not released" } }
                        lastResumedText = inflight
                        reply(webSocket, id, buildJsonObject {
                            put("session_id", "live1"); put("session_key", "s1"); if (!infoRunning) put("running", if (running) 1 else 0)
                            putJsonObject("info") { put("profile_name", "synthetic"); if (infoRunning) put("running", running) }
                            putJsonObject("inflight") { put("assistant", inflight) }
                        }.toString())
                    }
                    "session.create" -> reply(webSocket, id, """{"session_id":"live1","stored_session_id":"s1","info":{"profile_name":"synthetic"}}""")
                    "file.attach" -> {
                        attachEntered.countDown()
                        if (holdAttach) check(attachRelease.await(10, TimeUnit.SECONDS)) { "Actual attachment gate was not released" }
                        reply(webSocket, id, """{"ref_text":"@attachment.bin"}""")
                    }
                    "config.set" -> {
                        if (expireConfig && configs.incrementAndGet() == 1) webSocket.send("""{"jsonrpc":"2.0","id":"$id","error":{"code":4001,"message":"synthetic lost binding"}}""")
                        else { configRecovered.countDown(); reply(webSocket, id, "{}") }
                    }
                    "prompt.submit" -> {
                        if (prompts.incrementAndGet() == 2) secondPromptEntered.countDown()
                        promptEntered.countDown()
                        prompt = params?.get("text")?.jsonPrimitive?.content.orEmpty()
                        running = true
                        inflight = "partial"
                        if (receipts) synchronized(this@HistoryBackend) { userRowId = nextRow++; rows += row(userRowId!!, "user", prompt) }
                        val ack = buildJsonObject { put("status", "streaming"); if (receipts) put("user_row_id", userRowId!!) }.toString()
                        if (!ackLast) reply(webSocket, id, ack)
                        event(webSocket, "message.start", "live1", "{}")
                        if (toolRich) {
                            event(webSocket, "tool.start", "live1", """{"tool_id":"tool-1","name":"terminal","args":{}}""")
                            event(webSocket, "tool.complete", "live1", buildJsonObject { put("tool_id", "tool-1"); put("result", "full tool result ".repeat(200)) }.toString())
                        }
                        if (autoComplete) {
                            finishWithoutEvent()
                            event(webSocket, "message.complete", "live1", buildJsonObject {
                                put("text", "done"); put("status", "complete")
                                if (receipts) putJsonObject("persisted_turn") {
                                    put("complete", !partialReceipt); put("user_row_id", userRowId!!); put("final_assistant_row_id", assistantRowId!!)
                                    putJsonArray("row_ids") { add(userRowId!!); addressed.forEach { add(it) }; add(assistantRowId!!) }
                                }
                            }.toString())
                        } else event(webSocket, "message.delta", "live1", """{"text":"partial"}""")
                        if (ackLast) { check(ackRelease.await(10, TimeUnit.SECONDS)) { "ACK gate not released" }; reply(webSocket, id, ack) }
                    }
                    else -> reply(webSocket, id, "{}")
                }
            }
        }).build()
        "/api/sessions/s1" -> json(buildJsonObject {
            put("id", "s1"); put("title", "First history"); put("message_count", synchronized(this) { rows.size })
        }.toString())
        "/api/sessions/s1/messages" -> {
            if (histories.incrementAndGet() != 1) {
                if (holdSecond) { secondEntered.countDown(); check(secondRelease.await(10, TimeUnit.SECONDS)) { "Second history gate not released" } }
                json(frozen ?: snapshot())
            }
            else if (failFirst) {
                firstEntered.countDown()
                MockResponse.Builder().code(500).body("""{"detail":"initial history unavailable"}""").build()
            } else {
                val before = if (!captureAfterGate) snapshot().also { firstCapturedCompletions.set(completed.get()) } else null
                firstEntered.countDown()
                if (holdFirst) check(release.await(10, TimeUnit.SECONDS)) { "History generation gate was not released" }
                val payload = before ?: snapshot().also { firstCapturedCompletions.set(completed.get()) }
                if (freezeFirst) frozen = payload
                json(payload)
            }
        }
        "/api/system/stats" -> json("{}")
        else -> MockResponse.Builder().code(404).build()
    }
    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
    private fun reply(socket: WebSocket, id: String, result: String) { socket.send("""{"jsonrpc":"2.0","id":"$id","result":$result}""") }
    private fun event(socket: WebSocket, type: String, session: String?, payload: String) {
        socket.send(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "event")
            putJsonObject("params") {
                put("type", type); session?.let { put("session_id", it) }; put("payload", HermesJson.parseToJsonElement(payload))
            }
        }.toString())
    }
    private fun row(id: Int, role: String, text: String) = buildJsonObject { put("id", id); put("role", role); put("content", text) }.toString()
}
