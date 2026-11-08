package org.evsyukov.hermes.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.work.Configuration
import androidx.work.WorkManager
import org.evsyukov.hermes.data.auth.Tokens
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.evsyukov.hermes.chat.ChatItem
import org.evsyukov.hermes.chat.Delivery
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.evsyukov.hermes.chat.closeWithOwnedJobs
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.evsyukov.hermes.AppContainer
import org.evsyukov.hermes.chat.ChatController
import org.evsyukov.hermes.chat.LiveGateway
import org.evsyukov.hermes.chat.LiveServer
import org.evsyukov.hermes.chat.LocalAttachment
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.remote.SocketState
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.ui.chat.DraftKey
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/**
 * Drafts belong to a chat, not to the controller that happens to be open: switching from one chat to
 * another through [MainViewModel.openChat] and back finds the words, the files and the reading place.
 * A server is a boundary of its own, and an unsaved new chat keeps its words until they are sent.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class DraftNavigationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private var firstServer: LiveServer? = null
    private var secondServer: LiveServer? = null
    internal val server: LiveServer get() = firstServer ?: LiveServer(LiveGateway()).also { firstServer = it }
    internal val otherServer: LiveServer get() = secondServer ?: LiveServer(LiveGateway()).also { secondServer = it }

    internal lateinit var container: AppContainer
    private var vm: MainViewModel? = null
    private val driver = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal var joinDeadlineMillis = 5_000L

    /** Test seam: the shared reset may fail, and the teardown must still close what it owns. */
    internal var resetSharedServers: suspend () -> Unit = { container.servers.clearSyntheticServers() }

    @get:Rule
    val lifetime = TestRule { base, _ -> object : Statement() {
        override fun evaluate() = runFixture { base.evaluate() }
    } }

    /** The actual JUnit boundary encloses lazy acquisitions, the body and teardown. */
    internal fun runFixture(body: () -> Unit) {
        var primary: Throwable? = null
        try { body() } catch (failure: Throwable) { primary = failure } finally {
            try { tearDown() } catch (cleanup: Throwable) {
                val first = primary
                if (first == null) primary = cleanup else if (first !== cleanup) first.addSuppressed(cleanup)
            }
        }
        primary?.let { throw it }
    }

    fun tearDown() {
        val app = if (::container.isInitialized) container else null
        val ownedServers = listOfNotNull(firstServer, secondServer)
        val roots = listOfNotNull(driver.coroutineContext[Job], vm?.viewModelScope?.coroutineContext?.get(Job), app?.scope?.coroutineContext?.get(Job))
        var joined = false
        val steps = mutableListOf<suspend () -> Unit>(
            { ownedServers.forEach { it.releaseCreate() } },
            { ownedServers.forEach { it.releaseDelete() } },
            { ownedServers.forEach { it.historyGate?.countDown() } },
            { vm?.chat?.value?.close() },
            { app?.connection?.value?.socket?.stop() },
            *roots.map { root -> suspend { root.cancel() } }.toTypedArray(),
            {
                joined = joinDraftJobs(roots, joinDeadlineMillis, ::idle)
                check(joined) { "draft fixture producers did not complete; dependent resources remain open" }
            },
            {
                if (joined) {
                    // No global DataStore lifetime is inferred: this is a serialized reset after our producers finish.
                    val close: () -> Unit = {
                        runBlocking {
                            runCleanupSteps(listOf(
                                { if (app != null) resetSharedServers() },
                                *ownedServers.map { server -> suspend { server.close() } }.toTypedArray(),
                            ))
                        }
                    }
                    if (app == null) close() else app.scope.closeWithOwnedJobs(app.cacheDatabase, closeOther = close)
                }
            },
        )
        runBlocking { runCleanupSteps(steps) }
    }

    /** The main looper queues the collectors of [MainViewModel]; the test runs them by hand. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Runs [condition] with the main queue drained until it holds; no fixed wait. */
    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(10_000) {
            while (!condition()) {
                idle()
                delay(10)
            }
        }
    }

    /**
     * Runs a suspend call on the main dispatcher while the test keeps that dispatcher alive. A plain
     * [runBlocking] here would park Robolectric's main thread, and a continuation posted back to it
     * could never resume. The wait is bounded; a timeout reports what the main thread was doing.
     */
    internal fun onMain(block: suspend () -> Unit) {
        val done = CompletableDeferred<Unit>()
        // Body failures are recorded before done; cancellation/join failures follow them. A queue
        // also preserves a wait failure that happened before cancellation delivered a body failure.
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val job = driver.launch {
            try { block() } catch (failure: Throwable) { failures.add(failure) } finally { done.complete(Unit) }
        }
        try {
            runBlocking {
                withTimeout(10_000) {
                    while (!done.isCompleted) { idle(); delay(10) }
                }
            }
        } catch (failure: Throwable) {
            failures.add(if (failure is TimeoutCancellationException) AssertionError("the main call did not finish; ${mainThreadReport()}", failure) else failure)
        } finally {
            try { job.cancel() } catch (failure: Throwable) { failures.add(failure) }
            try {
                check(joinDraftJobs(listOf(job), joinDeadlineMillis, ::idle)) { "main driver did not complete cancellation" }
            } catch (cleanup: Throwable) {
                failures.add(cleanup)
            }
        }
        failures.poll()?.let { primary ->
            failures.filter { it !== primary }.forEach(primary::addSuppressed)
            throw primary
        }
    }

    /** A sampled Robolectric main stack at timeout; it does not identify the exact awaited producer. */
    private fun mainThreadReport(): String = Thread.getAllStackTraces().entries
        .firstOrNull { (thread, _) -> thread.name == "SDK 36 Main Thread" }
        ?.let { (thread, stack) -> "${thread.name} ${thread.state}: " + stack.take(12).joinToString(" | ") }
        ?: "the main thread is not running"

    internal fun acquireContainer() {
        if (::container.isInitialized) return
        // Same concrete constructor prerequisite as the accepted real search-wiring fixture.
        try { WorkManager.getInstance(context) } catch (_: IllegalStateException) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
        container = AppContainer(context, PlainCipher)
    }

    internal fun start(profile: ServerProfile): MainViewModel {
        acquireContainer()
        // A profile left in the shared synthetic DataStore must not become this case's fallback.
        runBlocking { container.servers.clearSyntheticServers() }
        connect(profile)
        return MainViewModel(container).also { vm = it }
    }

    private fun connect(profile: ServerProfile) {
        val previous = vm?.chat?.value
        onMain { container.servers.save(profile) }
        runBlocking { withTimeout(10_000) { container.connection.first { it?.session?.profile == profile } } }
        // The view model follows the connection on the main looper; a test that opens a chat at once
        // must not still see the controller of the previous server.
        if (previous != null) await { vm?.chat?.value !== previous }
    }

    private fun shown(): ChatController {
        val model = checkNotNull(vm)
        repeat(100) {
            idle()
            model.chat.value?.let { return it }
            Thread.sleep(10)
        }
        error("the chat did not open")
    }

    private fun open(id: String?): ChatController {
        val model = checkNotNull(vm)
        if (id == null) model.newChat() else model.openChat(id)
        idle()
        return shown()
    }

    private fun draftOf(controller: ChatController) = checkNotNull(checkNotNull(vm).draftOf(controller))

    private fun profile(id: String, on: LiveServer = server) = ServerProfile(id, id, on.http.url("/").toString())

    @Test
    fun `a draft survives switching to another chat and back`() {
        val model = start(profile("draft-a"))
        val first = open("s1")
        val draft = draftOf(first)
        draft.text = "half a thought"
        draft.attachments = listOf(LocalAttachment("report.pdf", "application/pdf", byteArrayOf(1, 2, 3)))
        draft.scrollIndex = 12
        draft.scrollOffset = 8
        draft.shownItems = 20

        val second = open("s2")
        assertThat(draftOf(second).text).isEmpty()
        draftOf(second).text = "for the other chat"

        val back = open("s1")
        val restored = draftOf(back)
        assertThat(restored.text).isEqualTo("half a thought")
        assertThat(restored.attachments.map { it.name }).containsExactly("report.pdf")
        assertThat(restored.attachments.single().bytes).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(restored.scrollIndex).isEqualTo(12)
        assertThat(restored.scrollOffset).isEqualTo(8)
        assertThat(restored.shownItems).isEqualTo(20)
        assertThat(model.chat.value).isNotSameInstanceAs(first)
    }

    @Test
    fun `an unsaved new chat keeps its words until they are sent`() {
        val model = start(profile("draft-new"))
        val fresh = open(null)
        draftOf(fresh).text = "an idea without a home"
        draftOf(fresh).attachments = listOf(LocalAttachment("photo.png", "image/png", byteArrayOf(4)))

        open("s1")
        val again = open(null)
        assertThat(draftOf(again).text).isEqualTo("an idea without a home")
        assertThat(draftOf(again).attachments.map { it.name }).containsExactly("photo.png")
        assertThat(model.chat.value).isSameInstanceAs(again)
    }

    @Test
    fun `a pending first send keeps its new chat apart from the next one`() {
        val profile = profile("draft-sending")
        server.holdCreate()
        val model = start(profile)
        val sending = shown()
        await { container.connection.value?.socket?.state?.value == SocketState.READY }
        await { server.createArrived.isCompleted }
        val draft = draftOf(sending)
        draft.text = "the first message"
        sending.send(draft.text, draft.attachments)
        assertThat(container.drafts.of(profile.id).isSendingNewChat()).isTrue()
        // ChatScreen clears the composer as soon as the controller has taken the message.
        draft.text = ""
        draft.text = "a follow-up not sent yet"
        assertThat(sending.state.value.storedId).isNull()
        assertThat(model.chat.value).isSameInstanceAs(sending)
        // The controller is off screen here, so its published state has not seen the sent message.
        // A refused new chat must not depend on that: the sending draft is still the owner.
        assertThat(container.drafts.of(profile.id).isSendingNewChat()).isTrue()

        // The first message is still waiting for its session, so this transition is refused.
        assertThat(model.newChat()).isFalse()
        idle()
        assertThat(model.chat.value).isSameInstanceAs(sending)
        assertThat(draft.text).isEqualTo("a follow-up not sent yet")
        assertThat(model.notice.value).isNotNull()
        model.noticeShown()

        // The sending chat leaves the screen for a stored one, but its message is still unnamed: the
        // next new chat stays refused rather than taking the draft that is waiting for its id.
        val saved = open("s2")
        model.chatShown(saved, true)
        idle()
        assertThat(model.chat.value).isSameInstanceAs(saved)
        assertThat(container.turns.visibleChat).isEqualTo("s2")
        assertThat(model.newChat()).isFalse()
        assertThat(model.notice.value).isNotNull()
        model.noticeShown()

        val listsBefore = server.listCount
        server.releaseCreate()
        await { checkNotNull(container.drafts.of(profile.id).obtain(DraftKey.Chat("s1"))).text == "a follow-up not sent yet" }
        idle()
        await { server.listCount > listsBefore }

        // The late first send still belongs to the chat that sent it. It may not take the visibility
        // or the notification owner away from the stored chat now on the screen.
        assertThat(model.chat.value).isSameInstanceAs(saved)
        assertThat(container.turns.visibleChat).isEqualTo("s2")
        assertThat(draftOf(saved).text).isEmpty()

        // The wait is over: the next new chat is empty, the follow-up is on the stored chat, and the
        // chat opened meanwhile kept its own empty draft.
        assertThat(model.newChat()).isTrue()
        idle()
        val fresh = shown()
        assertThat(draftOf(fresh).text).isEmpty()
        assertThat(draftOf(open("s1")).text).isEqualTo("a follow-up not sent yet")
        assertThat(draftOf(open("s2")).text).isEmpty()
    }

    @Test
    fun `a first send completing in the shown chat owns its visibility`() {
        val profile = profile("draft-shown-owner")
        server.holdCreate()
        val model = start(profile)
        val sending = shown()
        model.chatShown(sending, true)
        idle()
        await { container.connection.value?.socket?.state?.value == SocketState.READY }
        await { server.createArrived.isCompleted }
        val draft = draftOf(sending)
        draft.text = "the first message"
        sending.send(draft.text, draft.attachments)
        // ChatScreen clears the composer as soon as the controller has taken the message.
        draft.text = ""
        assertThat(sending.state.value.storedId).isNull()
        assertThat(container.turns.visibleChat).isNull()

        val listsBefore = server.listCount
        server.releaseCreate()
        await { sending.state.value.storedId == "s1" }
        await { server.listCount > listsBefore }

        assertThat(model.chat.value).isSameInstanceAs(sending)
        assertThat(container.turns.visibleChat).isEqualTo("s1")
        assertThat(draftOf(sending).text).isEmpty()
    }

    @Test
    fun `a pending first send keeps its words with its profile across a switch and back`() {
        val firstProfile = profile("draft-owner-a")
        server.holdCreate()
        start(firstProfile)
        val sending = shown()
        await { container.connection.value?.socket?.state?.value == SocketState.READY }
        await { server.createArrived.isCompleted }
        val draft = draftOf(sending)
        draft.text = "the first message of A"
        sending.send(draft.text, draft.attachments)
        draft.text = "a follow-up on A"
        assertThat(container.drafts.of(firstProfile.id).isSendingNewChat()).isTrue()

        // The user opens another server while A's first message waits for its session. A's socket
        // stops with the switch, so the send ends without naming the chat.
        connect(profile("draft-owner-b", otherServer))
        val bNew = shown()
        draftOf(bNew).text = "B has its own words"
        server.releaseCreate()
        await { !container.drafts.of(firstProfile.id).isSendingNewChat() }

        // Neither profile took the other's words: B keeps its new chat, A keeps the follow-up.
        assertThat(draftOf(bNew).text).isEqualTo("B has its own words")
        assertThat(checkNotNull(container.drafts.of(firstProfile.id).obtain(DraftKey.NewChat)).text).isEqualTo("a follow-up on A")

        // Back on A the unsaved new chat shows the follow-up again; B is unchanged on its server.
        connect(firstProfile)
        idle()
        val again = shown()
        assertThat(again.state.value.storedId).isNull()
        assertThat(draftOf(again).text).isEqualTo("a follow-up on A")

        connect(profile("draft-owner-b", otherServer))
        idle()
        val bAgain = shown()
        assertThat(bAgain.state.value.storedId).isNull()
        assertThat(draftOf(bAgain).text).isEqualTo("B has its own words")
    }

    @Test
    fun `another server with the same session id starts without the draft`() {
        start(profile("draft-server-one"))
        draftOf(open("s1")).text = "private to server one"

        val model = checkNotNull(vm)
        connect(profile("draft-server-two"))
        val other = open("s1")
        assertThat(draftOf(other).text).isEmpty()
        assertThat(model.chat.value).isSameInstanceAs(other)
    }

    @Test
    fun `a server keeps its drafts while another server is open`() {
        start(profile("draft-round-one"))
        draftOf(open("s1")).text = "server one draft"

        connect(profile("draft-round-two"))
        idle()
        val second = open("s1")
        assertThat(draftOf(second).text).isEmpty()
        draftOf(second).text = "server two draft"

        connect(profile("draft-round-one"))
        idle()
        assertThat(draftOf(open("s1")).text).isEqualTo("server one draft")
    }

    @Test
    fun `a rebuilt connection of the same profile keeps the draft`() {
        val original = profile("draft-rebuild")
        start(original)
        draftOf(open("s1")).text = "same server"
        val before = checkNotNull(container.connection.value)

        onMain { container.servers.save(original.copy(name = "Renamed")) }
        runBlocking { withTimeout(10_000) { container.connection.first { it?.session?.profile?.name == "Renamed" } } }
        idle()

        assertThat(container.connection.value).isNotSameInstanceAs(before)
        assertThat(draftOf(open("s1")).text).isEqualTo("same server")
    }

    @Test
    fun `a full memory refuses a new chat and keeps the current one`() {
        val model = start(profile("draft-full"))
        draftOf(open(null)).text = "new chat draft"
        // The app keeps sixteen drafts in all: the new chat and fifteen stored chats fill every slot.
        repeat(15) { index -> draftOf(open("s${index + 1}")).text = "filled ${index + 1}" }
        val current = checkNotNull(model.chat.value)
        val currentDraft = draftOf(current)

        model.openChat("s16")
        idle()

        assertThat(model.chat.value).isSameInstanceAs(current)
        assertThat(draftOf(current)).isSameInstanceAs(currentDraft)
        assertThat(currentDraft.text).isEqualTo("filled 15")
        assertThat(model.notice.value).isNotNull()

        val retained = open("s1")
        assertThat(draftOf(retained).text).isEqualTo("filled 1")
    }

    @Test
    fun `signing out drops only the drafts of its own server`() {
        start(profile("draft-signout-one"))
        draftOf(open("s1")).text = "server one plan"

        val model = checkNotNull(vm)
        connect(profile("draft-signout-two"))
        idle()
        draftOf(open("s1")).text = "server two plan"

        onMain { model.signOut() }
        idle()

        connect(profile("draft-signout-one"))
        idle()
        assertThat(draftOf(open("s1")).text).isEqualTo("server one plan")
    }

    @Test
    fun `a delete finishing after a switch touches only its own server`() {
        val first = profile("draft-late-one", server)
        val model = start(first)
        draftOf(open("s1")).text = "first server draft"

        server.deleteGate = CountDownLatch(1)
        val root = checkNotNull(model.viewModelScope.coroutineContext[Job])
        val before = root.children.toSet()
        model.deleteChat("s1")
        val mutation = root.children.single { it !in before }
        await { server.deleteArrived.count == 0L }

        // The user opens another server while the first server is still deleting.
        val second = profile("draft-late-two", otherServer)
        connect(second)
        // A null chat also differs from the old controller. Wait for the initial controller
        // bound to the replacement connection before opening and editing its stored chat.
        await { model.chat.value?.let { model.draftOf(it) } != null }
        draftOf(open("s1")).text = "second server draft"
        val secondChat = checkNotNull(model.chat.value)

        server.releaseDelete()

        // Observe the actual operation, then assert its outcome rather than waiting for that outcome.
        await { mutation.isCompleted }
        assertThat(model.notice.value).isNull()
        assertThat(checkNotNull(container.drafts.of(first.id).obtain(DraftKey.Chat("s1"))).text).isEmpty()
        idle()

        assertThat(draftOf(open("s1")).text).isEqualTo("second server draft")
        assertThat(model.chat.value).isSameInstanceAs(secondChat)
    }

    @Test
    fun `deleting a chat drops its draft`() {
        start(profile("draft-delete"))
        val model = checkNotNull(vm)
        draftOf(open("s1")).text = "delete me"

        model.deleteChat("s1")
        for (attempt in 0 until 200) {
            idle()
            if (model.chat.value?.state?.value?.storedId == null) break
            Thread.sleep(10)
        }

        assertThat(draftOf(open("s1")).text).isEmpty()
    }

    @Test
    fun `signing out drops the drafts of the server`() {
        start(profile("draft-signout"))
        val model = checkNotNull(vm)
        draftOf(open("s1")).text = "secret plan"

        onMain { model.signOut() }
        idle()

        assertThat(draftOf(open("s1")).text).isEmpty()
    }

    @Test
    fun `removing the server leaves no draft behind`() {
        start(profile("draft-removed"))
        val model = checkNotNull(vm)
        draftOf(open("s1")).text = "lives with the server"

        onMain { container.servers.remove("draft-removed") }
        runBlocking { withTimeout(10_000) { container.connection.first { it?.session?.profile?.id != "draft-removed" } } }
        runBlocking {
            withTimeout(10_000) {
                while ("draft-removed" in container.drafts.retainedProfiles()) delay(10)
            }
        }
        connect(profile("draft-removed"))
        idle()

        assertThat(draftOf(open("s1")).text).isEmpty()
        assertThat(model.chat.value?.state?.value?.storedId).isEqualTo("s1")
    }

    @Test
    fun `a refused search activation preserves the route and draft then admits a freed slot`() {
        val model = start(profile("draft-search-capacity"))
        draftOf(open(null)).text = "new words"
        repeat(15) { index -> draftOf(open("s${index + 1}")).text = "keep ${index + 1}" }
        val current = checkNotNull(model.chat.value)
        val draft = draftOf(current)
        model.push(Screen.Search)
        model.setQuery("capacity")
        idle()
        assertThat(server.searchCount).isEqualTo(0)
        // Robolectric's paused Handler clock, unlike wall time, does not advance in idle().
        shadowOf(Looper.getMainLooper()).idleFor(299, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertThat(server.searchCount).isEqualTo(0)
        shadowOf(Looper.getMainLooper()).idleFor(1, java.util.concurrent.TimeUnit.MILLISECONDS)
        await { server.searchCount == 1 }
        await { !model.search.value.searching }
        assertThat(model.search.value.error).isNull()
        val hit = model.search.value.hits.single()
        val identity = model.search.value.request
        val search = model.search.value
        model.openSearchHit(hit, identity)
        assertThat(model.chat.value).isSameInstanceAs(current)
        assertThat(model.stack).containsExactly(Screen.Search)
        assertThat(model.search.value).isSameInstanceAs(search)
        assertThat(draft.text).isEqualTo("keep 15")
        assertThat(model.notice.value).isNotNull()
        model.noticeShown()
        // Empty an existing mutable entry after insertion: the same result is now admitted.
        draft.text = ""
        model.openSearchHit(hit, identity)
        assertThat(model.chat.value).isNotSameInstanceAs(current)
        assertThat(model.chat.value?.state?.value?.storedId).isEqualTo("s16")
        assertThat(model.stack).isEmpty()
        assertThat(model.notice.value).isNull()
        assertThat(draftOf(open("s1")).text).isEqualTo("keep 1")
    }

    @Test
    fun `a signout suspended at the real cache transaction clears only its captured profile`() = heldRoomSignout()

    @Test
    fun heldRoomPhaseKeepsOtherProfileResponseWritesOutsideAdmission() = heldRoomSignout(probe = true)

    /** Both cases own the same installed phase; only the regression enables its independent probe. */
    private fun heldRoomSignout(probe: Boolean = false) {
        acquireContainer()
        val phase = DraftRoomWritePhase(container.responses, probe)
        var primary: Throwable? = null
        try { heldRoomSignoutScenario(phase, probe) } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try { phase.close() } catch (cleanup: Throwable) {
                val failure = primary
                if (failure == null) throw cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }

    private fun heldRoomSignoutScenario(phase: DraftRoomWritePhase, probe: Boolean) {
        val first = profile("draft-gated-signout-a", server)
        val model = start(first)
        val firstDraft = draftOf(open("s1"))
        firstDraft.text = "A retained words"
        firstDraft.attachments = listOf(LocalAttachment("a.txt", "text/plain", byteArrayOf(7)))
        onMain { container.servers.setTokens(first.id, Tokens("synthetic-access", "synthetic-refresh")) }

        // ROOM_PHASE_BEGIN INITIAL_A_ONLY
        if (probe) phase.let {
            // Only this regression establishes completion of concrete initial-A foreground work.
            onMain { model.checkAccess() }
            onMain {
                checkNotNull(model.accessProducer).join()
                model.listProducer?.join()
                model.cachedListProducer?.join()
            }
            val initialChat = checkNotNull(model.chat.value)
            await { initialChat.loadCompletion != null }
            onMain { checkNotNull(initialChat.loadCompletion).await() }
            runBlocking { withTimeout(10_000) { it.producers.toList().forEach { child -> child.join() } } }
            runBlocking { assertThat(container.cacheDatabase.responses().byUse().any { row -> row.server == first.id }).isTrue() }
        }
        // ROOM_PHASE_END INITIAL_A_ONLY

        // ROOM_PHASE_BEGIN SHARED_ARM
        phase.arm(first.id)
        // ROOM_PHASE_END SHARED_ARM
        val retired = CompletableDeferred<Unit>()
        val admitCleanup = CompletableDeferred<Unit>()
        val cacheEntered = CompletableDeferred<Unit>()
        container.lifetime.beforeCleanupAdmission = { owner, removed ->
            if (owner.profileId == first.id && !removed) { retired.complete(Unit); admitCleanup.await() }
        }
        container.responses.onForgetStarting = { if (it == first.id) cacheEntered.complete(Unit) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction = container.scope.launch(Dispatchers.IO) {
            container.cacheDatabase.withTransaction {
                entered.complete(Unit)
                release.await()
            }
        }
        var operation: kotlinx.coroutines.Deferred<Unit>? = null
        var primary: Throwable? = null
        try {
            await { entered.isCompleted }
            operation = driver.async { model.signOut() }
            await { retired.isCompleted }
            // This serialized store update precedes cache deletion in the actual sign-out chain.
            onMain { container.servers.state.first { first.id !in it.tokens } }
            assertThat(operation.isCompleted).isFalse()
            assertThat(container.drafts.retainedProfiles()).contains(first.id)
            assertThat(checkNotNull(container.drafts.of(first.id).obtain(DraftKey.Chat("s1"))).text).isEqualTo("A retained words")
            val second = profile("draft-gated-signout-b", otherServer)
            assertThat(second.url).isNotEqualTo(first.url)
            connect(second)
            val secondChat = open("s1")
            model.chatShown(secondChat, true)
            val secondDraft = draftOf(secondChat)
            secondDraft.text = "B retained words"
            secondDraft.attachments = listOf(LocalAttachment("b.txt", "text/plain", byteArrayOf(8)))

        // ROOM_PHASE_BEGIN ACTUAL_DECISION
            if (probe) phase.let { tracer ->
                var observed: DraftRoomWritePhase.Decision? = null
                onMain { observed = withTimeout(10_000) { tracer.decision.await() } }
                val decision = checkNotNull(observed)
                val write = decision.write
                tracer.selected = write
                val origin = checkNotNull(container.connection.value)
                assertThat(origin.session.profile).isEqualTo(second)
                assertThat(write.owner.profileId).isEqualTo(second.id)
                assertThat(origin.session.holds(write.owner)).isTrue()
                assertThat(tracer.producers.any { it === write.child }).isTrue()
                assertThat(entered.isCompleted && retired.isCompleted).isTrue()
                assertThat(transaction.isCompleted || operation.isCompleted).isFalse()
                assertThat(tracer.phaseRelease.isCompleted || tracer.safetyRelease.isCompleted).isFalse()
                // Derive the complete key from a real B HTTP dispatch, including UI filters.
                val dispatchedKeys = otherServer.requests.filter { it.url.encodedPath == "/api/sessions" }.map { request ->
                    org.evsyukov.hermes.data.remote.cacheKey(request.url.encodedPath,
                        request.url.queryParameterNames.associateWith { request.url.queryParameter(it) })
                }
                assertThat(dispatchedKeys).contains(write.key)
                assertThat(write.child.isCompleted).isFalse()
                println("ROOM_PHASE_OPPORTUNITY boundary=${decision.boundary} owner=${write.owner} key=${write.key} actualChild=${write.child} registeredChild=true roomEntered=true retirementPaused=true appliedBOrigin=true safetyPending=true")
                com.google.common.truth.Truth.assertWithMessage("DRAFT_ROOM_HOLD_WRITE_PHASE_NOT_HELD")
                    .that(decision.boundary).isEqualTo(DraftRoomWritePhase.Boundary.HELD_PHASE)
                // A future held decision must stand without the independent legacy safety stop.
                tracer.safetyRelease.complete(Unit)
            }
        // ROOM_PHASE_END ACTUAL_DECISION
            admitCleanup.complete(Unit)
            await { cacheEntered.isCompleted }
            assertThat(operation.isCompleted).isFalse()
            assertThat(transaction.isCompleted).isFalse()

        // ROOM_PHASE_BEGIN HELD_ROOM_GUARD
            if (probe) phase.let { tracer ->
                val write = checkNotNull(tracer.selected)
                assertThat(tracer.phaseRelease.isCompleted).isFalse()
                assertThat(write.child.isCompleted).isFalse()
                assertThat(tracer.legacy.any { it.write.child === write.child }).isFalse()
                println("ROOM_PHASE_POSITIVE A-cache-cleanup-entered while Room and B phase remain held")
            }
        // ROOM_PHASE_END HELD_ROOM_GUARD
            release.complete(Unit)
            await { operation.isCompleted }
            onMain { operation.await() }

        // ROOM_PHASE_BEGIN RELEASED_B_EFFECT
            if (probe) phase.let { tracer ->
                val write = checkNotNull(tracer.selected)
                val origin = checkNotNull(container.connection.value)
                assertThat(origin.session.holds(write.owner)).isTrue()
                assertThat(tracer.phaseRelease.isCompleted).isFalse()
                assertThat(write.child.isCompleted).isFalse()
                runBlocking { assertThat(container.cacheDatabase.responses().response(write.owner.profileId, write.key)).isNull() }
            }
        // ROOM_PHASE_END RELEASED_B_EFFECT

        // ROOM_PHASE_BEGIN SHARED_B_RELEASE
            phase.phaseRelease.complete(Unit)
        // ROOM_PHASE_END SHARED_B_RELEASE

        // ROOM_PHASE_BEGIN B_AFTER_RELEASE
            if (probe) phase.let { tracer ->
                val write = checkNotNull(tracer.selected)
                onMain { withTimeout(10_000) { write.child.join() } }
                assertThat(write.child.isCompleted && !write.child.isCancelled).isTrue()
                runBlocking {
                    val dao = container.cacheDatabase.responses()
                    assertThat(dao.byUse().any { it.server == first.id }).isFalse()
                    assertThat(dao.response(write.owner.profileId, write.key)?.request).isEqualTo(write.key)
                    assertThat(dao.parts(write.owner.profileId, write.key).joinToString("")).isEqualTo("{\"sessions\":[],\"total\":0}")
                    assertThat(container.responses.read(write.owner.profileId, write.key)).isEqualTo("{\"sessions\":[],\"total\":0}")
                }
                assertThat(tracer.legacy.single { it.write.child === write.child }.beforePhaseRelease).isFalse()
                // Every attributed other-profile writer is retained, not treated as quiescent by one selected callback.
                onMain { withTimeout(10_000) { tracer.otherWrites.toList().forEach { it.child.join() } } }
                println("ROOM_PHASE_POSITIVE B actual writer/effect verified; attributedOtherWriters=${tracer.otherWrites.size}")
            }
        // ROOM_PHASE_END B_AFTER_RELEASE
            assertThat(transaction.isCompleted).isTrue()
            assertThat(container.drafts.retainedProfiles()).doesNotContain(first.id)
            assertThat(model.chat.value).isSameInstanceAs(secondChat)
            assertThat(container.connection.value?.session?.profile).isEqualTo(second)
            assertThat(container.turns.visibleChat).isEqualTo("s1")
            assertThat(secondDraft.text).isEqualTo("B retained words")
            assertThat(secondDraft.attachments.single().name).isEqualTo("b.txt")
            com.google.common.truth.Truth.assertWithMessage("DRAFT_ROOM_B_CURRENT_DRAFT_BINDING_RETAINED")
                .that(model.draftOf(secondChat)).isSameInstanceAs(secondDraft)
            connect(first)
            assertThat(draftOf(open("s1")).text).isEmpty()
            assertThat(draftOf(open("s1")).attachments).isEmpty()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {

        // ROOM_PHASE_BEGIN INDEPENDENT_RELEASE
            phase.releaseAll()
        // ROOM_PHASE_END INDEPENDENT_RELEASE
            admitCleanup.complete(Unit)
            container.lifetime.beforeCleanupAdmission = null
            container.responses.onForgetStarting = null
            try {
                runBlocking {
                    runCleanupSteps(listOf(
                        { release.complete(Unit) },
                        { operation?.let { pending -> await { pending.isCompleted }; onMain { pending.await() } } },
                        { check(joinDraftJobs(listOf(transaction), 5_000, ::idle)) { "held Room transaction did not finish" } },
                    ))
                }

        // ROOM_PHASE_BEGIN CLEANUP_EXIT
                if (probe) phase.let {
                    check(operation?.isCompleted != false && transaction.isCompleted)
                    println("ROOM_PHASE_CLEANUP actual operation and held Room transaction exited")
                }
        // ROOM_PHASE_END CLEANUP_EXIT
            } catch (cleanup: Throwable) {
                val failure = primary
                if (failure == null) throw cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }

    @Test
    fun `a retained controller cannot look up or mutate the current other profile draft`() {
        val a = profile("lookup-a", server)
        val b = profile("lookup-b", otherServer)
        assertThat(a.url).isNotEqualTo(b.url)
        val model = start(a)
        val first = open("s1")
        val firstDraft = draftOf(first)
        firstDraft.text = "A words"
        firstDraft.attachments = listOf(LocalAttachment("a.txt", "text/plain", byteArrayOf(1)))
        connect(b)
        val current = open("s1")
        val currentDraft = draftOf(current)
        currentDraft.text = "B words"
        currentDraft.attachments = listOf(LocalAttachment("b.txt", "text/plain", byteArrayOf(2)))
        assertThat(model.draftOf(current)).isSameInstanceAs(currentDraft)

        val stale = model.draftOf(first)
        stale?.text = "a stale caller wrote here"
        stale?.attachments = emptyList()
        assertThat(currentDraft.text).isEqualTo("B words")
        assertThat(currentDraft.attachments.single().name).isEqualTo("b.txt")
        assertThat(currentDraft.attachments.single().bytes).isEqualTo(byteArrayOf(2))
        assertThat(stale).isNull()
        assertThat(model.draftOf(current)).isSameInstanceAs(currentDraft)
        connect(a)
        val restored = open("s1")
        assertThat(restored).isNotSameInstanceAs(first)
        assertThat(draftOf(restored).text).isEqualTo("A words")
        assertThat(draftOf(restored).attachments.single().bytes).isEqualTo(byteArrayOf(1))
    }

    @Test
    fun `a removed profile controller lookup cannot recreate its draft store`() {
        val a = profile("lookup-removed", server)
        val model = start(a)
        val first = open("s1")
        draftOf(first).text = "retired words"
        onMain { container.servers.remove(a.id) }
        await { model.chat.value == null && a.id !in container.drafts.retainedProfiles() }
        val profiles = container.drafts.retainedProfiles()
        assertThat(model.draftOf(first)).isNull()
        assertThat(container.drafts.retainedProfiles()).isEqualTo(profiles)
        connect(a)
        assertThat(draftOf(open("s1")).text).isEmpty()
    }

    @Test
    fun `a separately collected old connection cannot pair with the current controller draft`() {
        val model = start(profile("lookup-pair-a", server))
        open("s1")
        val previousConnection = checkNotNull(container.connection.value)
        connect(profile("lookup-pair-b", otherServer))
        val current = open("s1")
        val currentConnection = checkNotNull(container.connection.value)
        val draft = draftOf(current)
        draft.text = "current pair"
        assertThat(currentConnection).isNotSameInstanceAs(previousConnection)
        assertThat(model.draftOf(current, currentConnection)).isSameInstanceAs(draft)
        assertThat(model.draftOf(current, previousConnection)).isNull()
        assertThat(model.draftOf(current, currentConnection)).isSameInstanceAs(draft)
        assertThat(draft.text).isEqualTo("current pair")
    }

    @Test
    fun `signing out from an unsaved new chat installs a fresh usable binding`() {
        val model = start(profile("lookup-signout-new", server))
        val before = open(null)
        val oldDraft = draftOf(before)
        oldDraft.text = "clear these unsent words"
        oldDraft.attachments = listOf(LocalAttachment("old.txt", "text/plain", byteArrayOf(3)))
        onMain { model.signOut() }
        val current = checkNotNull(model.chat.value)
        val fresh = model.draftOf(current)
        assertThat(fresh).isNotNull()
        assertThat(current).isNotSameInstanceAs(before)
        assertThat(model.draftOf(before)).isNull()
        assertThat(checkNotNull(fresh).text).isEmpty()
        assertThat(fresh.attachments).isEmpty()
        fresh.text = "a fresh draft"
        open("s2")
        assertThat(draftOf(open(null)).text).isEqualTo("a fresh draft")
    }

    @Test
    fun `completed local observations survive navigation and held signout clears only captured profile`() {
        val first = profile("observation-navigation-a", server)
        val model = start(first)
        fun complete(on: LiveServer, controller: ChatController, text: String): org.evsyukov.hermes.chat.LocalObservation {
            val writes = controller.continuationProducers
            model.chatShown(controller, true)
            onMain {
                checkNotNull(controller.loadCompletion).await()
                controller.send(text, listOf(LocalAttachment("note.txt", "text/plain", byteArrayOf(7))))
                controller.state.first { state -> (state.items.lastOrNull() as? ChatItem.User)?.delivery == Delivery.SENT }
            }
            on.sendEvent("message.start", buildJsonObject {})
            on.sendEvent("message.delta", buildJsonObject { put("text", "on-device $text") })
            on.sendEvent("message.complete", buildJsonObject { put("text", "on-device $text"); put("status", "complete") })
            onMain {
                controller.state.first { it.observations.any { row -> row.completed } }
                writes.toList().joinAll()
            }
            return controller.state.value.observations.single()
        }
        val oldChat = open("s1")
        val retained = complete(server, oldChat, "A prompt")
        open("s2")
        val recovered = open("s1")
        model.chatShown(recovered, true)
        onMain { recovered.observationRecovery.join(); checkNotNull(recovered.loadCompletion).await() }
        assertThat(recovered.state.value.observations.single().items).isEqualTo(retained.items)
        assertThat(recovered.state.value.observations.single().files).isEqualTo(retained.files)
        assertThat(server.submittedPrompts.get()).isEqualTo(1)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        container.lifetime.beforeCleanupAdmission = { owner, removed -> if (owner.profileId == first.id && !removed) { entered.complete(Unit); release.await() } }
        val operation = driver.async { model.signOut() }
        var primary: Throwable? = null
        try {
            await { entered.isCompleted }
            assertThat(operation.isCompleted).isFalse()
            val second = profile("observation-navigation-b", otherServer)
            connect(second)
            val secondChat = open("s1")
            val other = complete(otherServer, secondChat, "B prompt")
            model.chatShown(secondChat, true)
            release.complete(Unit)
            await { operation.isCompleted }
            onMain { operation.await() }
            assertThat(model.chat.value).isSameInstanceAs(secondChat)
            assertThat(container.turns.visibleChat).isEqualTo("s1")
            onMain {
                val saved = container.servers.observations.load(container.lifetime.owner(second.id), second.url.toString(), "s1")
                assertThat(saved.single().id).isEqualTo(other.id)
                assertThat(saved.single().items).isEqualTo(other.items)
                assertThat(container.servers.observations.load(container.lifetime.owner(first.id), first.url.toString(), "s1")).isEmpty()
            }
            assertThat(server.submittedPrompts.get()).isEqualTo(1)
            assertThat(otherServer.submittedPrompts.get()).isEqualTo(1)
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            release.complete(Unit)
            container.lifetime.beforeCleanupAdmission = null
            try { check(joinDraftJobs(listOf(operation), 5_000, ::idle)) { "Observation signout driver did not complete" }; onMain { operation.await() } }
            catch (cleanup: Throwable) { val firstFailure = primary; if (firstFailure == null) throw cleanup else if (firstFailure !== cleanup) firstFailure.addSuppressed(cleanup) }
        }
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}

/**
 * Runs every cleanup step even after one failed: stopping at the first leaves the remaining
 * servers and scopes of the case open. The first failure stays the reported one, with the later
 * ones attached as suppressed.
 */
internal suspend fun runCleanupSteps(steps: List<suspend () -> Unit>) {
    var primary: Throwable? = null
    for (step in steps) {
        try {
            step()
        } catch (t: Throwable) {
            val first = primary
            if (first == null) primary = t else if (first !== t) first.addSuppressed(t)
        }
    }
    primary?.let { throw it }
}

/** Pumps the actual fixture dispatcher while waiting for completed jobs, with a real deadline. */
internal fun joinDraftJobs(roots: List<Job>, timeoutMillis: Long, pump: () -> Unit): Boolean = runBlocking {
    val joined = async(Dispatchers.Default) {
        withTimeoutOrNull(timeoutMillis) { roots.forEach { it.join() }; true } ?: false
    }
    while (!joined.isCompleted) {
        pump()
        yield()
    }
    joined.await()
}
