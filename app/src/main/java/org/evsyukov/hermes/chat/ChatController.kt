package org.evsyukov.hermes.chat

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.R
import org.evsyukov.hermes.TIMING_TAG
import org.evsyukov.hermes.background.TurnHold
import org.evsyukov.hermes.data.remote.GatewayEvent
import org.evsyukov.hermes.data.remote.GatewayChannel
import org.evsyukov.hermes.data.remote.RpcException
import org.evsyukov.hermes.data.remote.ServerRequest
import org.evsyukov.hermes.data.remote.SessionUsage
import org.evsyukov.hermes.data.remote.SocketState
import org.evsyukov.hermes.data.remote.bool
import org.evsyukov.hermes.data.remote.string
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.WorkOwner
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A file picked on the phone and not sent yet. */
class LocalAttachment(val name: String, val mime: String?, val bytes: ByteArray) {
    val image: Boolean get() = mime?.startsWith("image/") == true
}

/**
 * A request of the agent that waits for the user in a sheet: approve a command or give a secret. Its
 * questions are cards of the chat instead, [QuestionCard].
 */
sealed interface Prompt {
    val request: ServerRequest

    data class Approval(
        override val request: ServerRequest,
        val kind: ApprovalKind,
        val command: String,
        val description: String,
        val choices: List<String>,
    ) : Prompt
    data class Secret(override val request: ServerRequest, val title: String, val sudo: Boolean) : Prompt
}

data class ChatState(
    val storedId: String? = null,
    val title: String = "",
    val items: List<ChatItem> = emptyList(),
    /** Fetched canonical lane, before any optimistic working-transcript merge. */
    val storedHistory: List<ChatItem>? = null,
    val observations: List<LocalObservation> = emptyList(),
    val currentObservationId: String? = null,
    val observationStorageError: Boolean = false,
    val loading: Boolean = false,
    val running: Boolean = false,
    val prompt: Prompt? = null,
    val error: String? = null,
    val model: String? = null,
    /**
     * The model was picked while a turn ran and the server applies it at the next turn; the turn
     * under way still runs on the previous one.
     */
    val modelPending: Boolean = false,
    val status: String? = null,
    /** A message waits for the socket to connect. */
    val connecting: Boolean = false,
    /** An expensive model waits for a yes: provider, model and the server's warning. */
    val modelConfirm: Triple<String, String, String>? = null,
    /** Tokens and cost of the chat so far, as of its last finished turn. */
    val usage: SessionUsage? = null,
    /** Connection operations seen in this chat, oldest first; a settled one stays as its outcome. */
    val connections: List<ConnectionOp> = emptyList(),
    /** The questions the agent asked in this chat, oldest first; an answered or closed one stays as its outcome. */
    val questions: List<QuestionCard> = emptyList(),
) {
    /** A turn runs, and no open answer shows it yet: the chat says it is waiting under the last message. */
    val awaitingAnswer: Boolean get() = running && (items.lastOrNull() as? ChatItem.Assistant)?.streaming != true
}

/**
 * One open chat. A new chat creates its server session while it is still empty, so the agent is built
 * before the first message; the server keeps no chat for it until that message, and leaving the empty
 * chat closes the session. An existing chat shows its stored history at once and joins the live
 * session in the background, so a turn that runs elsewhere keeps streaming here.
 *
 * A chat that is not on the screen of the app in front keeps working on its state, the screen's copy
 * of it stays as it was: the words and reasoning that stream meanwhile are only appended to a buffer
 * per piece of the answer, and go into the transcript at once when a tool, a message boundary or the
 * screen needs it. Shown again, the chat publishes what it has, once, and asks the server nothing.
 */
class ChatController(
    private val session: ServerSession,
    private val socket: GatewayChannel,
    private val scope: CoroutineScope,
    private val strings: AppStrings,
    storedId: String?,
    private val onSessionCreated: (String) -> Unit = {},
    /** Tells the background watcher which live session belongs to which chat. */
    private val onLive: (liveId: String, storedId: String, title: String) -> Unit = { _, _, _ -> },
    /**
     * Called right before a turn starts, while the app is surely in front; the hold it returns learns
     * the session of the turn and is released when the submission fails.
     */
    private val onTurnStart: () -> TurnHold = { TurnHold.NONE },
    /**
     * The messages of the chat are on the screen up to the server's `last_active`; tells the read
     * mark. [owner] is the profile and authorization the load started in, so a callback that comes
     * after a sign-out is not taken as work of the newer authorization.
     */
    private val onRead: (storedId: String, lastActive: Double, owner: WorkOwner) -> Unit = { _, _, _ -> },
    /** Whether the chat starts on the screen; the screen tells it afterwards with [setVisible]. */
    visible: Boolean = true,
    /** The questions of the chats of this connection, which outlive this chat being open. */
    private val questionMemory: QuestionMemory = QuestionMemory(),
    private val wanted: () -> Boolean = { true },
) {
    /** Orders every change of the chat's state, its publication and the change of its visibility. */
    private val lock = Any()

    /** The chat's own state, always current but for the words in [streamed]; guarded by [lock]. */
    private val _state = MutableStateFlow(
        ChatState(
            storedId = storedId,
            // Kept from an earlier visit: shown at once, answered only once this chat's join confirms them.
            questions = questionMemory.cards(storedId.orEmpty()).map { it.copy(confirmed = false) },
        ),
    )

    /** Per-operation carrier. A root without a ready transport binds once, never to its replacement. */
    private class Work(val owner: WorkOwner, val chat: String?, transport: Long, val observationTicket: ObservationTicket? = null, val publicObservationProducer: Boolean = false) {
        val transport = AtomicLong(transport)
    }
    private val workContext = ThreadLocal<Work?>()
    private fun captureWork(owner: WorkOwner = session.owner(), inheritedTicket: ObservationTicket? = null, publicObservationProducer: Boolean = false): Work {
        val transport = socket.transport.value
        val real = socket as? org.evsyukov.hermes.data.remote.GatewaySocket
        val eligibleTransport = if (real != null && real.ownerOn(transport) != owner) 0L else transport
        return Work(owner, _state.value.storedId, eligibleTransport, inheritedTicket ?: session.observations.capture(owner.profileId, session.profile.url.toString(), _state.value.storedId.orEmpty()), publicObservationProducer)
    }
    private val initialWork = captureWork()
    private val observationRecoveryTicket = checkNotNull(initialWork.observationTicket)
    @Volatile private var lastWork: Work? = null
    @Volatile private var draftWork: Work? = null
    private fun holds(owner: WorkOwner) = wanted() && session.holds(owner)
    private fun <T> publish(owner: WorkOwner, block: () -> T): T? = session.publish(owner) {
        val origin = workContext.get()
        if (wanted() && (origin == null || origin.owner != owner || publicationEligible(origin))) block() else null
    }
    /** Test-only capture boundary, outside publication/controller monitors; null in production. */
    @Volatile internal var afterPublicRootCaptured: ((String) -> Unit)? = null
    private fun withRoot(owner: WorkOwner? = null, publicAction: String? = null, block: (Work) -> Unit) {
        if (closed) return
        val origin = captureWork(owner ?: session.owner(), publicObservationProducer = publicAction != null)
        publicAction?.let { afterPublicRootCaptured?.invoke(it) }
        publish(origin.owner) {
            if (!observationProducerEligible(origin)) return@publish
            val prior = workContext.get()
            workContext.set(origin)
            try { block(origin) } finally { workContext.set(prior) }
        }
    }
    private fun launchOwned(origin: Work, allowClosed: Boolean = false, start: kotlinx.coroutines.CoroutineStart = kotlinx.coroutines.CoroutineStart.DEFAULT, block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(workContext.asContextElement(origin), start = start) {
            if (!holds(origin.owner) || (closed && !allowClosed)) throw CancellationException("Retired chat origin")
            block()
        }.also(::recordContinuation)
    private fun operation(): Work = checkNotNull(workContext.get()) { "Chat continuation requires its originating work" }
    // Group content and its immutable writer must share clear admission. In particular a
    // suspended pre-clear public root cannot edit a fresh group's working suffix, even when
    // begin() would reuse that group or rewrite would replace its writer. Current wire input
    // is observed independently; it is not permission for an older public producer's payload.
    private fun observationProducerEligible(origin: Work): Boolean {
        if (!origin.publicObservationProducer) return true
        val target = _state.value.storedId.orEmpty()
        val ticket = checkNotNull(origin.observationTicket)
        if (session.observations.valid(ticket, target)) return true
        return observationWrites.values.none { writer -> writer.owner == origin.owner && session.observations.valid(writer.ticket, target) }
    }
    private fun publicationEligible(origin: Work): Boolean {
        val bound = origin.transport.get()
        val current = socket.transport.value
        return holds(origin.owner) && observationProducerEligible(origin) && (bound == 0L || current == 0L || bound == current)
    }
    private suspend fun awaitSocket(origin: Work = operation()) {
        if (!holds(origin.owner)) throw IOException("Retired chat origin")
        if (origin.transport.get() == 0L) {
            if (socket is org.evsyukov.hermes.data.remote.GatewaySocket) socket.awaitReady(origin.owner) else socket.awaitReady()
            if (!holds(origin.owner)) throw IOException("Retired chat origin")
            origin.transport.compareAndSet(0L, socket.transport.value)
        }
        if (origin.transport.get() == 0L || origin.transport.get() != socket.transport.value || socket.state.value != SocketState.READY) throw IOException("Retired chat transport")
    }
    private suspend fun rpc(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMillis: Long = 60_000): JsonElement {
        val origin = operation()
        if (!observationProducerEligible(origin)) throw CancellationException("Retired observation producer")
        awaitSocket(origin)
        val result = socket.callOn(origin.owner, origin.transport.get(), method, params, timeoutMillis)
        if (!publicationEligible(origin)) throw IOException("Retired chat operation")
        return result
    }
    private fun apiForWork() = session.chatFor(operation().owner)
    private fun respond(origin: Work, id: String, result: JsonElement?, error: String? = null) {
        if (!holds(origin.owner)) return
        socket.respondOwned(origin.owner, origin.transport.get(), id, result, error)
    }


    /** What the screen sees: the state as of the last change made while the chat was shown. */
    private val published = MutableStateFlow(_state.value)
    val state: StateFlow<ChatState> = published.asStateFlow()

    /** The chat is on the screen of an app in front. */
    @Volatile
    private var visible = visible

    /**
     * The words and reasoning a hidden chat received since its transcript last changed, in the order
     * they came, one buffer per run of one kind: as many buffers as pieces of the answer, however many
     * deltas came. Guarded by [lock].
     */
    private val streamed = ArrayList<Pair<String, StringBuilder>>()

    /** When the first of [streamed] came: an answer they open takes that time. */
    private var streamedAt = 0.0

    /** What the chat read while hidden, and the work that read it, to tell the mark once it is shown. */
    private var pendingRead: Pair<Double, WorkOwner>? = null

    /** The state without what [streamed] holds, for tests that check no delta rebuilt it. */
    @VisibleForTesting
    internal val working: ChatState get() = _state.value

    /** The connection the chat's current join came over, for tests; 0 while it has no join it can trust. */
    @VisibleForTesting
    internal val joinedTransport: Long get() = joinedOn

    /** The state with what [streamed] holds, for code that reads the transcript. */
    private val current: ChatState get() = synchronized(lock) {
        flushStreamed()
        _state.value
    }

    /**
     * Changes the chat's state and, while it is shown, what the screen sees. [flush] first puts the
     * buffered words into the transcript; a change that leaves the transcript alone does not need it.
     */
    private fun edit(flush: Boolean = true, change: (ChatState) -> ChatState) {
        val origin = workContext.get()
        if (origin == null) editNow(flush, change) else publish(origin.owner) {
            if (publicationEligible(origin)) editNow(flush, change)
        }
    }

    private fun editNow(flush: Boolean = true, change: (ChatState) -> ChatState) = synchronized(lock) {
        if (flush) flushStreamed()
        val asked = _state.value.questions
        val previousId = _state.value.storedId
        _state.value = change(_state.value)
        val assignedId = _state.value.storedId
        if (previousId != assignedId && assignedId != null) workContext.get()?.let { origin ->
            // Bind the retained draft copy to the actual created session in the same synchronous
            // publication, before attachment/network suspension exposes that ID to deletion.
            observationBook.assignStoredSession(assignedId, origin.owner.generation)
            stageObservations()
            persistObservations()
        }
        // The questions outlive the chat being open: opened again, it finds them as they are now.
        if (_state.value.questions !== asked) questionMemory.keep(_state.value.storedId.orEmpty(), _state.value.questions)
        if (visible) published.value = _state.value
    }

    /** Puts the buffered words into the transcript in one pass, as their deltas one by one would have. */
    private fun flushStreamed() {
        if (streamed.isEmpty()) return
        val at = streamedAt
        var items = _state.value.items
        for ((type, text) in streamed) {
            items = reduce(items, GatewayEvent(type, liveId, null, buildJsonObject { put("text", text.toString()) }), keyOf = { "live$localNamespace:${keys.incrementAndGet()}" }, now = { at })
        }
        streamed.clear()
        _state.value = _state.value.copy(items = items)
    }

    /**
     * The screen of this chat came up in an app in front, or went away. Hidden, the chat publishes
     * nothing and tells the read mark nothing; shown again, it publishes its state once, and what it
     * read meanwhile is marked read.
     */
    fun setVisible(shown: Boolean) {
        if (!shown) { synchronized(lock) { visible = false }; return }
        val owner = lastLoadOwner ?: liveOwner ?: initialWork.owner
        val read = publish(owner) {
            synchronized(lock) {
                if (visible) return@synchronized null
                visible = true
                flushStreamed()
                published.value = _state.value
                pendingRead.also { pendingRead = null }
            }
        }
        if (!holds(owner)) synchronized(lock) { visible = true }
        val id = _state.value.storedId
        if (read != null && id != null) publish(read.second) { onRead(id, read.first, read.second) }
    }

    /** Tells the read mark, or keeps it for when the chat is on the screen: a hidden chat is not read. */
    private fun tellRead(id: String, lastActive: Double, owner: WorkOwner) {
        publish(owner) {
            val now = synchronized(lock) {
                if (visible) true else {
                    val pending = pendingRead?.takeIf { holds(it.second) }
                    if (pending == null || pending.second != owner || lastActive > pending.first) pendingRead = lastActive to owner
                    false
                }
            }
            if (now) onRead(id, lastActive, owner)
        }
    }

    @Volatile private var lastLoadOwner: WorkOwner? = null
    @Volatile private var liveOwner: WorkOwner? = null

    @Volatile
    private var liveId: String? = null

    /** The model picked before the first message; the session is created with it. */
    private var draftModel: Pair<String, String>? = null

    /** One join at a time, so a message sent while the draft is being created does not create a second session. */
    private val joining = Mutex()

    /**
     * The connection the live session was joined on; 0 until the first join. A later connection
     * means the card or the turn may have moved while the socket was down, so the session is joined
     * again. A count, not the last `ready`: a chat made while a reconnect is under way gets one
     * replayed ready and would take it for the connection it started on.
     */
    private val joinedGeneration = AtomicInteger(0)

    /** A connection came while a load ran: it is joined once that load is over. */
    private val rejoinPending = java.util.concurrent.atomic.AtomicReference<Work?>(null)
    @Volatile
    internal var beforeReconnectEnqueue: (suspend () -> Unit)? = null
    @Volatile internal var afterReconnectEnqueue: (() -> Unit)? = null
    @Volatile
    internal var beforeCloseWarm: (suspend () -> Unit)? = null
    internal val pendingRejoin: Boolean get() = rejoinPending.get() != null
    internal val completedLoadCycles = java.util.concurrent.atomic.AtomicLong()

    /** Loads asked for while one runs; the newest request wins. */
    private val reloadRequests = Channel<Work>(Channel.CONFLATED)
    /** Actual admitted queue entries; observation only, never a load/rejoin decision. */
    internal val acceptedReloadRequests = AtomicLong()

    /** The answer of `session.create` for a new chat that has not been sent; guarded by [joining]. */
    private var draft: JsonObject? = null

    /** The connection [draft] was created over, by [GatewayChannel.transport]; 0 when that is not known. */
    private var draftOn = 0L

    @Volatile
    private var closed = false
    private val keys = AtomicLong()
    private val localNamespace = java.util.UUID.randomUUID().toString()
    private val observationBook = ObservationBook()
    private var observedHistory: org.evsyukov.hermes.data.remote.HistoryPage? = null
    private var liveRemoteProfile: String? = null
    private data class ObservationWrite(val owner: WorkOwner, val ticket: ObservationTicket)
    private var currentEventRevision = 0L
    @Volatile private var liveObservationTicket = observationRecoveryTicket
    private val observationWrites = java.util.concurrent.ConcurrentHashMap<String, ObservationWrite>()
    private val scheduledObservationRevision = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile internal var submitCompletion: Job? = null
        private set
    internal lateinit var observationRecovery: Job
        private set
    private val jobs = mutableListOf<Job>()

    /** When the last message was sent, for the log of its first reply; 0 once the reply came. */
    @Volatile
    private var sentAt = 0L

    /** The stored history is in the phone's cache, so closing the chat has nothing to warm. */
    @Volatile
    private var warmed = false

    /** Guarded by lock: a real history has been installed, rather than only a local send. */
    private var historyShown = false

    /**
     * A turn was sent and has not started. The server may report the session idle meanwhile: a new
     * session announces its agent once built, and that can happen before the prompt reaches it.
     */
    @Volatile
    private var awaitingStart = false
        set(value) {
            val origin = workContext.get()
            if (origin == null) field = value else publish(origin.owner) { field = value }
        }

    /**
     * The messages the user has sent that have no outcome yet, each with the point at which it last
     * moved. A join answer is as old as the point at which it was asked: a send that entered, went to
     * the server or started after that point is newer than the answer, and an idle answer cannot say
     * it ended. A send still waiting for the server when the answer was asked is newer too. Intents
     * that ended are dropped, so an old failure cannot poison a fresh idle answer.
     */
    private val sends = SendActivityTracker()

    /**
     * The questions and withdrawals that came over the connection the socket is on while the chat has
     * no join over it yet — one on its way, or none begun — in the order they came. Guarded by [lock].
     * None is left out: a withdrawal or a question dropped here could be newer than the answer of the
     * join it waits for, which could then not tell it. A join over that connection takes those of its
     * session once it answers and the others go; a new connection or closing the chat empties it.
     */
    private val early = ArrayList<Heard>()

    /** A question or a withdrawal as [observe] took it, with the place it came in among all it took. */
    private class Heard(val observation: Any, val ordinal: Long, val owner: WorkOwner)

    /**
     * Counts the questions and withdrawals [observe] took, in the order they came: a join reads it when
     * it asks, so it knows which came after.
     */
    private val heardLive = AtomicLong()

    /** When each request was last heard live, by [heardLive]; guarded by [lock]. */
    private val seenLive = HashMap<String, Long>()

    /**
     * The connection, by [GatewayChannel.transport], that the answer of the chat's current join came
     * over; 0 when the chat has no join it can trust. Only while the socket is still on this very
     * connection does what the join confirmed stand: an answer to a question goes over it alone, and a
     * new connection, even one already READY and not yet announced, confirms nothing until a join of
     * its own. Guarded by [lock].
     */
    @Volatile
    private var joinedOn = 0L

    init {
        observationRecovery = launchOwned(initialWork) { restoreObservations(initialWork.owner, initialWork.chat.orEmpty()) }
        jobs += observationRecovery
        jobs += scope.launch { socket.events.collect(::onEvent) }
        jobs += scope.launch { socket.requests.collect(::onRequest) }
        // The connection of the join is lost: nothing it confirmed takes an answer any more. The check
        // reads the socket itself, so a value that arrives late cannot undo a newer join.
        jobs += scope.launch {
            socket.transport.collect {
                synchronized(lock) { if (joinedOn != 0L && socket.transport.value != joinedOn) invalidateJoin() }
            }
        }
        // After a reconnect the old live id may be gone: join the session again. The count of
        // connections says whether this chat has already joined the one under way, also for a
        // collector that starts after the connection came.
        val connectedWhenMade = socket.connections.value
        jobs += scope.launch {
            socket.connections.collect { generation ->
                if (closed || _state.value.storedId == null) return@collect
                if (generation == joinedGeneration.get()) return@collect
                // The gateway counts a connection only after `client.capabilities`, so the count of the
                // connection the chat has already joined can arrive later. It is not one that replaced it.
                if (validJoin() != 0L) {
                    joinedGeneration.updateAndGet { maxOf(it, generation) }
                    return@collect
                }
                // The load that opens a chat joins the connection it finds; only a later one needs it again.
                if (joinedGeneration.get() == 0 && generation <= connectedWhenMade) return@collect
                val owner = (socket as? org.evsyukov.hermes.data.remote.GatewaySocket)?.ownerOn(socket.transport.value) ?: liveOwner ?: lastLoadOwner ?: initialWork.owner
                val origin = captureWork(owner, liveObservationTicket)
                beforeReconnectEnqueue?.invoke()
                // The initial observation can wait while another load joins this transport.
                // Recheck at the actual binding/queue mutation under the same publication order
                // as takeLive; an already settled join must not be invalidated by that old decision.
                publish(origin.owner) {
                    val reload = synchronized(lock) {
                        when {
                            closed -> false
                            validJoin() != 0L -> {
                                joinedGeneration.updateAndGet { maxOf(it, generation) }
                                false
                            }
                            _state.value.loading -> { rejoinPending.set(origin); false }
                            else -> { liveId = null; invalidateJoin(); true }
                        }
                    }
                    if (reload && reloadRequests.trySend(origin).isSuccess) acceptedReloadRequests.incrementAndGet()
                }
                afterReconnectEnqueue?.invoke()
            }
        }
        jobs += scope.launch {
            for (requested in reloadRequests) {
                try { runLoads(requested) } finally { completedLoadCycles.incrementAndGet() }
            }
        }
        if (storedId != null) {
            reload()
        } else {
            // Waits for the socket rather than opening it: the app decides when it connects.
            jobs += scope.launch {
                socket.state.collect {
                    val owner = (socket as? org.evsyukov.hermes.data.remote.GatewaySocket)?.ownerOn(socket.transport.value) ?: initialWork.owner
                    withContext(workContext.asContextElement(captureWork(owner, liveObservationTicket))) { beforeDraftRenewal?.invoke(); renewDraft() }
                }
            }
        }
    }

    @Volatile private var observedcontinuationProducers: java.util.concurrent.CopyOnWriteArrayList<Job>? = null
    internal val continuationProducers: java.util.concurrent.CopyOnWriteArrayList<Job>
        get() = synchronized(this) {
            observedcontinuationProducers ?: java.util.concurrent.CopyOnWriteArrayList<Job>().also { observedcontinuationProducers = it }
        }

    private fun recordContinuation(job: Job) { observedcontinuationProducers?.add(job) }

    fun close() {
        val snapshotOwner = liveOwner ?: lastLoadOwner ?: initialWork.owner
        publish(snapshotOwner) { synchronized(lock) { flushStreamed(); observationBook.refreshItems(_state.value.items); stageObservations(); persistObservations() } }
        closed = true
        jobs.forEach { it.cancel() }
        synchronized(lock) { early.clear() }
        // Keep what the chat shows on the phone: opening it again puts that on the screen at once
        // and refreshes it under the surface, rather than waiting for the server with an empty chat.
        val origin = lastLoadOwner ?: liveOwner
        _state.value.storedId?.let { id -> if (origin != null) recordContinuation(scope.launch {
            beforeCloseWarm?.invoke()
            if (holds(origin)) runCatching { session.chatFor(origin).messages(id) }
        }) }
        val cleanup = draftWork ?: lastWork ?: initialWork
        launchOwned(cleanup, allowClosed = true) { renewDraft() }
    }

    /**
     * Creates the session of a new chat while it is empty, on a ready socket. The draft it replaces
     * belongs to a lost connection, another model or a chat that is closed, so it is closed first.
     */
    @Volatile internal var beforeDraftRenewal: (suspend () -> Unit)? = null
    internal val retainedDraftId: String? get() = draft?.string("session_id")
    internal val boundLiveId: String? get() = liveId

    private suspend fun renewDraft() = withContext(NonCancellable) {
        val origin = operation()
        if (!holds(origin.owner)) return@withContext
        joining.withLock {
            if (!holds(origin.owner)) return@withLock
            val ready = socket.state.value == SocketState.READY
            draft?.let { old ->
                draft = null
                // The server drops a session of a lost connection by itself.
                val oldWork = draftWork
                if (ready && oldWork != null && holds(oldWork.owner) && oldWork.transport.get() == socket.transport.value) old.string("session_id")?.let { live ->
                    runCatching { socket.callOn(oldWork.owner, oldWork.transport.get(), "session.close", buildJsonObject { put("session_id", live) }) }
                }
            }
            if (closed || !ready || liveId != null || _state.value.storedId != null) return@withLock
            draft = try {
                onTransport { create() }.let { (answer, on) -> answer.also { draftOn = on; draftWork = origin } }
            } catch (e: Exception) {
                // The first message creates the session then.
                Log.w(TAG, "Draft session not created", e)
                null
            }
        }
    }

    /**
     * Runs [call] on the connection the socket is on when it starts and gives its answer with that
     * connection, or with 0 when the socket was on another one by the time the answer came: such an
     * answer tells nothing about the connection the socket is on now.
     */
    private suspend fun <T> onTransport(call: suspend () -> T): Pair<T, Long> {
        awaitSocket()
        val before = socket.transport.value
        val answer = call()
        return answer to if (before != 0L && socket.transport.value == before) before else 0L
    }

    private suspend fun create() = rpc(
        "session.create",
        buildJsonObject {
            put("source", "android")
            put("close_on_disconnect", false)
            draftModel?.let { (provider, model) ->
                put("provider", provider)
                put("model", model)
            }
        },
    ) as JsonObject

    /**
     * Reads the stored history, then joins the live session to pick up a turn in progress. A chat
     * opened afresh first shows the history the phone keeps, so it does not wait for the server.
     *
     * The head and the history are on their way together, and the session joins as soon as the chat
     * is known to be active, so a reconnect does not wait for the transcript: a history that is slow
     * or cannot be read takes nothing away from a live chat. The answer of the join is applied once
     * the history is on the screen, so a turn that streams lands on top of it.
     */
    fun reload() = enqueueReload(captureWork())

    private fun enqueueReload(origin: Work) {
        publish(origin.owner) { if (!closed && reloadRequests.trySend(origin).isSuccess) acceptedReloadRequests.incrementAndGet() }
    }

    /**
     * Runs one load at a time. A connection that came while a load ran is joined by another load
     * once the first is over: the load may have sent its resume on the lost connection and failed,
     * and no further connection would tell the chat about it.
     */
    private suspend fun runLoads(requested: Work) {
        var origin: Work? = requested
        while (origin != null) {
            rejoinPending.set(null)
            if (holds(origin.owner)) performLoad(origin)
            val queued = reloadRequests.tryReceive().getOrNull()?.takeIf { holds(it.owner) }
            val rejoin = rejoinPending.getAndSet(null)?.takeIf { holds(it.owner) }
            // A transport may be replaced before its count/collectors arrive. This is a reconnect
            // under the SAME captured owner, never permission sampled from a newer authorization.
            val currentTransport = socket.transport.value
            val real = socket as? org.evsyukov.hermes.data.remote.GatewaySocket
            val replacement = if (holds(origin.owner) && currentTransport != 0L && origin.transport.get() != 0L &&
                currentTransport != origin.transport.get() && validJoin() == 0L &&
                (real == null || real.ownerOn(currentTransport) == origin.owner)) Work(origin.owner, origin.chat, currentTransport, origin.observationTicket) else null
            origin = queued ?: rejoin?.takeIf { socket.connections.value != joinedGeneration.get() || validJoin() == 0L } ?: replacement
        }
    }

    /** Completes after the actual load, including its synchronous read callback, returns. */
    @Volatile
    internal var loadCompletion: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        private set

    private suspend fun performLoad(origin: Work) {
        val completed = kotlinx.coroutines.CompletableDeferred<Unit>()
        loadCompletion = completed
        try {
            withContext(workContext.asContextElement(origin)) { performLoadBody(origin) }
        } finally {
            completed.complete(Unit)
        }
    }

    private suspend fun performLoadBody(origin: Work) {
        val owner = origin.owner
        val id = origin.chat ?: return
        // Taken before the requests go out: the read mark this load delivers belongs to the
        // authorization the load started in, however long the history takes.
        if (!holds(owner)) return
        lastLoadOwner = owner
        restoreObservations(owner, id)
        lastWork = origin
        if (liveOwner != null && liveOwner != owner) forgetLive(owner)
        val chat = session.chatFor(owner)
        publish(owner) { edit { it.copy(loading = true, error = null) } }
        if (current.items.isEmpty()) showCached(id, owner)
        try {
            coroutineScope {
                val head = async { runCatching { chat.get(id) } }
                // A history response must not replace live transcript changes made while it travelled.
                val beforeHistory = synchronized(lock) {
                    flushStreamed()
                    _state.value.items to historyShown
                }
                val history = async { runCatching { chat.history(id) } }
                val summary = head.await().getOrElse { if (it is CancellationException) throw it; null }
                if (!holds(owner)) return@coroutineScope
                summary?.let { read ->
                    publish(owner) { edit { state ->
                        state.copy(
                            title = read.title,
                            // The live session knows its model better than the stored row: after a
                            // pick the row may still carry the previous one until the next turn.
                            model = if (liveId != null && state.model != null) state.model else read.model,
                            usage = read.usage,
                        )
                    } }
                }
                // The session joins while the history is still on its way: the live id is what a
                // message and a card need, and a transcript that is slow or fails must not hold
                // the chat back. The answer is put on the screen below, under the history.
                //
                // The stored detail has no `is_active` (only the list does), so a chat running in
                // the dashboard process is looked up in the gateway's live list: without that the
                // chat would open on its history alone and the tools and answer of the running turn
                // would never arrive.
                val since = heardLive.get()
                val lookup = if (summary?.active == true) null else liveSession(id)
                val live = lookup?.getOrNull()
                // Live nowhere the gateway knows: none of the chat's questions can still wait for an answer.
                if (summary != null && lookup?.isSuccess == true && live == null) closeUnlisted(emptySet(), since)
                val joined = if (summary?.active == true || live != null) {
                    runCatching {
                        joining.withLock {
                            // A join over the connection the socket is on now needs no other; one over a
                            // lost connection, or none, is made again, also while an earlier load runs.
                            if (validJoin() != 0L) {
                                null
                            } else {
                                val generationAtStart = socket.connections.value
                                val joinAnswer = resume(id, owner)
                                val generation = if (generationAtStart == 0) socket.connections.value else generationAtStart
                                bindLoad(joinAnswer)
                                joinAnswer to generation

                            }
                        }
                    }
                } else {
                    null
                }
                val read = history.await()
                (read.exceptionOrNull() as? CancellationException)?.let { throw it }
                if (!holds(owner)) return@coroutineScope
                if (read.isSuccess) {
                    publish(owner) {
                        observedHistory = read.getOrThrow()
                        showHistory(transcriptOf(read.getOrThrow().rows), beforeHistory, joined?.getOrNull()?.first)
                        syncObservations()
                    }
                    // What is on the screen now is read: a mark in the sidebar clears, and a new
                    // answer after this will mark it again.
                    summary?.let { tellRead(id, it.lastActive, owner) }
                } else {
                    publish(owner) { edit { it.copy(loading = false, error = read.exceptionOrNull()?.message) } }
                }
                joined?.getOrNull()?.let { (joinAnswer, generation) -> adopt(joinAnswer, id, starting = false, generation) }
                joined?.exceptionOrNull()?.let { throw it }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            publish(owner) { edit { it.copy(loading = false, error = e.message) } }
        }
    }

    /**
     * Puts [history] on the screen, keeping a message the server has not stored yet: one the user
     * sent while the history was on its way must not vanish from under them. An answer that streams
     * stays live until an authoritative join or terminal event ends it; a join is applied after this.
     */
    private fun showHistory(history: List<ChatItem>, before: Pair<List<ChatItem>, Boolean>, join: JoinAnswer?) {
        edit { state ->
            // A stored read cannot end a live answer merely because no delta arrived during it.
            // A fresh idle join newer than the local send may still restore authoritative history.
            val keepStreaming = state.running && state.items.any { it is ChatItem.Assistant && it.streaming } &&
                (join == null || sends.protects(join.activity) || state.items !== join.boundItems)
            if (before.second && (state.items !== (join?.boundItems ?: before.first) || keepStreaming)) return@edit state.copy(storedHistory = history, loading = false)
            historyShown = true
            // The working reducer retains the legacy folded remote work context. This is a
            // display overlay, never correlation: canonical rows remain separately visible and
            // the unbound on-device observation cannot retire without an actual submit ACK.
            val liveTail = state.items.lastOrNull() as? ChatItem.Assistant
            val storedTail = history.lastOrNull() as? ChatItem.Assistant
            val localPrompt = state.items.any { it is ChatItem.User && it.rowId == null }
            if (!before.second && join?.boundRevision != null && !localPrompt && liveTail != null && storedTail != null && liveTail.key.startsWith("live")) {
                val currentTools = liveTail.parts.filterIsInstance<Part.Tool>().mapTo(HashSet()) { it.run.id }
                val context = storedTail.parts.filter { it !is Part.Text && (it !is Part.Tool || it.run.id !in currentTools) }
                return@edit state.copy(items = history.dropLast(1) + liveTail.copy(parts = context + liveTail.parts), storedHistory = history, loading = false)
            }
            val known = history.mapTo(HashSet()) { it.key }
            // The first history can arrive after a public early send, including its completed reply.
            // Keep that local suffix, not only its user row; later ordinary reloads remain replaceable.
            val localStart = if (!before.second) state.items.indexOfFirst { it is ChatItem.User && it.rowId == null } else -1
            val local = state.items.filterIndexed { index, item ->
                item.key !in known && (item is ChatItem.User && item.rowId == null ||
                    join?.boundRevision != null && item.key.startsWith("live") ||
                    localStart >= 0 && index >= localStart &&
                        (item !is ChatItem.Assistant || !item.streaming || keepStreaming) || keepStreaming && item is ChatItem.Assistant && item.streaming)
            }
            state.copy(items = if (local.isEmpty()) history else history + local, storedHistory = history, loading = false)
        }
    }

    private suspend fun showCached(id: String, owner: WorkOwner) {
        val cached = session.chatFor(owner).cached ?: return
        try {
            val history = transcriptOf(cached.messages(id))
            val summary = runCatching { cached.get(id) }.getOrNull()
            publish(owner) { edit { if (it.items.isEmpty()) { historyShown = true; it.copy(items = history, title = summary?.title.orEmpty(), model = summary?.model, usage = summary?.usage) } else it } }
            // Cached content has no auth-generation provenance; only the admitted live load reports activity.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not on the phone yet, or unreadable: the server's answer fills the chat.
        }
    }

    /** Joins the live session; [starting] a turn, the chat stays running whatever the session was doing. */
    private suspend fun ensureLive(starting: Boolean = false): String {
        val origin = operation()
        if (!holds(origin.owner)) throw IOException("Retired chat origin")
        if (liveOwner != null && liveOwner != origin.owner) forgetLive(origin.owner)
        liveId?.let { if (validJoin() != 0L) return it }
        return joining.withLock {
            if (!holds(origin.owner)) throw IOException("Retired chat origin")
            liveId?.takeIf { liveOwner == origin.owner && validJoin() != 0L } ?: join(starting)
        }
    }

    private suspend fun join(starting: Boolean): String {
        val owner = operation().owner
        val stored = operation().chat
        // The connection the answer comes over: a call made before the socket is up opens it, so the
        // number is read again after the call when there was none.
        val generationAtStart = socket.connections.value
        // The point at which this answer is asked: a send that enters or moves after it is newer.
        val activity = sends.mark()
        val joinAnswer = if (stored == null) {
            val asked = heardLive.get()
            val kept = draft
            val keptWork = draftWork
            val keptOn = draftOn
            draft = null
            draftWork = null
            draftOn = 0L
            // A session made in advance over a connection since lost is not this connection's: a new one is made.
            if (kept != null && keptWork?.owner == owner && keptOn != 0L && keptOn == socket.transport.value) {
                JoinAnswer(kept, asked, keptOn, activity, owner)
            } else {
                if (keptWork != null && holds(keptWork.owner) && keptOn != 0L && keptOn == socket.transport.value) {
                    kept?.string("session_id")?.let { old ->
                        try { socket.callOn(keptWork.owner, keptOn, "session.close", buildJsonObject { put("session_id", old) }) }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { /* A rejected draft is discarded locally if cleanup fails. */ }
                    }
                }
                onTransport { create() }.let { (answer, on) -> JoinAnswer(answer, asked, on, activity, owner) }
            }
        } else {
            resume(stored, owner)
        }
        val generation = if (generationAtStart == 0) socket.connections.value else generationAtStart
        return adopt(joinAnswer, stored, starting, generation)
    }

    /**
     * The live session of stored chat [id] from the gateway's process-local list, null when this
     * gateway does not host it, or a failure when the list could not be read. The socket is given a
     * moment to come up; a chat opened offline waits for its history, not for the live list.
     */
    private suspend fun liveSession(id: String): Result<JsonObject?> {
        val ready = socket.state.value == SocketState.READY || withTimeoutOrNull(3_000) { awaitSocket(); true } == true
        if (!ready) return Result.failure(IOException("no connection"))
        return runCatching {
            val answer = rpc("session.active_list", buildJsonObject { put("current_session_id", id) }, timeoutMillis = 10_000) as? JsonObject
            (answer?.get("sessions") as? JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull {
                it.string("session_key") == id || it.string("id") == id
            }
        }
    }

    /**
     * Asks the server to join the session of [stored]; [adopt] puts the answer on the screen. Gives the
     * answer, the connection it came over and how many requests the chat had heard live when it asked:
     * the open requests of the answer are as of a moment after that, and one heard later is newer.
     */
    private suspend fun resume(stored: String, owner: WorkOwner): JoinAnswer {
        val asked = heardLive.get()
        // A send from here on is newer than this answer, whatever the server answers. One that
        // entered before this point but had not gone to the server yet is newer too.
        val activity = sends.mark()
        val (answer, transport) = onTransport {
            rpc(
                "session.resume",
                buildJsonObject {
                    put("session_id", stored)
                    put("close_on_disconnect", false)
                    put("omit_messages", true)
                    put("inline_images", false)
                },
                timeoutMillis = 120_000,
            ) as JsonObject
        }
        return JoinAnswer(answer, asked, transport, activity, owner)
    }

    /**
     * The join answered with live session [live] over connection [transport], 0 when that connection
     * is already lost: what came for the session meanwhile is applied now, in the order it came, and
     * what came for any other session is dropped.
     */
    @Volatile internal var afterLiveBound: ((Long) -> Unit)? = null

    private fun takeLive(live: String, transport: Long, owner: WorkOwner) = publish(owner) { synchronized(lock) {
        liveOwner = owner
        liveId = live
        // Another join, or one over a lost connection: what an earlier join confirmed is not confirmed by this one.
        if (joinedOn != transport) {
            joinedOn = transport
            unconfirm()
        }
        afterLiveBound?.invoke(transport)
        val heard = ArrayList(early)
        early.clear()
        // In the order they came, each with the place it came in, not the moment of this replay.
        heard.filter { it.owner == owner }.forEach { entry ->
            when (val observation = entry.observation) {
                is GatewayEvent -> takeWithdrawal(observation, entry.ordinal)
                is ServerRequest -> takeQuestion(observation, socket.connections.value, entry.ordinal)
            }
        }
    } }

    /**
     * Takes a question or a withdrawal as it comes, or from [early] once a join answered: the only way
     * either changes the chat, under [lock] from the check to the change. One that came over no known
     * connection, or over one the socket is no longer on, is dropped: it neither spares a question from
     * the reconciliation of a join nor ends one. One that came over the connection the socket is on
     * now is [applied][apply] when the chat's join is over that same connection and [sessionId] is the
     * chat's live session; while the chat has no such join it waits in [early] for one.
     *
     * Each one taken gets its place among all taken, by [heardLive], once, when it first comes: a replay
     * from [early] passes that [ordinal] on rather than counting it again, so a join tells by it what
     * came before it asked. A request a join [listed] is not heard live and gets no place.
     */
    private fun observe(
        observation: Any,
        transport: Long,
        sessionId: String?,
        ordinal: Long = 0L,
        listed: Boolean = false,
        apply: (ordinal: Long) -> Unit,
    ) = synchronized(lock) {
        if (transport == 0L || transport != socket.transport.value) return@synchronized
        val heard = when {
            listed -> 0L
            ordinal != 0L -> ordinal
            else -> heardLive.incrementAndGet()
        }
        if (transport != joinedOn || liveId == null) {
            // A join lists what it answers with itself; only what came live waits for one.
            if (listed) return@synchronized
            // What came over an earlier connection can no longer be attributed: only this one's are kept.
            early.removeAll { originOf(it.observation) != transport }
            early += Heard(observation, heard, operation().owner)
            return@synchronized
        }
        if (sessionId == liveId) apply(heard)
    }

    private fun takeWithdrawal(event: GatewayEvent, ordinal: Long = 0L) =
        observe(event, event.transport, event.sessionId, ordinal) { withdraw(event) }

    private fun takeQuestion(request: ServerRequest, generation: Int, ordinal: Long = 0L, listed: Boolean = false) =
        observe(request, request.transport, request.params.string("session_id"), ordinal, listed) { heard -> showQuestion(request, generation, listed, heard) }

    private fun originOf(observation: Any): Long = when (observation) {
        is GatewayEvent -> observation.transport
        is ServerRequest -> observation.transport
        else -> 0L
    }

    /**
     * The live session is no longer known, after a lost connection or a session the server dropped:
     * no question takes an answer until the next join confirms it is still open.
     */
    private fun forgetLive(owner: WorkOwner) = publish(owner) { synchronized(lock) {
        liveId = null
        invalidateJoin()
    } }

    /** The join can no longer be trusted: its connection is lost. Guarded by [lock]. */
    private fun invalidateJoin() {
        joinedOn = 0L
        unconfirm()
    }

    private fun unconfirm() = edit(flush = false) { state ->
        if (state.questions.none { it.confirmed }) state else state.copy(questions = state.questions.map { it.copy(confirmed = false) })
    }

    /** The connection of the chat's current join while the socket is still on it, or 0. */
    private fun validJoin(): Long = synchronized(lock) {
        joinedOn.takeIf { it != 0L && liveId != null && socket.transport.value == it } ?: 0L
    }

    /**
     * The answer of a join, the connection it came over, the count of [heardLive] when it was asked,
     * and the point of the local sends then: a send that entered or moved after it is newer than
     * this answer.
     */
    private class JoinAnswer(
        val answer: JsonObject,
        val asked: Long,
        val transport: Long,
        val activity: Long,
        val owner: WorkOwner,
        var boundItems: List<ChatItem>? = null,
        var boundRevision: Long? = null,
    )

    /** Seed the authoritative prefix before current-transport deltas can be consumed. */
    private fun bindLoad(answer: JoinAnswer) = publish(answer.owner) { synchronized(lock) {
        if (!sends.protects(answer.activity)) {
            flushStreamed()
            observationBook.refreshItems(_state.value.items)
            observationBook.sealActive()
            syncObservations()
            val running = answer.answer.runningFlag() ?: (answer.answer["info"] as? JsonObject)?.runningFlag() ?: false
            val prefix = (answer.answer["inflight"] as? JsonObject)?.string("assistant").orEmpty()
            edit { state ->
                val base = if (state.items.lastOrNull() is ChatItem.Assistant && (state.items.last() as ChatItem.Assistant).streaming) state.items.dropLast(1) else state.items
                state.copy(currentObservationId = null, running = running, model = (answer.answer["info"] as? JsonObject)?.string("model") ?: state.model, items = if (running) base + ChatItem.Assistant("live$localNamespace:${keys.incrementAndGet()}", listOf(Part.Text(prefix)), true, time = nowSeconds()) else base)
            }
        }
        liveRemoteProfile = (answer.answer["info"] as? JsonObject)?.string("profile_name")
        liveObservationTicket = operation().observationTicket ?: observationRecoveryTicket
        if (_state.value.running && observationBook.active == null) {
            val tail = _state.value.items.lastOrNull() as? ChatItem.Assistant
            if (tail != null) {
                val id = observationBook.continuation(answer.owner.profileId, session.profile.url.toString(), _state.value.storedId.orEmpty(), answer.owner.generation, tail)
                observationWrites[id] = ObservationWrite(answer.owner, liveObservationTicket)
                edit { it.copy(currentObservationId = id, observations = observationBook.snapshot()) }
                stageObservations(); persistObservations()
            }
        }
        answer.answer.string("session_id")?.let { takeLive(it, answer.transport, answer.owner) }
        answer.boundItems = _state.value.items
        answer.boundRevision = currentEventRevision
    } }

    /**
     * Applies the answer of a join: the live id, the turn that runs, its card and the questions the
     * server holds. Separately from [resume] so a chat that reconnects can ask at once and apply
     * once its history is on the screen, the turn streaming on top of it.
     */
    private fun adopt(joinAnswer: JoinAnswer, stored: String?, starting: Boolean, generation: Int): String = publish(joinAnswer.owner) {
        val result = joinAnswer.answer
        liveRemoteProfile = (result["info"] as? JsonObject)?.string("profile_name")
        val live = result.string("session_id") ?: error("No session_id in the answer")
        // What the answer says of the requests holds on the connection it came over, and confirms
        // nothing once the socket is on another one: the join over that one brings its own answer.
        val current = joinAnswer.transport != 0L && socket.transport.value == joinAnswer.transport
        takeLive(live, if (current) joinAnswer.transport else 0L, joinAnswer.owner)
        // A count read now may already include a connection that took the socket while this answer was
        // on its way. It is the chat's only if the socket is still on the connection the answer came
        // over, which is read after the count: the socket may change between the two reads.
        val counted = if (current) socket.connections.value else generation
        val stillCurrent = joinAnswer.transport != 0L && socket.transport.value == joinAnswer.transport
        // The connection the answer came over is the one the chat is joined to. Its count may already
        // have caught up while the answer was on its way; it is never lower than when the join asked.
        joinedGeneration.updateAndGet { maxOf(it, generation) }
        if (current && stillCurrent) joinedGeneration.updateAndGet { maxOf(it, counted) }
        // The answer came over `generation`; a connection that came meanwhile may have moved the
        // card or the turn, so that one is joined as well. A count that caught up on the answer's own
        // connection is not such a connection.
        if (!stillCurrent || (!current && socket.connections.value != generation)) rejoinPending.set(Work(joinAnswer.owner, stored, socket.transport.value, operation().observationTicket))
        val storedId = result.string("stored_session_id") ?: stored
        val info = result["info"] as? JsonObject
        val serverRunning = result.runningFlag() ?: info?.runningFlag() ?: false
        // A join may have been asked before the user sent a message, or after the message entered
        // but before it went to the server: its idle answer is older than that send and cannot say
        // the send ended. A join asked after the send went out is newer, so its answer stands,
        // including a turn that finished while the chat was away.
        val sentAfterAsk = sends.protects(joinAnswer.activity)
        val bound = joinAnswer.boundRevision != null
        val running = if (bound) _state.value.running else starting || serverRunning || (!starting && sentAfterAsk)
        if (!bound && !starting && !serverRunning && !sentAfterAsk) {
            // The server is idle and this answer is newer than every local send: any turn the chat
            // knew of has ended, including one whose outcome was lost while disconnected.
            sends.discard(joinAnswer.activity)
        }
        // A turn under way when the chat opens goes on in its answer, with what streamed while the chat was away.
        val resumed = !bound && serverRunning && !starting
        val streamed = (result["inflight"] as? JsonObject)?.string("assistant").orEmpty()
        // A completed copy is immutable. A later authorized resume observes a new continuation,
        // without asserting that it is the same persisted turn as that copy or the canonical row.
        val archivedResume = resumed && observationBook.snapshot().any { it.id == observationBook.active && it.completed }
        if (archivedResume) observationBook.sealActive()
        edit {
            it.copy(
                storedId = storedId ?: it.storedId,
                running = running,
                model = if (bound) it.model else info?.string("model") ?: it.model,
                items = if (resumed) {
                    val opened = resumeTurn(it.items, streamed, keyOf = { "live$localNamespace:${keys.incrementAndGet()}" })
                    val tail = opened.lastOrNull() as? ChatItem.Assistant
                    if (archivedResume && tail != null) opened.dropLast(1) + tail.copy(key = "live$localNamespace:${keys.incrementAndGet()}") else opened
                } else it.items,
            )
        }
        if (!bound) {
            liveObservationTicket = operation().observationTicket ?: observationRecoveryTicket
            synchronized(lock) {
                if (resumed && observationBook.active == null) {
                    val tail = _state.value.items.lastOrNull() as? ChatItem.Assistant
                    if (tail != null) {
                        val id = observationBook.continuation(joinAnswer.owner.profileId, session.profile.url.toString(), _state.value.storedId.orEmpty(), joinAnswer.owner.generation, tail)
                        observationWrites[id] = ObservationWrite(joinAnswer.owner, liveObservationTicket)
                        edit { it.copy(currentObservationId = id, observations = observationBook.snapshot()) }
                        stageObservations(); persistObservations()
                    }
                }
            }
        }
        if (stored == null && storedId != null) onSessionCreated(storedId)
        if (storedId != null) onLive(live, storedId, _state.value.title)
        // A card left open while the chat was away comes back with the server's deadline; one the
        // server no longer has open ended while the socket was down and takes no answers.
        val pending = connectionOf(result["pending_connection"] as? JsonObject)
        edit { state -> state.copy(connections = state.connections.map { if (it.settled || it.opId == pending?.opId) it else it.copy(settled = true) }) }
        pending?.let(::showConnection)
        if (current && stillCurrent) {
            val listed = HashSet<String>()
            (result["open_requests"] as? JsonArray)?.forEach { request ->
                val obj = request as? JsonObject ?: return@forEach
                val id = obj.string("id") ?: return@forEach
                listed += id
                // Listed by this join's answer: it came over the connection of that answer.
                onRequest(ServerRequest(id, obj.string("method").orEmpty(), obj["params"] as? JsonObject ?: JsonObject(emptyMap()), joinAnswer.transport), generation, listed = true)
            }
            closeUnlisted(listed, joinAnswer.asked)
        }
        live
    } ?: throw IOException("Retired chat operation")

    /** Runs [block] on the live session; a session the server dropped is joined again once. */
    @Volatile internal var beforeLiveRecovery: (suspend () -> Unit)? = null

    private suspend fun <T> withLive(starting: Boolean = false, block: suspend (String) -> T): T {
        val origin = operation()
        val live = ensureLive(starting)
        val on = origin.transport.get()
        return try { block(live) } catch (error: RpcException) {
            if (!error.sessionGone()) throw error
            beforeLiveRecovery?.invoke()
            val forgotten = publish(origin.owner) {
                synchronized(lock) {
                    if (liveOwner != origin.owner || liveId != live || joinedOn != on) false
                    else { liveId = null; invalidateJoin(); true }
                }
            } == true
            if (!forgotten) throw IOException("Retired live binding", error)
            block(ensureLive(starting))
        }
    }

    fun send(text: String, attachments: List<LocalAttachment>) {
        var start: (() -> Unit)? = null
        withRoot(publicAction = "send") { origin ->
            if (text.isBlank() && attachments.isEmpty()) return@withRoot
            val sendId = sends.begin()
            val key = "local$localNamespace:${keys.incrementAndGet()}"
            val shown = attachments.map { Attachment(it.name, it.name, it.image) }
            edit { it.copy(items = it.items + ChatItem.User(key, text, shown, delivery = Delivery.SENDING, time = nowSeconds()), running = true, error = null) }
            val localUser = _state.value.items.last() as ChatItem.User
            observationBook.active?.let { id -> observationWrites[id]?.let { if (!session.observations.valid(it.ticket, _state.value.storedId.orEmpty())) observationBook.sealActive() } }
            val observationId = observationBook.begin(origin.owner.profileId, session.profile.url.toString(), _state.value.storedId.orEmpty(), origin.owner.generation, localUser,
                attachments.map { ObservationFile(it.name, it.mime, Base64.getEncoder().encodeToString(it.bytes), key) })
            observationWrites.getOrPut(observationId) { ObservationWrite(origin.owner, checkNotNull(origin.observationTicket)) }
            stageObservations()
            edit { it.copy(observations = observationBook.snapshot(), currentObservationId = observationId) }
            awaitingStart = true
            // The root's synchronous publication is complete before a service/caller callback runs.
            // In particular, no short authority monitor is held while it waits for a real join.
            start = submit@{
                if (closed || !publicationEligible(origin)) { sends.finish(sendId); return@submit }
                val hold = onTurnStart()
                val started = System.currentTimeMillis()
                publish(origin.owner) { if (publicationEligible(origin)) sentAt = started }
                val submit = launchOwned(origin, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    // The prompt may have reached the server even when its answer does not come back: an
                    // unknown outcome is reported, never retried on its own, and the message stays visible.
                    var submitted = false
                    try {
                        synchronized(lock) { persistObservations() }
                        // A message sent before the socket is up says so instead of waiting in silence.
                        if (socket.state.value != SocketState.READY) edit { it.copy(connecting = true) }
                        try {
                            awaitSocket()
                        } finally {
                            edit { it.copy(connecting = false) }
                        }
                        val socketMillis = System.currentTimeMillis() - started
                        withLive(starting = true) { live ->
                            val sessionMillis = System.currentTimeMillis() - started
                            hold.live(live)
                            val refs = attachments.mapNotNull { attach(live, it) }
                            // The words first: the server titles the chat by the start of its first message.
                            val prompt = buildString {
                                append(text.ifBlank { if (attachments.all { it.image }) strings.get(R.string.chat_describe_image) else strings.get(R.string.chat_look_files) })
                                if (refs.isNotEmpty()) append("\n").append(refs.joinToString("\n"))
                            }
                            sends.submitted(sendId)
                            submitted = true
                            publish(origin.owner) { synchronized(lock) { observationBook.moveSession(observationId, _state.value.storedId.orEmpty()); stageObservations() } }
                            synchronized(lock) { persistObservations() }
                            val proofOrigin = ReceiptOrigin(origin.owner.profileId, origin.owner.generation, live, operation().transport.get(), liveRemoteProfile)
                            val ack = rpc("prompt.submit", buildJsonObject { put("session_id", live); put("text", prompt) })
                            publish(origin.owner) { synchronized(lock) { observationBook.bind(observationId, key, proofOrigin, prompt, ack); syncObservations() } }
                            markDelivery(key, Delivery.SENT)
                            hold.submitted()
                            Log.i(
                                TIMING_TAG,
                                "Sent: socket ready after $socketMillis ms, session after $sessionMillis ms, " +
                                    "prompt accepted after ${System.currentTimeMillis() - started} ms",
                            )
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException && e !is TimeoutCancellationException) throw e
                        hold.release()
                        awaitingStart = false
                        Log.w(TAG, "Send failed", e)
                        val delivery = deliveryFor(e, submitted)
                        val error = if (delivery == Delivery.UNKNOWN) strings.get(R.string.error_send_unknown) else e.message ?: strings.get(R.string.error_send_failed)
                        failDelivery(sendId, key, delivery, error)
                publish(origin.owner) { synchronized(lock) { observationBook.failed(observationId, _state.value.items, error); syncObservations() } }
                    }
                }
                submitCompletion = submit
                submit.invokeOnCompletion { cause ->
                    if (cause != null) {
                        hold.release()
                        sends.finish(sendId)
                    }
                }
                submit.start()
            }
        }
        start?.invoke()
    }


    /**
     * Whether the server may have taken the prompt: an answer that never came (a timeout or a lost
     * connection) after the submit went out is unknown; a refusal or a failure before it is sent.
     */
    private fun deliveryFor(e: Exception, submitted: Boolean): Delivery =
        if (!submitted || e is RpcException) Delivery.FAILED else if (e is IOException || e is TimeoutCancellationException) Delivery.UNKNOWN else Delivery.FAILED

    private fun markDelivery(key: String, delivery: Delivery) {
        edit { state -> state.copy(items = state.items.map { item -> if (item is ChatItem.User && item.key == key) item.copy(delivery = delivery) else item }) }
        publish(operation().owner) { synchronized(lock) { observationBook.delivery(key, delivery); syncObservations() } }
    }

    /** The submission did not go through: the message keeps its place with the outcome, in one update. */
    private fun failDelivery(sendId: Long, key: String, delivery: Delivery, error: String) {
        sends.finish(sendId)
        edit { state ->
            state.copy(
                items = state.items.map { item -> if (item is ChatItem.User && item.key == key) item.copy(delivery = delivery) else item },
                running = false,
                error = error,
            )
        }
    }

    /** Pictures go to the vision queue of the next turn; other files land in the workspace and are named in the prompt. */
    private suspend fun attach(live: String, file: LocalAttachment): String? {
        val base64 = Base64.getEncoder().encodeToString(file.bytes)
        return if (file.image) {
            rpc(
                "image.attach_bytes",
                buildJsonObject {
                    put("session_id", live)
                    put("content_base64", base64)
                    put("filename", file.name)
                },
                timeoutMillis = 120_000,
            )
            null
        } else {
            val result = rpc(
                "file.attach",
                buildJsonObject {
                    put("session_id", live)
                    put("data_url", "data:${file.mime ?: "application/octet-stream"};base64,$base64")
                    put("name", file.name)
                },
                timeoutMillis = 120_000,
            ) as? JsonObject
            result?.string("ref_text")
        }
    }

    fun stop() = withRoot { origin ->
        val live = liveId ?: return@withRoot
        launchOwned(origin) {
            try {
                rpc("session.interrupt", buildJsonObject { put("session_id", live) })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                edit { it.copy(error = e.message) }
            }
            awaitingStart = false
            sends.stopAll()
            edit { it.copy(items = closeOpen(it.items, interrupted = true), running = false) }
        }
    }

    /** Asks for the last answer again: the server drops the last turn and the same text goes again. */
    fun regenerate() = rewriteLast(null)

    /** Replaces the last user message with [text] and answers it anew. */
    fun editLast(text: String) = rewriteLast(text)

    private fun rewriteLast(replacement: String?) = withRoot(publicAction = "rewrite") { origin ->
        val lastUser = current.items.lastOrNull { it is ChatItem.User } as? ChatItem.User ?: return@withRoot
        val sendId = sends.begin()
        val again = lastUser.rewritten(replacement, "local$localNamespace:${keys.incrementAndGet()}").copy(delivery = Delivery.SENDING, time = nowSeconds())
        val text = again.sent
        val oldFiles = observationBook.snapshot().flatMap { it.files }.filter { it.userKey == lastUser.key }.map { it.copy(userKey = again.key) }
        observationBook.active?.let { id -> observationWrites[id]?.let { if (!session.observations.valid(it.ticket, _state.value.storedId.orEmpty())) observationBook.sealActive() } }
            val observationId = observationBook.begin(origin.owner.profileId, session.profile.url.toString(), _state.value.storedId.orEmpty(), origin.owner.generation, again, oldFiles)
        observationWrites[observationId] = ObservationWrite(origin.owner, checkNotNull(origin.observationTicket))
        stageObservations()
        edit { it.copy(observations = observationBook.snapshot(), currentObservationId = observationId) }
        var hold: TurnHold? = null
        launchOwned(origin) {
            var submitted = false
            try {
                synchronized(lock) { persistObservations() }
                val live = ensureLive()
                if (_state.value.running) rpc("session.interrupt", buildJsonObject { put("session_id", live) })
                rpc("session.undo", buildJsonObject { put("session_id", live); put("intent", "retry") })
                val index = current.items.indexOfLast { it is ChatItem.User }
                val kept = if (index >= 0) current.items.subList(0, index) else current.items
                edit { it.copy(items = kept + again, running = true, error = null) }
                awaitingStart = true
                hold = onTurnStart().also { it.live(live) }
                sends.submitted(sendId)
                submitted = true
                val proofOrigin = ReceiptOrigin(origin.owner.profileId, origin.owner.generation, live, operation().transport.get(), liveRemoteProfile)
                val ack = rpc("prompt.submit", buildJsonObject { put("session_id", live); put("text", text) })
                publish(origin.owner) { synchronized(lock) { observationBook.bind(observationId, again.key, proofOrigin, text, ack); syncObservations() } }
                markDelivery(again.key, Delivery.SENT)
                hold?.submitted()
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                hold?.release()
                awaitingStart = false
                val delivery = deliveryFor(e, submitted)
                val error = if (delivery == Delivery.UNKNOWN) strings.get(R.string.error_send_unknown) else e.message ?: strings.get(R.string.error_failed)
                failDelivery(sendId, again.key, delivery, error)
                publish(origin.owner) { synchronized(lock) { observationBook.failed(observationId, _state.value.items, error); syncObservations() } }
            }
        }.invokeOnCompletion { cause ->
            if (cause != null) {
                hold?.release()
                sends.finish(sendId)
            }
        }
    }

    fun rename(title: String) = withRoot { origin ->
        val id = _state.value.storedId ?: return@withRoot
        recordContinuation(launchOwned(origin) {
            try {
                // A rename touches no pin.
                ChatMutations(apiForWork(), socket, liveOf = { stored -> liveId.takeIf { stored == id } }, pinLocally = { _, _ -> }, owner = origin.owner, wanted = { holds(origin.owner) && !closed }, transport = origin.transport.get()).rename(id, title)
                edit { it.copy(title = title) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                edit { it.copy(error = e.message) }
            }
        })
    }

    fun dismissError() = edit { it.copy(error = null) }

    /** Switches the model of this chat; a running turn finishes on the old one. */
    fun chooseModel(provider: String, model: String, confirmed: Boolean = false) = withRoot { origin ->
        edit { it.copy(modelConfirm = null) }
        if (liveId == null && _state.value.storedId == null) {
            draftModel = provider to model
            edit { it.copy(model = model, modelPending = false) }
            // The draft session was built for the model before; a new one is created with the pick.
            jobs += launchOwned(origin) { renewDraft() }
            return@withRoot
        }
        launchOwned(origin) {
            try {
                val result = withLive { live ->
                    rpc(
                        "config.set",
                        buildJsonObject {
                            put("session_id", live)
                            put("key", "model")
                            put("value", "$model --provider $provider")
                            if (confirmed) put("confirm_expensive_model", true)
                        },
                    ) as? JsonObject
                }
                if (result?.bool("confirm_required") == true) {
                    edit { it.copy(modelConfirm = Triple(provider, model, result.string("confirm_message") ?: strings.get(R.string.chat_model_expensive))) }
                } else {
                    // A turn already runs: the server switches at the next turn, so the pick is shown
                    // with that caveat instead of claiming the running turn already uses it.
                    edit {
                        it.copy(
                            model = model,
                            modelPending = result?.bool("deferred") == true,
                            error = result?.string("warning")?.takeIf { w -> w.isNotBlank() },
                        )
                    }
                }
            } catch (e: Exception) {
                edit { it.copy(error = e.message) }
            }
        }
    }

    fun dismissModelConfirm() = edit { it.copy(modelConfirm = null) }

    fun answerApproval(choice: String) = answer { buildJsonObject { put("choice", choice) } }

    /**
     * Answers question [id], once and only while its card is pending: a second tap, or a card the
     * server closed meanwhile, sends nothing. A question without words is sent as skipped.
     */
    fun answerQuestion(id: String, answers: Map<String, String?>) = resolveQuestion(id) { card ->
        val given = card.questions.associate { q -> q.id to answers[q.id]?.trim()?.takeIf { it.isNotEmpty() } }
        card.copy(state = QuestionState.SUBMITTED, answers = given) to buildJsonObject {
            put("answers", JsonObject(given.mapValues { (_, v) -> if (v == null) JsonNull else JsonPrimitive(v) }))
            put("outcome", "submitted")
        }
    }

    /** The answers written on the card of [card] and not sent yet. */
    fun answersOf(card: QuestionCard) = questionMemory.answers(_state.value.storedId.orEmpty(), card.id, card.prefilled)

    /** Tells the agent the user will not answer question [id]; only the button of its card says so. */
    fun cancelQuestion(id: String) = resolveQuestion(id) { card ->
        card.copy(state = QuestionState.CANCELLED) to buildJsonObject { put("outcome", "cancelled") }
    }

    /**
     * Settles a pending question and sends the one answer [settle] gives for it. The answer is a frame
     * that gets no reply, so without a connection it would be lost unseen: then nothing is sent, the
     * card stays open and the chat says why.
     */
    private fun resolveQuestion(id: String, settle: (QuestionCard) -> Pair<QuestionCard, JsonElement>) = withRoot(liveOwner ?: lastLoadOwner ?: initialWork.owner) { origin ->
        synchronized(lock) {
            // A closed chat answers nothing, and a card this chat's join has not confirmed may be one the server ended.
            if (closed) return@withRoot
            val card = _state.value.questions.firstOrNull { it.id == id && it.state == QuestionState.PENDING } ?: return@withRoot
            val chat = _state.value.storedId.orEmpty()
            if (questionMemory.ended(chat, id) != null) return@withRoot
            if (socket.state.value != SocketState.READY) {
                edit(flush = false) { it.copy(error = strings.get(R.string.chat_question_offline)) }
                return@withRoot
            }
            // Only over the connection of the join that confirmed it, and only while the socket is still on it.
            val on = validJoin()
            if (!card.confirmed || on == 0L) return@withRoot
            val (settled, result) = settle(card)
            if (!socket.respondOwned(origin.owner, on, id, result)) return@withRoot
            questionMemory.heard(chat, id, socket.connections.value)
            questionMemory.answered(chat, id, on)
            edit(flush = false) { state -> state.copy(questions = state.questions.map { if (it.id == id) settled else it }) }
        }
    }

    /**
     * Shows a `clarify` request as a card under the answer whose tool asked it, and confirms it takes
     * an answer: it is open on the session this chat has joined, live or [listed] by the join. The
     * request comes again with every join while it waits: a card the chat has stays one card, with
     * what the user wrote on it. A request the server ended stays ended, whatever an older answer
     * still lists: its card shows how it ended, also when the chat had not shown it yet. A card the
     * user settled opens again only when the server still holds the request over a connection later
     * than the one its answer went over, which lost the answer. [ordinal] is the place a live request
     * came in among all [observe] took. The connection, not a count of connections, tells which join
     * came later: another connection can carry the same count.
     */
    private fun showQuestion(request: ServerRequest, generation: Int, listed: Boolean, ordinal: Long) = edit(flush = false) { state ->
        val chat = state.storedId.orEmpty()
        if (!listed) seenLive[request.id] = maxOf(seenLive[request.id] ?: 0L, ordinal)
        val known = state.questions.firstOrNull { it.id == request.id }
        val heard = questionMemory.heardOn(chat, request.id)
        val answeredOn = questionMemory.answeredOn(chat, request.id)
        val ended = questionMemory.ended(chat, request.id)
        fun replaced(card: QuestionCard) = state.copy(questions = state.questions.map { if (it.id == request.id) card else it })
        // Confirmed only by a join the chat can still trust: one over the connection the socket is on now.
        val valid = validJoin() != 0L
        when {
            known == null -> {
                if (ended == null) questionMemory.heard(chat, request.id, generation)
                val card = questionCardOf(request, lastTool(state.items), time = nowSeconds())
                state.copy(questions = state.questions + if (ended == null) card.copy(confirmed = valid) else card.copy(state = ended))
            }
            ended != null -> state
            known.state == QuestionState.PENDING -> {
                questionMemory.heard(chat, request.id, maxOf(heard ?: 0, generation))
                if (known.confirmed == valid) state else replaced(known.copy(confirmed = valid))
            }
            answeredOn != null && answeredOn < request.transport -> {
                questionMemory.heard(chat, request.id, generation)
                val given = known.answers.mapNotNull { (qid, answer) -> answer?.let { qid to it } }.toMap()
                replaced(known.copy(state = QuestionState.PENDING, answers = emptyMap(), prefilled = known.prefilled + given, confirmed = valid))
            }
            else -> state
        }
    }

    /**
     * Closes the pending questions that a join does not [listed] as open, unless heard live after the
     * join asked: the server no longer waits for them, and they take no answer. [asked] is the count
     * of [heardLive] when the join asked.
     */
    private fun closeUnlisted(listed: Set<String>, asked: Long) = edit(flush = false) { state ->
        val chat = state.storedId.orEmpty()
        val gone = state.questions.filter { it.state == QuestionState.PENDING && it.id !in listed && (seenLive[it.id] ?: 0) <= asked }
        gone.forEach { questionMemory.end(chat, it.id, QuestionState.CLOSED) }
        if (gone.isEmpty()) {
            state
        } else {
            state.copy(questions = state.questions.map { if (it in gone) it.copy(state = QuestionState.CLOSED, confirmed = false) else it })
        }
    }

    fun answerSecret(value: String?) = withRoot(liveOwner ?: lastLoadOwner ?: initialWork.owner) { origin ->
        val prompt = _state.value.prompt ?: return@withRoot
        if (value == null) {
            respond(origin, prompt.request.id, null, error = "cancelled")
            edit { it.copy(prompt = null) }
        } else {
            answer { buildJsonObject { put("value", value) } }
        }
    }

    fun cancelPrompt() = withRoot(liveOwner ?: lastLoadOwner ?: initialWork.owner) { origin ->
        val prompt = _state.value.prompt ?: return@withRoot
        when (prompt) {
            is Prompt.Approval -> answerApproval("deny")
            is Prompt.Secret -> answerSecret(null)
        }
    }

    /** A newer snapshot of a known operation replaces it; a new operation joins the chat. */
    private fun showConnection(connection: ConnectionOp) = edit(flush = false) { state ->
        val known = state.connections.indexOfFirst { it.opId == connection.opId }
        when {
            known < 0 -> state.copy(connections = state.connections + connection)
            connection.seq > state.connections[known].seq -> state.copy(connections = state.connections.toMutableList().also { it[known] = connection })
            else -> state
        }
    }

    private fun owner(live: String) = buildJsonObject {
        put("type", "session")
        put("session_id", live)
    }

    /** Approves or skips rows of the card; the server answers with `connection.update`. */
    fun answerConnection(opId: String, answers: List<TargetAnswer>) = respondConnection(opId) {
        put(
            "targets",
            JsonArray(
                answers.map { answer ->
                    buildJsonObject {
                        put("name", answer.name)
                        put("status", if (answer.approved) "approved" else "skipped")
                        if (answer.env.isNotEmpty()) put("env", JsonObject(answer.env.mapValues { (_, v) -> JsonPrimitive(v) }))
                    }
                },
            ),
        )
    }

    /** Ends the operation now with whatever is not resolved: the agent goes on without it. */
    fun continueConnection(opId: String) = respondConnection(opId) { put("settled_by", "continue") }

    private fun respondConnection(opId: String, result: JsonObjectBuilder.() -> Unit) = withRoot(liveOwner ?: lastLoadOwner ?: initialWork.owner) { origin ->
        val live = liveId ?: return@withRoot
        if (_state.value.connections.none { it.opId == opId && !it.settled }) return@withRoot
        launchOwned(origin) {
            try {
                rpc(
                    "connection.respond",
                    buildJsonObject {
                        put("owner", owner(live))
                        put("op_id", opId)
                        put("result", buildJsonObject(result))
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                edit { it.copy(error = e.message ?: strings.get(R.string.error_failed)) }
            }
        }
    }

    /** A new link for a row whose sign-in failed or expired; the row gets it through `connection.update`. */
    fun retryConnection(opId: String, name: String) = withRoot(liveOwner ?: lastLoadOwner ?: initialWork.owner) { origin ->
        val live = liveId ?: return@withRoot
        if (_state.value.connections.none { it.opId == opId && !it.settled }) return@withRoot
        launchOwned(origin) {
            try {
                rpc(
                    "connectors.connect",
                    buildJsonObject {
                        put("owner", owner(live))
                        put("connectors", JsonArray(listOf(JsonPrimitive(name))))
                        put("reconnect", true)
                    },
                    timeoutMillis = 45_000,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                edit { it.copy(error = strings.get(R.string.connect_retry_failed, name)) }
            }
        }
    }

    /**
     * Installs a catalog plugin again after its card settled, with the choices the user approved;
     * the row shows the result itself, since the operation is over.
     */
    fun reinstallPlugin(opId: String, name: String) = withRoot(liveOwner ?: lastLoadOwner ?: initialWork.owner) { origin ->
        val target = _state.value.connections.firstOrNull { it.opId == opId }?.targets?.firstOrNull { it.name == name } ?: return@withRoot
        val catalog = target.catalog ?: return@withRoot
        launchOwned(origin) {
            try {
                val result = rpc(
                    "plugins.manage",
                    buildJsonObject {
                        put("action", "install")
                        put("identifier", "")
                        put("catalog_name", name)
                        put("force", catalog.approved?.force ?: false)
                        put("enable", catalog.approved?.enable ?: true)
                        catalog.approved?.ref?.let { put("ref", it) }
                        catalog.targetProfile?.let { put("profile", it) }
                    },
                    timeoutMillis = 120_000,
                ) as? JsonObject
                if (result?.bool("ok") == false) error(result.string("error") ?: strings.get(R.string.error_failed))
                replaceTarget(opId, name) {
                    it.copy(state = TargetState.CONNECTED, detail = "", catalog = catalog.copy(alreadyInstalled = false, enabled = result?.bool("enabled") ?: catalog.enabled))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                replaceTarget(opId, name) { it.copy(state = TargetState.FAILED, detail = e.message.orEmpty()) }
            }
        }
    }

    private fun replaceTarget(opId: String, name: String, change: (ConnectionTarget) -> ConnectionTarget) = edit { state ->
        state.copy(
            connections = state.connections.map { c ->
                if (c.opId != opId) c else c.copy(targets = c.targets.map { if (it.name == name) change(it) else it })
            },
        )
    }

    /** The user opened the sign-in link of a row; it waits for the browser now. */
    fun linkOpened(opId: String, name: String) = edit { state ->
        state.copy(connections = state.connections.map { if (it.opId == opId) it.copy(opened = it.opened + name) else it })
    }

    /**
     * The user came back from the browser. The server gives the phone no link back to the app, so it
     * is told now to read the accounts instead of at its next check, and the card takes the result.
     */
    fun browserReturned() = withRoot(liveOwner ?: lastLoadOwner ?: initialWork.owner) { origin ->
        val live = liveId ?: return@withRoot
        val waiting = _state.value.connections.filter { it.awaitsBrowser }
        if (waiting.isEmpty()) return@withRoot
        launchOwned(origin) {
            for (connection in waiting) {
                val params = buildJsonObject {
                    put("owner", owner(live))
                    put("op_id", connection.opId)
                }
                try {
                    rpc("connectors.operation.wake", params)
                    val status = rpc("connectors.operation.status", params) as? JsonObject ?: continue
                    edit { state -> state.copy(connections = state.connections.map { it.applying(status) }) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The server checks the accounts by itself as well; the card moves with its update.
                    Log.w(TAG, "Wake of ${connection.opId} failed", e)
                }
            }
        }
    }

    /** The apps the user can connect through Nous, for a directory card. */
    suspend fun connectorDirectory(): List<ConnectorApp> = withContext(workContext.asContextElement(captureWork())) {
        val result = rpc("connectors.list", buildJsonObject { put("owner", buildJsonObject { put("type", "account") }) }) as? JsonObject
        if (result?.bool("available") == false) return@withContext emptyList()
        return@withContext (result?.get("connectors") as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            ConnectorApp(obj.string("connector") ?: return@mapNotNull null, obj.bool("connected") ?: false, obj.string("connection_status"))
        }
    }

    /** Starts connecting [app] to the account from a directory card; the link to sign in, if there is one to open. */
    suspend fun connectApp(app: ConnectorApp): String? = withContext(workContext.asContextElement(captureWork())) {
        val result = rpc(
            "connectors.connect",
            buildJsonObject {
                put("owner", buildJsonObject { put("type", "account") })
                put("connectors", JsonArray(listOf(JsonPrimitive(app.slug))))
                put("reconnect", app.status in setOf("failed", "expired", "revoked", "inactive"))
            },
            timeoutMillis = 45_000,
        ) as? JsonObject
        return@withContext (result?.get("targets") as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.string("connect_url")?.takeIf { url -> url.startsWith("https://") } }
    }

    private fun answer(result: () -> JsonElement) = withRoot(liveOwner ?: lastLoadOwner ?: initialWork.owner) { origin ->
        val prompt = _state.value.prompt ?: return@withRoot
        respond(origin, prompt.request.id, result())
        edit { it.copy(prompt = null) }
    }

    /** Incoming frames inherit an admitted socket or in-flight join, never current credentials. */
    private fun incomingWork(transport: Long): Work? {
        if (transport == 0L || transport != socket.transport.value) return null
        val real = socket as? org.evsyukov.hermes.data.remote.GatewaySocket
        if (real != null) return real.ownerOn(transport)?.let { Work(it, _state.value.storedId, transport, liveObservationTicket) }
        // Synthetic GatewayChannel peers carry the actual join's already-captured work.
        return lastWork?.takeIf { it.transport.get() == transport }
            ?: liveOwner?.let { Work(it, _state.value.storedId, transport, liveObservationTicket) }
    }

    private fun withWork(origin: Work, block: () -> Unit) {
        val prior = workContext.get()
        workContext.set(origin)
        try { publish(origin.owner, block) } finally { workContext.set(prior) }
    }

    private fun onEvent(event: GatewayEvent) {
        val origin = incomingWork(event.transport) ?: return
        if (event.owner != null && event.owner != origin.owner) return
        withWork(origin) {
            // Withdrawals can precede the resume answer that identifies their live session.
            if (event.type == "request.cancel") takeWithdrawal(event)
            else if (event.transport == joinedOn && liveOwner == origin.owner) applyEvent(event, origin.owner)
        }
    }

    @Volatile internal var afterCurrentEvent: ((String) -> Unit)? = null

    private fun applyEvent(event: GatewayEvent, origin: WorkOwner) {
        val live = liveId
        when (event.type) {
            "session.title" -> if (event.payload.string("session_id") == _state.value.storedId || event.sessionId == live) {
                edit { it.copy(title = event.payload.string("title").orEmpty()) }
            }
        }
        if (event.type == "request.cancel") {
            takeWithdrawal(event)
            return
        }
        if (live == null || event.sessionId != live) return
        if (event.type in TRANSCRIPT_EVENTS || event.type in setOf("session.info", "error", "thinking.delta")) currentEventRevision++
        val sent = sentAt
        if (sent != 0L) {
            when (event.type) {
                "message.start" -> Log.i(TIMING_TAG, "Turn started ${System.currentTimeMillis() - sent} ms after sending")
                "message.delta" -> {
                    Log.i(TIMING_TAG, "First words of the reply ${System.currentTimeMillis() - sent} ms after sending")
                    sentAt = 0L
                }
                "message.complete", "error" -> sentAt = 0L
            }
        }
        when (event.type) {
            "message.start" -> {
                awaitingStart = false
                sends.started()
            }
            // The turn this chat's send waited for is over: an idle answer taken before that send is
            // no longer newer than the outcome, so it may speak for the chat again. An outcome of an
            // older or adopted turn has no started local send and leaves the local sends alone.
            "message.complete", "error" -> {
                awaitingStart = false
                sends.ended()
            }
        }
        synchronized(lock) {
            // Hidden, the words of the answer go to their buffer: no transcript is rebuilt for them.
            if (!visible && buffer(event)) return
            // The fields of the turn leave the transcript alone, so the buffered words wait for a change that needs them.
            when (event.type) {
                // A turn starts only after a queued model switch has been applied, so the pick is the model now.
                "message.start" -> edit(flush = false) { it.copy(running = true, status = null, modelPending = false) }
                "message.complete" -> {
                    edit(flush = false) { it.copy(running = false, status = null) }
                    refreshUsage(origin)
                    // A new chat has no stored history on the phone until one is read; the first finished
                    // turn writes it so switching away and back does not redraw the chat from nothing.
                    if (!warmed) warmHistory(origin)
                }
                "session.info" -> edit(flush = false) { state ->
                    // Idle before the sent turn started is the state the session had before the prompt, not the end of the turn.
                    val running = event.payload.runningFlag()?.takeUnless { !it && awaitingStart }
                    val model = event.payload.string("model")
                    // A pick the server dropped (an unconfirmed expensive model, a failed switch) reports
                    // the old model; one still queued reports the pick. Only the former ends the pending state.
                    val pending = state.modelPending && (model == null || model == state.model)
                    state.copy(running = running ?: state.running, model = model ?: state.model, modelPending = pending)
                }
                "status.update" -> edit(flush = false) { it.copy(status = event.payload.string("text")?.takeIf { t -> t.isNotBlank() }) }
                "thinking.delta" -> strings.get(R.string.chat_thinking).let { thinking ->
                    edit(flush = false) { if (it.status == thinking) it else it.copy(status = thinking) }
                }
                "error" -> edit(flush = false) { it.copy(error = event.payload.string("message"), running = false) }
                "connection.request" -> connectionOf(event.payload)?.let(::showConnection)
                "connection.update" -> edit(flush = false) { state -> state.copy(connections = state.connections.map { it.applying(event.payload) }) }
            }
            if (event.type in TRANSCRIPT_EVENTS) edit { it.copy(items = reduce(it.items, event, keyOf = { "live$localNamespace:${keys.incrementAndGet()}" })) }
            if (event.type in setOf("message.start", "message.interim", "reasoning.available", "tool.start", "tool.complete", "message.complete", "error")) {
                val observed = observationBook.snapshot()
                if (observed.none { it.id == observationBook.active && !it.completed }) {
                    val tail = _state.value.items.lastOrNull() as? ChatItem.Assistant
                    if (tail != null && observed.none { row -> row.items.any { it.key == tail.key } }) {
                        val id = observationBook.continuation(origin.profileId, session.profile.url.toString(), _state.value.storedId.orEmpty(), origin.generation, tail)
                        observationWrites[id] = ObservationWrite(origin, operation().observationTicket ?: liveObservationTicket)
                        edit { it.copy(currentObservationId = id) }
                    }
                }
                observationBook.semantic(ReceiptOrigin(origin.profileId, origin.generation, event.sessionId.orEmpty(), event.transport, liveRemoteProfile), event.type, event.payload)
                observationBook.refreshItems(_state.value.items)
                syncObservations()
            }
            if (event.type == "message.complete" || event.type == "error") {
                observationBook.terminal(ReceiptOrigin(origin.profileId, origin.generation, event.sessionId.orEmpty(), event.transport, liveRemoteProfile), event.payload, _state.value.items, _state.value.error)
                syncObservations()
            }
            afterCurrentEvent?.invoke(event.type)
        }
    }

    /**
     * Appends a delta of words or reasoning to the buffer of a hidden chat: to the last run when it is
     * of the same kind, as the transcript would grow its last piece, or as a new run. False for any
     * other event, which is applied as it comes.
     */
    private fun buffer(event: GatewayEvent): Boolean {
        val text = when (event.type) {
            "message.delta" -> (event.payload["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            "reasoning.delta" -> event.payload.string("text")
            else -> null
        } ?: return false
        if (streamed.isEmpty()) streamedAt = nowSeconds()
        val last = streamed.lastOrNull()
        if (last != null && last.first == event.type) last.second.append(text) else streamed += event.type to StringBuilder(text)
        return true
    }

    private fun syncObservations() {
        val page = observedHistory
        val groups = if (page == null) observationBook.snapshot() else observationBook.reconcile(page.rows, page.session, page.profile, operation().owner.generation)
        val retired = groups.filter { it.reconciled }.flatMap { it.items }.mapTo(HashSet()) { it.key }
        edit { it.copy(observations = groups, items = if (retired.isEmpty()) it.items else it.items.filterNot { item -> item.key in retired }) }
        stageObservations()
        persistObservations()
    }

    private fun stageObservations() {
        observationBook.snapshot().forEach { row -> observationWrites[row.id]?.let { session.observations.stage(it.owner, it.ticket, row) } }
    }
    private fun observationWarning(owner: WorkOwner) = session.observations.hasFailures(owner.profileId, session.profile.url.toString(), _state.value.storedId.orEmpty())
    private suspend fun saveObservation(pair: Pair<ObservationWrite, LocalObservation>) {
        val id = pair.second.id
        try {
            session.observations.put(pair.first.owner, pair.first.ticket, pair.second)
            publish(pair.first.owner) { synchronized(lock) { if (!closed) edit(flush = false) { it.copy(observationStorageError = observationWarning(pair.first.owner)) } } }
        }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { publish(pair.first.owner) { synchronized(lock) {
            if ((scheduledObservationRevision[id] ?: -1) <= pair.second.revision) scheduledObservationRevision.remove(id)
            if (!closed) edit(flush = false) { it.copy(observationStorageError = observationWarning(pair.first.owner)) }
        } } }
    }
    private fun persistObservations() {
        observationBook.snapshot().forEach { row ->
            if (!observationWrites.containsKey(row.id) || (scheduledObservationRevision[row.id] ?: -1) >= row.revision) return@forEach
            val writer = observationWrites[row.id] ?: return@forEach
            val payload = writer to row
            scheduledObservationRevision[row.id] = row.revision
            recordContinuation(scope.launch(Dispatchers.IO) { saveObservation(payload) })
        }
    }
    private suspend fun restoreObservations(owner: WorkOwner, id: String) {
        val lineage = session.profile.url.toString()
        val saved = try { session.observations.load(owner, lineage, id) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { session.observations.pending(owner, lineage, id) }
        val recoveryTicket = operation().observationTicket ?: observationRecoveryTicket
        publish(owner) { synchronized(lock) {
            if (closed) return@synchronized
            val keep = observationBook.snapshot().filter { row -> observationWrites[row.id]?.let { session.holds(it.owner) && session.observations.valid(it.ticket, row.session) } == true }.mapTo(HashSet()) { it.id }
            val admitted = if (session.observations.valid(recoveryTicket, id)) saved else emptyList()
            observationBook.restore(admitted, keep)
            admitted.forEach { row -> observationWrites.putIfAbsent(row.id, ObservationWrite(owner, recoveryTicket)) }
            val groups = observationBook.snapshot()
            edit(flush = false) { it.copy(observations = groups, currentObservationId = it.currentObservationId?.takeIf { key -> groups.any { row -> row.id == key } }, observationStorageError = session.observations.hasFailures(owner.profileId, lineage, id)) }
        } }
    }
    fun reviewObservation(id: String, collapsed: Boolean) = withRoot { origin ->
        synchronized(lock) {
            val row = observationBook.snapshot().firstOrNull { it.id == id } ?: return@synchronized
            val ticket = session.observations.capture(origin.owner.profileId, session.profile.url.toString(), row.session)
            observationBook.collapse(id, collapsed)
            edit { it.copy(observations = observationBook.snapshot()) }
            recordContinuation(scope.launch(Dispatchers.IO) {
                try { session.observations.presentation(origin.owner, ticket, id, collapsed); publish(origin.owner) { synchronized(lock) { if (!closed) edit(flush = false) { it.copy(observationStorageError = observationWarning(origin.owner)) } } } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { publish(origin.owner) { if (!closed) edit(flush = false) { it.copy(observationStorageError = observationWarning(origin.owner)) } } }
            })
        }
    }

    /**
     * Reads the stored history into the phone's cache. A failure leaves the cache as it was; the
     * next finished turn or [close] tries again.
     */
    private fun warmHistory(owner: WorkOwner) {
        val id = _state.value.storedId ?: return
        val chat = session.chatFor(owner)
        recordContinuation(scope.launch {
            if (holds(owner)) runCatching { chat.messages(id) }.onSuccess { publish(owner) { warmed = true } }
        })
    }

    /**
     * The server totals tokens and cost in the stored chat when a turn ends; the socket reports tokens
     * alone, counted only since the session was joined.
     */
    private fun refreshUsage(owner: WorkOwner) {
        val id = _state.value.storedId ?: return
        val chat = session.chatFor(owner)
        recordContinuation(scope.launch {
            try {
                chat.get(id)?.let { read ->
                    read.usage?.let { usage -> publish(owner) { edit { it.copy(usage = usage) } } }
                    // The turn that just ended is on the screen: its answer is read, in the
                    // authorization the read started in.
                    tellRead(id, read.lastActive, owner)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The last known totals stay; the next turn or opening the chat reads them again.
                Log.w(TAG, "Usage not read", e)
            }
        })
    }

    /**
     * The server stopped waiting for a request: at its deadline, or because the turn or the session
     * ended or another client answered. A card the user settled keeps its outcome. Remembered even for
     * a request the chat has not shown yet: a join answered before the withdrawal may still list it,
     * and must not open it again. Called by [observe] alone.
     */
    private fun withdraw(event: GatewayEvent) = edit(flush = false) { state ->
        val id = event.payload.string("id")
        val ended = if (event.payload.string("reason") == "timeout") QuestionState.EXPIRED else QuestionState.CLOSED
        if (id != null && questionMemory.ended(state.storedId.orEmpty(), id) == null) questionMemory.end(state.storedId.orEmpty(), id, ended)
        state.copy(
            prompt = if (state.prompt?.request?.id == id) null else state.prompt,
            questions = state.questions.map { if (it.id == id && it.state == QuestionState.PENDING) it.copy(state = ended) else it },
        )
    }

    /**
     * A request of the server, live or [listed] among the open requests of a join answered over
     * connection [generation].
     */
    private fun onRequest(request: ServerRequest, generation: Int = socket.connections.value, listed: Boolean = false) {
        val origin = if (listed) operation() else incomingWork(request.transport) ?: return
        withWork(origin) { applyRequest(request, generation, listed, origin.owner) }
    }

    private fun applyRequest(request: ServerRequest, generation: Int, listed: Boolean, owner: WorkOwner) {
        val live = liveId
        val sessionId = request.params.string("session_id")
        if (request.method == "clarify") {
            takeQuestion(request, generation, listed = listed)
            return
        }
        if (live == null || sessionId != live || request.transport == 0L || request.transport != joinedOn) return
        val params = request.params
        val prompt = when (request.method) {
            "approval" -> approvalOf(request)
            "sudo" -> Prompt.Secret(request, strings.get(R.string.chat_sudo_password, params.string("command").orEmpty()), sudo = true)
            "secret" -> Prompt.Secret(request, params.string("prompt") ?: strings.get(R.string.chat_secret_value, params.string("env_var").orEmpty()), sudo = false)
            else -> {
                // Desktop-only questions (window, terminal, theme): saying no at once keeps the agent from waiting.
                socket.respondOwned(owner, request.transport, request.id, null, error = "Method not supported by the Android client")
                null
            }
        }
        if (prompt != null) edit(flush = false) { it.copy(prompt = prompt) }
    }

    private fun JsonObject.runningFlag(): Boolean? = when ((get("running") as? JsonPrimitive)?.content) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }

    private companion object {
        const val TAG = "HermesChat"

        /** The events [reduce] builds the transcript from. */
        val TRANSCRIPT_EVENTS = setOf(
            "message.start", "message.delta", "message.interim", "reasoning.delta", "reasoning.available",
            "tool.start", "tool.complete", "message.complete",
        )
    }
}

/** True when an RPC error says the live session is gone and a resume by the stored id will help. */
fun RpcException.sessionGone(): Boolean = code == 4001

/**
 * The join answer reports `running` as 0 or 1, not as a boolean (docs/fixtures/socket/resume.json);
 * the older boolean form is still taken. This is the wire of this one field: a general
 * [JsonObject.bool] would read `0` as null and quietly call a running turn idle.
 */
private fun JsonObject.runningFlag(): Boolean? =
    this["running"]?.let { (it as? JsonPrimitive)?.contentOrNull?.let { value ->
        value.toBooleanStrictOrNull() ?: when (value) {
            "0" -> false
            "1" -> true
            else -> null
        }
    } }
