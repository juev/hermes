package org.evsyukov.hermes.chat

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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
    /** The messages of the chat are on the screen up to the server's `last_active`; tells the read mark. */
    private val onRead: (storedId: String, lastActive: Double) -> Unit = { _, _ -> },
    /** Whether the chat starts on the screen; the screen tells it afterwards with [setVisible]. */
    visible: Boolean = true,
    /** The questions of the chats of this connection, which outlive this chat being open. */
    private val questionMemory: QuestionMemory = QuestionMemory(),
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

    /** What the chat read while hidden, to tell the read mark once it is shown. */
    private var pendingRead: Double? = null

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
    private fun edit(flush: Boolean = true, change: (ChatState) -> ChatState) = synchronized(lock) {
        if (flush) flushStreamed()
        val asked = _state.value.questions
        _state.value = change(_state.value)
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
            items = reduce(items, GatewayEvent(type, liveId, null, buildJsonObject { put("text", text.toString()) }), keyOf = { "a${keys.incrementAndGet()}" }, now = { at })
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
        val read = synchronized(lock) {
            if (visible == shown) return
            visible = shown
            if (!shown) return
            flushStreamed()
            published.value = _state.value
            pendingRead.also { pendingRead = null }
        }
        val id = _state.value.storedId
        if (read != null && id != null) onRead(id, read)
    }

    /** Tells the read mark, or keeps it for when the chat is on the screen: a hidden chat is not read. */
    private fun tellRead(id: String, lastActive: Double) {
        val now = synchronized(lock) {
            visible.also { if (!it) pendingRead = maxOf(pendingRead ?: lastActive, lastActive) }
        }
        if (now) onRead(id, lastActive)
    }

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
    private val rejoinPending = AtomicBoolean(false)

    /** Loads asked for while one runs; the newest request wins. */
    private val reloadRequests = Channel<Unit>(Channel.CONFLATED)

    /** The answer of `session.create` for a new chat that has not been sent; guarded by [joining]. */
    private var draft: JsonObject? = null

    /** The connection [draft] was created over, by [GatewayChannel.transport]; 0 when that is not known. */
    private var draftOn = 0L

    @Volatile
    private var closed = false
    private val keys = AtomicLong()
    private val jobs = mutableListOf<Job>()

    /** When the last message was sent, for the log of its first reply; 0 once the reply came. */
    @Volatile
    private var sentAt = 0L

    /** The stored history is in the phone's cache, so closing the chat has nothing to warm. */
    @Volatile
    private var warmed = false

    /**
     * A turn was sent and has not started. The server may report the session idle meanwhile: a new
     * session announces its agent once built, and that can happen before the prompt reaches it.
     */
    @Volatile
    private var awaitingStart = false

    /**
     * The questions and withdrawals that came over the connection the socket is on while the chat has
     * no join over it yet — one on its way, or none begun — in the order they came. Guarded by [lock].
     * None is left out: a withdrawal or a question dropped here could be newer than the answer of the
     * join it waits for, which could then not tell it. A join over that connection takes those of its
     * session once it answers and the others go; a new connection or closing the chat empties it.
     */
    private val early = ArrayList<Heard>()

    /** A question or a withdrawal as [observe] took it, with the place it came in among all it took. */
    private class Heard(val observation: Any, val ordinal: Long)

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
                // The load that opens a chat joins the connection it finds; only a later one needs it again.
                if (joinedGeneration.get() == 0 && generation <= connectedWhenMade) return@collect
                if (_state.value.loading) {
                    rejoinPending.set(true)
                    return@collect
                }
                forgetLive()
                reload()
            }
        }
        jobs += scope.launch { for (requested in reloadRequests) runLoads() }
        if (storedId != null) {
            reload()
        } else {
            // Waits for the socket rather than opening it: the app decides when it connects.
            jobs += scope.launch { socket.state.collect { renewDraft() } }
        }
    }

    fun close() {
        closed = true
        jobs.forEach { it.cancel() }
        synchronized(lock) { early.clear() }
        // Keep what the chat shows on the phone: opening it again puts that on the screen at once
        // and refreshes it under the surface, rather than waiting for the server with an empty chat.
        _state.value.storedId?.let { id -> scope.launch { runCatching { session.chat.messages(id) } } }
        scope.launch { renewDraft() }
    }

    /**
     * Creates the session of a new chat while it is empty, on a ready socket. The draft it replaces
     * belongs to a lost connection, another model or a chat that is closed, so it is closed first.
     */
    private suspend fun renewDraft() = withContext(NonCancellable) {
        joining.withLock {
            val ready = socket.state.value == SocketState.READY
            draft?.let { old ->
                draft = null
                // The server drops a session of a lost connection by itself.
                if (ready) old.string("session_id")?.let { live -> runCatching { socket.call("session.close", buildJsonObject { put("session_id", live) }) } }
            }
            if (closed || !ready || liveId != null || _state.value.storedId != null) return@withLock
            draft = try {
                onTransport { create() }.let { (answer, on) -> answer.also { draftOn = on } }
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
        socket.awaitReady()
        val before = socket.transport.value
        val answer = call()
        return answer to if (before != 0L && socket.transport.value == before) before else 0L
    }

    private suspend fun create() = socket.call(
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
    fun reload() {
        if (!closed) reloadRequests.trySend(Unit)
    }

    /**
     * Runs one load at a time. A connection that came while a load ran is joined by another load
     * once the first is over: the load may have sent its resume on the lost connection and failed,
     * and no further connection would tell the chat about it.
     */
    private suspend fun runLoads() {
        while (true) {
            rejoinPending.set(false)
            performLoad()
            val queued = reloadRequests.tryReceive().isSuccess
            val rejoin = rejoinPending.getAndSet(false)
            if (rejoin && socket.connections.value != joinedGeneration.get()) continue
            if (queued) continue
            return
        }
    }

    private suspend fun performLoad() {
        val id = _state.value.storedId ?: return
        edit { it.copy(loading = true, error = null) }
        if (current.items.isEmpty()) showCached(id)
        try {
            coroutineScope {
                val head = async { runCatching { session.chat.get(id) } }
                val history = async { runCatching { transcriptOf(session.chat.messages(id)) } }
                val summary = head.await().getOrNull()
                summary?.let { read ->
                    edit { state ->
                        state.copy(
                            title = read.title,
                            // The live session knows its model better than the stored row: after a
                            // pick the row may still carry the previous one until the next turn.
                            model = if (liveId != null && state.model != null) state.model else read.model,
                            usage = read.usage,
                        )
                    }
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
                                val joinAnswer = resume(id)
                                val generation = if (generationAtStart == 0) socket.connections.value else generationAtStart
                                joinAnswer.answer.string("session_id")?.let { takeLive(it, joinAnswer.transport) }
                                joinAnswer to generation

                            }
                        }
                    }
                } else {
                    null
                }
                val read = history.await()
                if (read.isSuccess) {
                    showHistory(read.getOrDefault(emptyList()))
                    // What is on the screen now is read: a mark in the sidebar clears, and a new
                    // answer after this will mark it again.
                    summary?.let { tellRead(id, it.lastActive) }
                } else {
                    edit { it.copy(loading = false, error = read.exceptionOrNull()?.message) }
                }
                joined?.getOrNull()?.let { (joinAnswer, generation) -> adopt(joinAnswer, id, starting = false, generation) }
                joined?.exceptionOrNull()?.let { throw it }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            edit { it.copy(loading = false, error = e.message) }
        }
    }

    /**
     * Puts [history] on the screen, keeping a message the server has not stored yet: one the user
     * sent while the history was on its way must not vanish from under them. An answer that streams
     * comes back with the join, which is applied after this.
     */
    private fun showHistory(history: List<ChatItem>) {
        edit { state ->
            val known = history.mapTo(HashSet()) { it.key }
            val unsent = state.items.filter { it.key !in known && it is ChatItem.User && it.rowId == null }
            state.copy(items = if (unsent.isEmpty()) history else history + unsent, loading = false)
        }
    }

    private suspend fun showCached(id: String) {
        val cached = session.chat.cached ?: return
        try {
            val history = transcriptOf(cached.messages(id))
            val summary = runCatching { cached.get(id) }.getOrNull()
            edit { if (it.items.isEmpty()) it.copy(items = history, title = summary?.title.orEmpty(), model = summary?.model, usage = summary?.usage) else it }
            summary?.let { tellRead(id, it.lastActive) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not on the phone yet, or unreadable: the server's answer fills the chat.
        }
    }

    /** Joins the live session; [starting] a turn, the chat stays running whatever the session was doing. */
    private suspend fun ensureLive(starting: Boolean = false): String {
        liveId?.let { return it }
        return joining.withLock { liveId ?: join(starting) }
    }

    private suspend fun join(starting: Boolean): String {
        val stored = _state.value.storedId
        // The connection the answer comes over: a call made before the socket is up opens it, so the
        // number is read again after the call when there was none.
        val generationAtStart = socket.connections.value
        val joinAnswer = if (stored == null) {
            val asked = heardLive.get()
            val kept = draft
            draft = null
            // A session made in advance over a connection since lost is not this connection's: a new one is made.
            if (kept != null && draftOn != 0L && draftOn == socket.transport.value) {
                JoinAnswer(kept, asked, draftOn)
            } else {
                kept?.string("session_id")?.let { old -> runCatching { socket.call("session.close", buildJsonObject { put("session_id", old) }) } }
                onTransport { create() }.let { (answer, on) -> JoinAnswer(answer, asked, on) }
            }
        } else {
            resume(stored)
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
        val ready = socket.state.value == SocketState.READY || withTimeoutOrNull(3_000) { socket.awaitReady(); true } == true
        if (!ready) return Result.failure(IOException("no connection"))
        return runCatching {
            val answer = socket.call("session.active_list", buildJsonObject { put("current_session_id", id) }, timeoutMillis = 10_000) as? JsonObject
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
    private suspend fun resume(stored: String): JoinAnswer {
        val asked = heardLive.get()
        val (answer, transport) = onTransport {
            socket.call(
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
        return JoinAnswer(answer, asked, transport)
    }

    /**
     * The join answered with live session [live] over connection [transport], 0 when that connection
     * is already lost: what came for the session meanwhile is applied now, in the order it came, and
     * what came for any other session is dropped.
     */
    private fun takeLive(live: String, transport: Long) = synchronized(lock) {
        liveId = live
        // Another join, or one over a lost connection: what an earlier join confirmed is not confirmed by this one.
        if (joinedOn != transport) {
            joinedOn = transport
            unconfirm()
        }
        val heard = ArrayList(early)
        early.clear()
        // In the order they came, each with the place it came in, not the moment of this replay.
        heard.forEach { entry ->
            when (val observation = entry.observation) {
                is GatewayEvent -> takeWithdrawal(observation, entry.ordinal)
                is ServerRequest -> takeQuestion(observation, socket.connections.value, entry.ordinal)
            }
        }
    }

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
            early += Heard(observation, heard)
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
    private fun forgetLive() = synchronized(lock) {
        liveId = null
        invalidateJoin()
    }

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

    /** The answer of a join, the connection it came over and the count of [heardLive] when it was asked. */
    private class JoinAnswer(val answer: JsonObject, val asked: Long, val transport: Long)

    /**
     * Applies the answer of a join: the live id, the turn that runs, its card and the questions the
     * server holds. Separately from [resume] so a chat that reconnects can ask at once and apply
     * once its history is on the screen, the turn streaming on top of it.
     */
    private fun adopt(joinAnswer: JoinAnswer, stored: String?, starting: Boolean, generation: Int): String {
        val result = joinAnswer.answer
        val live = result.string("session_id") ?: error("No session_id in the answer")
        // What the answer says of the requests holds on the connection it came over, and confirms
        // nothing once the socket is on another one: the join over that one brings its own answer.
        val current = joinAnswer.transport != 0L && socket.transport.value == joinAnswer.transport
        takeLive(live, if (current) joinAnswer.transport else 0L)
        joinedGeneration.set(generation)
        // The answer came over `generation`; a connection that came meanwhile may have moved the
        // card or the turn, so that one is joined as well.
        if (socket.connections.value != generation) rejoinPending.set(true)
        val storedId = result.string("stored_session_id") ?: stored
        val info = result["info"] as? JsonObject
        val running = starting || (result.bool("running") ?: info?.bool("running") ?: false)
        // A turn under way when the chat opens goes on in its answer, with what streamed while the chat was away.
        val resumed = running && !starting
        val streamed = (result["inflight"] as? JsonObject)?.string("assistant").orEmpty()
        edit {
            it.copy(
                storedId = storedId ?: it.storedId,
                running = running,
                model = info?.string("model") ?: it.model,
                items = if (resumed) resumeTurn(it.items, streamed, keyOf = { "a${keys.incrementAndGet()}" }) else it.items,
            )
        }
        if (stored == null && storedId != null) onSessionCreated(storedId)
        if (storedId != null) onLive(live, storedId, _state.value.title)
        // A card left open while the chat was away comes back with the server's deadline; one the
        // server no longer has open ended while the socket was down and takes no answers.
        val pending = connectionOf(result["pending_connection"] as? JsonObject)
        edit { state -> state.copy(connections = state.connections.map { if (it.settled || it.opId == pending?.opId) it else it.copy(settled = true) }) }
        pending?.let(::showConnection)
        if (current) {
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
        return live
    }

    /** Runs [block] on the live session; a session the server dropped is joined again once. */
    private suspend fun <T> withLive(starting: Boolean = false, block: suspend (String) -> T): T = try {
        block(ensureLive(starting))
    } catch (e: RpcException) {
        if (!e.sessionGone()) throw e
        forgetLive()
        block(ensureLive(starting))
    }

    fun send(text: String, attachments: List<LocalAttachment>) {
        if (text.isBlank() && attachments.isEmpty()) return
        val key = "local${keys.incrementAndGet()}"
        val shown = attachments.map { Attachment(it.name, it.name, it.image) }
        edit { it.copy(items = it.items + ChatItem.User(key, text, shown, delivery = Delivery.SENDING, time = nowSeconds()), running = true, error = null) }
        awaitingStart = true
        val hold = onTurnStart()
        val started = System.currentTimeMillis().also { sentAt = it }
        scope.launch {
            // The prompt may have reached the server even when its answer does not come back: an
            // unknown outcome is reported, never retried on its own, and the message stays visible.
            var submitted = false
            try {
                // A message sent before the socket is up says so instead of waiting in silence.
                if (socket.state.value != SocketState.READY) edit { it.copy(connecting = true) }
                try {
                    socket.awaitReady()
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
                    submitted = true
                    socket.call("prompt.submit", buildJsonObject { put("session_id", live); put("text", prompt) })
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
                failDelivery(key, delivery, error)
            }
        }.invokeOnCompletion { cause -> if (cause != null) hold.release() }
    }

    /**
     * Whether the server may have taken the prompt: an answer that never came (a timeout or a lost
     * connection) after the submit went out is unknown; a refusal or a failure before it is sent.
     */
    private fun deliveryFor(e: Exception, submitted: Boolean): Delivery =
        if (!submitted || e is RpcException) Delivery.FAILED else if (e is IOException || e is TimeoutCancellationException) Delivery.UNKNOWN else Delivery.FAILED

    private fun markDelivery(key: String, delivery: Delivery) = edit { state ->
        state.copy(items = state.items.map { item -> if (item is ChatItem.User && item.key == key) item.copy(delivery = delivery) else item })
    }

    /** The submission did not go through: the message keeps its place with the outcome, in one update. */
    private fun failDelivery(key: String, delivery: Delivery, error: String) = edit { state ->
        state.copy(
            items = state.items.map { item -> if (item is ChatItem.User && item.key == key) item.copy(delivery = delivery) else item },
            running = false,
            error = error,
        )
    }

    /** Pictures go to the vision queue of the next turn; other files land in the workspace and are named in the prompt. */
    private suspend fun attach(live: String, file: LocalAttachment): String? {
        val base64 = Base64.getEncoder().encodeToString(file.bytes)
        return if (file.image) {
            socket.call(
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
            val result = socket.call(
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

    fun stop() {
        val live = liveId ?: return
        scope.launch {
            try {
                socket.call("session.interrupt", buildJsonObject { put("session_id", live) })
            } catch (e: Exception) {
                edit { it.copy(error = e.message) }
            }
            awaitingStart = false
            edit { it.copy(items = closeOpen(it.items, interrupted = true), running = false) }
        }
    }

    /** Asks for the last answer again: the server drops the last turn and the same text goes again. */
    fun regenerate() = rewriteLast(null)

    /** Replaces the last user message with [text] and answers it anew. */
    fun editLast(text: String) = rewriteLast(text)

    private fun rewriteLast(replacement: String?) {
        val lastUser = current.items.lastOrNull { it is ChatItem.User } as? ChatItem.User ?: return
        val again = lastUser.rewritten(replacement, "local${keys.incrementAndGet()}").copy(delivery = Delivery.SENDING, time = nowSeconds())
        val text = again.sent
        var hold: TurnHold? = null
        scope.launch {
            var submitted = false
            try {
                val live = ensureLive()
                if (_state.value.running) socket.call("session.interrupt", buildJsonObject { put("session_id", live) })
                socket.call("session.undo", buildJsonObject { put("session_id", live); put("intent", "retry") })
                val index = current.items.indexOfLast { it is ChatItem.User }
                val kept = if (index >= 0) current.items.subList(0, index) else current.items
                edit { it.copy(items = kept + again, running = true, error = null) }
                awaitingStart = true
                hold = onTurnStart().also { it.live(live) }
                submitted = true
                socket.call("prompt.submit", buildJsonObject { put("session_id", live); put("text", text) })
                markDelivery(again.key, Delivery.SENT)
                hold?.submitted()
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                hold?.release()
                awaitingStart = false
                val delivery = deliveryFor(e, submitted)
                val error = if (delivery == Delivery.UNKNOWN) strings.get(R.string.error_send_unknown) else e.message ?: strings.get(R.string.error_failed)
                failDelivery(again.key, delivery, error)
            }
        }.invokeOnCompletion { cause -> if (cause != null) hold?.release() }
    }

    fun rename(title: String) {
        val id = _state.value.storedId ?: return
        scope.launch {
            try {
                // A rename touches no pin.
                ChatMutations(session.chat, socket, liveOf = { stored -> liveId.takeIf { stored == id } }, pinLocally = { _, _ -> }).rename(id, title)
                edit { it.copy(title = title) }
            } catch (e: Exception) {
                edit { it.copy(error = e.message) }
            }
        }
    }

    fun dismissError() = edit { it.copy(error = null) }

    /** Switches the model of this chat; a running turn finishes on the old one. */
    fun chooseModel(provider: String, model: String, confirmed: Boolean = false) {
        edit { it.copy(modelConfirm = null) }
        if (liveId == null && _state.value.storedId == null) {
            draftModel = provider to model
            edit { it.copy(model = model, modelPending = false) }
            // The draft session was built for the model before; a new one is created with the pick.
            jobs += scope.launch { renewDraft() }
            return
        }
        scope.launch {
            try {
                val result = withLive { live ->
                    socket.call(
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
    private fun resolveQuestion(id: String, settle: (QuestionCard) -> Pair<QuestionCard, JsonElement>) {
        synchronized(lock) {
            // A closed chat answers nothing, and a card this chat's join has not confirmed may be one the server ended.
            if (closed) return
            val card = _state.value.questions.firstOrNull { it.id == id && it.state == QuestionState.PENDING } ?: return
            val chat = _state.value.storedId.orEmpty()
            if (questionMemory.ended(chat, id) != null) return
            if (socket.state.value != SocketState.READY) {
                edit(flush = false) { it.copy(error = strings.get(R.string.chat_question_offline)) }
                return
            }
            // Only over the connection of the join that confirmed it, and only while the socket is still on it.
            val on = validJoin()
            if (!card.confirmed || on == 0L) return
            val (settled, result) = settle(card)
            if (!socket.respondOn(on, id, result)) return
            questionMemory.heard(chat, id, socket.connections.value)
            edit(flush = false) { state -> state.copy(questions = state.questions.map { if (it.id == id) settled else it }) }
        }
    }

    /**
     * Shows a `clarify` request as a card under the answer whose tool asked it, and confirms it takes
     * an answer: it is open on the session this chat has joined, live or [listed] by the join. The
     * request comes again with every join while it waits: a card the chat has stays one card, with
     * what the user wrote on it. A request the server ended stays ended, whatever an older answer
     * still lists: its card shows how it ended, also when the chat had not shown it yet. A card the
     * user settled opens again only when the server still holds the request over a later connection
     * than the one its answer went over, which lost the answer. [ordinal] is the place a live request
     * came in among all [observe] took.
     */
    private fun showQuestion(request: ServerRequest, generation: Int, listed: Boolean, ordinal: Long) = edit(flush = false) { state ->
        val chat = state.storedId.orEmpty()
        if (!listed) seenLive[request.id] = maxOf(seenLive[request.id] ?: 0L, ordinal)
        val known = state.questions.firstOrNull { it.id == request.id }
        val heard = questionMemory.heardOn(chat, request.id)
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
            heard != null && heard < generation -> {
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

    fun answerSecret(value: String?) {
        val prompt = _state.value.prompt ?: return
        if (value == null) {
            socket.respond(prompt.request.id, null, error = "cancelled")
            edit { it.copy(prompt = null) }
        } else {
            answer { buildJsonObject { put("value", value) } }
        }
    }

    fun cancelPrompt() {
        val prompt = _state.value.prompt ?: return
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

    private fun respondConnection(opId: String, result: JsonObjectBuilder.() -> Unit) {
        val live = liveId ?: return
        if (_state.value.connections.none { it.opId == opId && !it.settled }) return
        scope.launch {
            try {
                socket.call(
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
    fun retryConnection(opId: String, name: String) {
        val live = liveId ?: return
        if (_state.value.connections.none { it.opId == opId && !it.settled }) return
        scope.launch {
            try {
                socket.call(
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
    fun reinstallPlugin(opId: String, name: String) {
        val target = _state.value.connections.firstOrNull { it.opId == opId }?.targets?.firstOrNull { it.name == name } ?: return
        val catalog = target.catalog ?: return
        scope.launch {
            try {
                val result = socket.call(
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
    fun browserReturned() {
        val live = liveId ?: return
        val waiting = _state.value.connections.filter { it.awaitsBrowser }
        if (waiting.isEmpty()) return
        scope.launch {
            for (connection in waiting) {
                val params = buildJsonObject {
                    put("owner", owner(live))
                    put("op_id", connection.opId)
                }
                try {
                    socket.call("connectors.operation.wake", params)
                    val status = socket.call("connectors.operation.status", params) as? JsonObject ?: continue
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
    suspend fun connectorDirectory(): List<ConnectorApp> {
        val result = socket.call("connectors.list", buildJsonObject { put("owner", buildJsonObject { put("type", "account") }) }) as? JsonObject
        if (result?.bool("available") == false) return emptyList()
        return (result?.get("connectors") as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            ConnectorApp(obj.string("connector") ?: return@mapNotNull null, obj.bool("connected") ?: false, obj.string("connection_status"))
        }
    }

    /** Starts connecting [app] to the account from a directory card; the link to sign in, if there is one to open. */
    suspend fun connectApp(app: ConnectorApp): String? {
        val result = socket.call(
            "connectors.connect",
            buildJsonObject {
                put("owner", buildJsonObject { put("type", "account") })
                put("connectors", JsonArray(listOf(JsonPrimitive(app.slug))))
                put("reconnect", app.status in setOf("failed", "expired", "revoked", "inactive"))
            },
            timeoutMillis = 45_000,
        ) as? JsonObject
        return (result?.get("targets") as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.string("connect_url")?.takeIf { url -> url.startsWith("https://") } }
    }

    private fun answer(result: () -> JsonElement) {
        val prompt = _state.value.prompt ?: return
        socket.respond(prompt.request.id, result())
        edit { it.copy(prompt = null) }
    }

    private fun onEvent(event: GatewayEvent) {
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
            "message.start", "message.complete", "error" -> awaitingStart = false
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
                    refreshUsage()
                    // A new chat has no stored history on the phone until one is read; the first finished
                    // turn writes it so switching away and back does not redraw the chat from nothing.
                    if (!warmed) warmHistory()
                }
                "session.info" -> edit(flush = false) { state ->
                    // Idle before the sent turn started is the state the session had before the prompt, not the end of the turn.
                    val running = event.payload.bool("running")?.takeUnless { !it && awaitingStart }
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
            if (event.type in TRANSCRIPT_EVENTS) edit { it.copy(items = reduce(it.items, event, keyOf = { "a${keys.incrementAndGet()}" })) }
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

    /**
     * Reads the stored history into the phone's cache. A failure leaves the cache as it was; the
     * next finished turn or [close] tries again.
     */
    private fun warmHistory() {
        val id = _state.value.storedId ?: return
        scope.launch {
            runCatching { session.chat.messages(id) }.onSuccess { warmed = true }
        }
    }

    /**
     * The server totals tokens and cost in the stored chat when a turn ends; the socket reports tokens
     * alone, counted only since the session was joined.
     */
    private fun refreshUsage() {
        val id = _state.value.storedId ?: return
        scope.launch {
            try {
                session.chat.get(id)?.let { read ->
                    read.usage?.let { usage -> edit { it.copy(usage = usage) } }
                    // The turn that just ended is on the screen: its answer is read.
                    tellRead(id, read.lastActive)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The last known totals stay; the next turn or opening the chat reads them again.
                Log.w(TAG, "Usage not read", e)
            }
        }
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
        val live = liveId
        val sessionId = request.params.string("session_id")
        if (request.method == "clarify") {
            takeQuestion(request, generation, listed = listed)
            return
        }
        if (live == null || sessionId != live) return
        val params = request.params
        val prompt = when (request.method) {
            "approval" -> approvalOf(request)
            "sudo" -> Prompt.Secret(request, strings.get(R.string.chat_sudo_password, params.string("command").orEmpty()), sudo = true)
            "secret" -> Prompt.Secret(request, params.string("prompt") ?: strings.get(R.string.chat_secret_value, params.string("env_var").orEmpty()), sudo = false)
            else -> {
                // Desktop-only questions (window, terminal, theme): saying no at once keeps the agent from waiting.
                socket.respond(request.id, null, error = "Method not supported by the Android client")
                null
            }
        }
        if (prompt != null) edit(flush = false) { it.copy(prompt = prompt) }
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
