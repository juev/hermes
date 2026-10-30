package org.evsyukov.hermes.background

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import org.evsyukov.hermes.Connection
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.ApprovalKind
import org.evsyukov.hermes.chat.approvalOf
import org.evsyukov.hermes.chat.connectionOf
import org.evsyukov.hermes.chat.summary
import org.evsyukov.hermes.chat.stripMedia
import org.evsyukov.hermes.data.remote.GatewayEvent
import org.evsyukov.hermes.data.remote.ServerRequest
import org.evsyukov.hermes.data.remote.bool
import org.evsyukov.hermes.data.remote.string
import java.util.concurrent.ConcurrentHashMap

/** A turn the app asked for, from its submission until it starts on the server or fails to reach it. */
interface TurnHold {
    /** The session the turn goes to, once it is known; its start ends the hold. */
    fun live(id: String)

    /** The submission reached the server; its start is awaited from now on. */
    fun submitted()

    /** The submission did not reach the server, failed or was cancelled. */
    fun release()

    companion object {
        /** Holds nothing, for a chat that runs without the watcher. */
        val NONE = object : TurnHold {
            override fun live(id: String) = Unit
            override fun submitted() = Unit
            override fun release() = Unit
        }
    }
}

/**
 * Follows the turns of the chats opened in the app, whatever screen is shown: while one runs, the
 * foreground service keeps the process and its socket alive, and when it ends with the app in the
 * background the answer becomes a notification, the way the ChatGPT app behaves. Approvals that
 * arrive in the background become notifications with buttons.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connection: StateFlow<Connection?>,
    private val notifications: Notifications,
    /** The permanent-connection switch as read from disk; null until it is. */
    private val keepConnected: StateFlow<Boolean?>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Live session id to the stored id the notification opens. */
    private val chats = ConcurrentHashMap<String, String>()
    private val titles = ConcurrentHashMap<String, String>()
    private val streamed = ConcurrentHashMap<String, StringBuilder>()

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    /** The chat on screen; its answers need no notification while the app is in front. */
    @Volatile
    var visibleChat: String? = null

    val inForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** Called when a chat of the app joins its live session. */
    fun bind(liveId: String, storedId: String, title: String) {
        chats[liveId] = storedId
        if (title.isNotBlank()) titles[storedId] = title
    }

    private val notified = ConcurrentHashMap<String, Long>()

    /** True when the watcher announced an answer of [storedId] within the last minutes; push then stays quiet. */
    fun notifiedRecently(storedId: String): Boolean =
        notified[storedId]?.let { System.currentTimeMillis() - it < 5 * 60_000 } ?: false

    /**
     * The chat is read now: its message notification leaves the shade, and the next message may
     * notify again rather than being taken for the one already announced.
     */
    fun read(storedId: String) {
        notifications.cancelChat(storedId)
        notified.remove(storedId)
    }

    /** The live id under which the app has [storedId] open, if it has. */
    fun liveOf(storedId: String): String? = chats.entries.firstOrNull { it.value == storedId }?.key

    /** Turns the app submitted whose start has not arrived; they hold the service like running ones. */
    private val holds: MutableSet<Hold> = ConcurrentHashMap.newKeySet()

    /** Orders the checks of [settle] against the start of a turn, so a stop never overtakes it. */
    private val service = Any()

    // After every property: a dispatcher that runs at once enters these coroutines here, and a switch
    // read already settles the service, which needs the holds and their lock.
    init {
        scope.launch {
            connection.collectLatest { c ->
                if (c == null) return@collectLatest
                launch { c.socket.events.collect(::onEvent) }
                c.socket.requests.collect(::onRequest)
            }
        }
        scope.launch { watchdog() }
        // The switch the user set, not the defaults: a process restarted for the service must not stop
        // it before its settings are read.
        scope.launch {
            keepConnected.filterNotNull().collect { keep ->
                if (keep) HermesService.start(context, context.getString(R.string.notify_connected)) else settle()
            }
        }
    }

    private inner class Hold : TurnHold {
        @Volatile
        var session: String? = null

        /**
         * When the submit returned; until then the submission runs and holds without a limit, whatever
         * the events of its session say.
         */
        @Volatile
        var submittedAt: Long? = null

        /** A start of the session came while submitting: the turn is under way, or over already. */
        var started = false

        override fun live(id: String) {
            session = id
        }

        override fun submitted() {
            synchronized(service) {
                submittedAt = now()
                // The turn holds the service itself now, or has ended.
                if (started && holds.remove(this)) settle()
            }
        }

        override fun release() {
            synchronized(service) {
                if (holds.remove(this)) settle()
            }
        }
    }

    /**
     * The app is about to start a turn; the service starts now, while the app may still start it, and
     * stays until the turn starts or its submission fails.
     */
    fun turnStarting(): TurnHold {
        val hold = Hold()
        synchronized(service) {
            holds += hold
            HermesService.start(context, context.getString(R.string.notify_replying))
        }
        return hold
    }

    /**
     * The app came to the front. A process started in the background, by the periodic check or a
     * push, read the switch while it could not start the service; now it can, and a permanent
     * connection gets its service back. A turn under way keeps its text.
     */
    fun appInFront() {
        synchronized(service) {
            if (keepConnected.value != true) return
            val text = if (_running.value.isEmpty() && holds.isEmpty()) R.string.notify_connected else R.string.notify_replying
            HermesService.start(context, context.getString(text))
        }
    }

    internal fun onEvent(event: GatewayEvent) {
        val live = event.sessionId ?: return
        when (event.type) {
            "session.title" -> event.payload.string("session_id")?.let { stored ->
                event.payload.string("title")?.let { titles[stored] = it }
            }
            "message.start" -> {
                if (chats.containsKey(live)) {
                    streamed[live] = StringBuilder()
                    _running.update { it + live }
                }
                started(live)
            }
            "message.delta" -> streamed[live]?.append(event.payload.string("text").orEmpty())
            "message.complete" -> {
                // An end never lets a hold go. One still submitting holds until its submit returns; one
                // submitted awaits a start, so an end before it is of an earlier turn, e.g. one interrupted
                // for the answer asked for again, and a start that never came is the watchdog's to find.
                val stored = chats[live] ?: return
                val text = event.payload.string("text")?.takeIf { it.isNotBlank() } ?: streamed[live]?.toString().orEmpty()
                streamed.remove(live)
                _running.update { it - live }
                val failed = event.payload.string("status") == "error"
                if (!inForeground || visibleChat != stored) {
                    val shown = if (failed) event.payload.string("error") ?: context.getString(R.string.notify_model_error) else stripMedia(text).take(400)
                    if (shown.isNotBlank()) {
                        notifications.answer(stored, titles[stored] ?: "Hermes", shown, failed)
                        notified[stored] = System.currentTimeMillis()
                    }
                } else {
                    // The answer is on the screen as it arrives: whatever an earlier one left in the
                    // shade is read now, and the push of this turn is already accounted for.
                    read(stored)
                }
                settle()
            }
            "request.cancel" -> event.payload.string("id")?.let(notifications::cancelRequest)
            "connection.request" -> {
                if (inForeground) return
                val connection = connectionOf(event.payload) ?: return
                val services = connection.targets.joinToString(", ") { it.catalog?.display ?: it.name }.ifEmpty { context.getString(R.string.connect_title_directory) }
                notifications.connection(chats[live], connection.opId, services)
            }
            // A settled operation waits for nobody.
            "connection.update" -> if (event.payload.bool("settled") == true) event.payload.string("op_id")?.let(notifications::cancelRequest)
        }
    }

    /**
     * A turn of [live] started. A submitted hold awaited it and lets go: the turn holds the service
     * itself. A hold still submitting keeps holding until its submit returns.
     */
    private fun started(live: String) {
        synchronized(service) {
            for (hold in holds) {
                if (hold.session != live) continue
                if (hold.submittedAt != null) holds.remove(hold) else hold.started = true
            }
        }
    }

    private fun onRequest(request: ServerRequest) {
        if (inForeground) return
        val stored = chats[request.params.string("session_id") ?: return]
        when (request.method) {
            "approval" -> {
                val approval = approvalOf(request)
                val title = when (val kind = approval.kind) {
                    ApprovalKind.Command -> context.getString(R.string.notify_approval_title)
                    is ApprovalKind.Tool -> context.getString(R.string.notify_approval_tool_title, kind.name)
                    ApprovalKind.Mcp -> context.getString(R.string.notify_approval_mcp_title)
                }
                notifications.request(stored, request.id, title, approval.summary, approval.choices)
            }
            "clarify", "sudo", "secret" -> notifications.request(
                stored,
                request.id,
                context.getString(R.string.notify_waiting_title),
                context.getString(R.string.notify_waiting_text),
                emptyList(),
            )
        }
    }

    /** Answers an approval from the notification. */
    fun answer(requestId: String, choice: String) {
        val socket = connection.value?.socket ?: return
        socket.respond(requestId, kotlinx.serialization.json.buildJsonObject { put("choice", JsonPrimitive(choice)) })
        notifications.cancelRequest(requestId)
    }

    /**
     * Stops the service once nothing runs or is being submitted, unless the user keeps the connection
     * open; before the switch is read it stays, and the switch settles it once read.
     */
    fun settle() {
        synchronized(service) {
            if (_running.value.isEmpty() && holds.isEmpty() && keepConnected.value == false) HermesService.stop(context)
        }
    }

    /**
     * A turn whose end never arrived, because the socket dropped in between, must not keep the
     * service forever: while turns run, the server is asked every minute whether they still do.
     */
    private suspend fun watchdog() {
        while (true) {
            delay(60_000)
            val session = connection.value?.session ?: continue
            for (live in _running.value) {
                val stored = chats[live] ?: continue
                val active = try {
                    session.chat.get(stored)?.active
                } catch (e: Exception) {
                    null
                }
                if (active == false) {
                    Log.d(TAG, "Turn of $stored ended unseen")
                    _running.update { it - live }
                    streamed.remove(live)
                }
            }
            // A submission under way, e.g. uploading its attachments, holds the service until it returns
            // or fails; once submitted, its start is awaited a minute at most, or as long as its chat says
            // a turn runs.
            for (hold in holds) {
                val submittedAt = hold.submittedAt ?: continue
                if (now() - submittedAt < 60_000) continue
                val stored = hold.session?.let(chats::get)
                val active = stored?.let {
                    try {
                        session.chat.get(it)?.active
                    } catch (e: Exception) {
                        true
                    }
                }
                if (active != true) holds.remove(hold)
            }
            settle()
        }
    }

    private companion object {
        const val TAG = "HermesTurns"
    }
}
