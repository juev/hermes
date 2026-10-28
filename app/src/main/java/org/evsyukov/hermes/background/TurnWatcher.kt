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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.evsyukov.hermes.Connection
import org.evsyukov.hermes.chat.stripMedia
import org.evsyukov.hermes.data.remote.GatewayEvent
import org.evsyukov.hermes.data.remote.ServerRequest
import org.evsyukov.hermes.data.remote.string
import java.util.concurrent.ConcurrentHashMap

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
    private val keepConnected: () -> Boolean,
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

    init {
        scope.launch {
            connection.collectLatest { c ->
                if (c == null) return@collectLatest
                launch { c.socket.events.collect(::onEvent) }
                c.socket.requests.collect(::onRequest)
            }
        }
        scope.launch { watchdog() }
    }

    /** Called when a chat of the app joins its live session. */
    fun bind(liveId: String, storedId: String, title: String) {
        chats[liveId] = storedId
        if (title.isNotBlank()) titles[storedId] = title
    }

    /** The app is about to start a turn; the service starts now, while the app may still start it. */
    fun turnStarting() = HermesService.start(context, "Hermes отвечает…")

    private fun onEvent(event: GatewayEvent) {
        val live = event.sessionId ?: return
        when (event.type) {
            "session.title" -> event.payload.string("session_id")?.let { stored ->
                event.payload.string("title")?.let { titles[stored] = it }
            }
            "message.start" -> if (chats.containsKey(live)) {
                streamed[live] = StringBuilder()
                _running.update { it + live }
            }
            "message.delta" -> streamed[live]?.append(event.payload.string("text").orEmpty())
            "message.complete" -> {
                val stored = chats[live] ?: return
                val text = event.payload.string("text")?.takeIf { it.isNotBlank() } ?: streamed[live]?.toString().orEmpty()
                streamed.remove(live)
                _running.update { it - live }
                val failed = event.payload.string("status") == "error"
                if (!inForeground || visibleChat != stored) {
                    val shown = if (failed) event.payload.string("error") ?: "ошибка модели" else stripMedia(text).take(400)
                    if (shown.isNotBlank()) notifications.answer(stored, titles[stored] ?: "Hermes", shown, failed)
                }
                settle()
            }
            "request.cancel" -> event.payload.string("id")?.let(notifications::cancelRequest)
        }
    }

    private fun onRequest(request: ServerRequest) {
        if (inForeground) return
        val stored = chats[request.params.string("session_id") ?: return]
        when (request.method) {
            "approval" -> notifications.request(
                stored,
                request.id,
                "Разрешить команду?",
                request.params.string("command") ?: request.params.string("description").orEmpty(),
                (request.params["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: listOf("once", "deny"),
            )
            "clarify", "sudo", "secret" -> notifications.request(stored, request.id, "Агент ждёт ответа", "Откройте чат, чтобы ответить", emptyList())
        }
    }

    /** Answers an approval from the notification. */
    fun answer(requestId: String, choice: String) {
        val socket = connection.value?.socket ?: return
        socket.respond(requestId, kotlinx.serialization.json.buildJsonObject { put("choice", JsonPrimitive(choice)) })
        notifications.cancelRequest(requestId)
    }

    /** Stops the service once nothing runs, unless the user keeps the connection open. */
    fun settle() {
        if (_running.value.isEmpty() && !keepConnected()) HermesService.stop(context)
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
            settle()
        }
    }

    private companion object {
        const val TAG = "HermesTurns"
    }
}
