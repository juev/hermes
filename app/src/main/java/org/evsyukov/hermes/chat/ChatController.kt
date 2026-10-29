package org.evsyukov.hermes.chat

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.R
import org.evsyukov.hermes.TIMING_TAG
import org.evsyukov.hermes.background.TurnHold
import org.evsyukov.hermes.data.remote.GatewayEvent
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.remote.RpcException
import org.evsyukov.hermes.data.remote.ServerRequest
import org.evsyukov.hermes.data.remote.SessionUsage
import org.evsyukov.hermes.data.remote.SocketState
import org.evsyukov.hermes.data.remote.bool
import org.evsyukov.hermes.data.remote.string
import org.evsyukov.hermes.data.server.ServerSession
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/** A file picked on the phone and not sent yet. */
class LocalAttachment(val name: String, val mime: String?, val bytes: ByteArray) {
    val image: Boolean get() = mime?.startsWith("image/") == true
}

/** A question of the agent that waits for the user: approve a command, answer, give a secret. */
sealed interface Prompt {
    val request: ServerRequest

    data class Approval(override val request: ServerRequest, val command: String, val description: String, val choices: List<String>) : Prompt
    data class Clarify(override val request: ServerRequest, val questions: List<Question>) : Prompt
    data class Secret(override val request: ServerRequest, val title: String, val sudo: Boolean) : Prompt

    data class Question(val id: String, val text: String, val choices: List<String>)
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
    val status: String? = null,
    /** A message waits for the socket to connect. */
    val connecting: Boolean = false,
    /** An expensive model waits for a yes: provider, model and the server's warning. */
    val modelConfirm: Triple<String, String, String>? = null,
    /** Tokens and cost of the chat so far, as of its last finished turn. */
    val usage: SessionUsage? = null,
) {
    /** A turn runs, and no open answer shows it yet: the chat says it is waiting under the last message. */
    val awaitingAnswer: Boolean get() = running && (items.lastOrNull() as? ChatItem.Assistant)?.streaming != true
}

/**
 * One open chat. A new chat lives only in the app until its first message; the server session is
 * created then. An existing chat shows its stored history at once and joins the live session in
 * the background, so a turn that runs elsewhere keeps streaming here.
 */
class ChatController(
    private val session: ServerSession,
    private val socket: GatewaySocket,
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
) {
    private val _state = MutableStateFlow(ChatState(storedId = storedId))
    val state: StateFlow<ChatState> = _state.asStateFlow()

    @Volatile
    private var liveId: String? = null

    /** The model picked before the first message; the session is created with it. */
    private var draftModel: Pair<String, String>? = null
    private val keys = AtomicLong()
    private val jobs = mutableListOf<Job>()

    /** When the last message was sent, for the log of its first reply; 0 once the reply came. */
    @Volatile
    private var sentAt = 0L

    /**
     * A turn was sent and has not started. The server may report the session idle meanwhile: a new
     * session announces its agent once built, and that can happen before the prompt reaches it.
     */
    @Volatile
    private var awaitingStart = false

    init {
        jobs += scope.launch { socket.events.collect(::onEvent) }
        jobs += scope.launch { socket.requests.collect(::onRequest) }
        // After a reconnect the old live id may be gone: join the session again.
        jobs += scope.launch {
            socket.ready.drop(1).collect {
                if (liveId != null) {
                    liveId = null
                    reload()
                }
            }
        }
        if (storedId != null) reload()
    }

    fun close() {
        jobs.forEach { it.cancel() }
    }

    /**
     * Reads the stored history, then joins the live session to pick up a turn in progress. A chat
     * opened afresh first shows the history the phone keeps, so it does not wait for the server.
     */
    fun reload() {
        val id = _state.value.storedId ?: return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            if (_state.value.items.isEmpty()) showCached(id)
            try {
                val summary = session.chat.get(id)
                val history = transcriptOf(session.chat.messages(id))
                _state.update { it.copy(items = history, title = summary?.title.orEmpty(), model = summary?.model, usage = summary?.usage, loading = false) }
                if (summary?.active == true) ensureLive()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    private suspend fun showCached(id: String) {
        val cached = session.chat.cached ?: return
        try {
            val history = transcriptOf(cached.messages(id))
            val summary = runCatching { cached.get(id) }.getOrNull()
            _state.update { if (it.items.isEmpty()) it.copy(items = history, title = summary?.title.orEmpty(), model = summary?.model, usage = summary?.usage) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not on the phone yet, or unreadable: the server's answer fills the chat.
        }
    }

    /** Joins the live session; [starting] a turn, the chat stays running whatever the session was doing. */
    private suspend fun ensureLive(starting: Boolean = false): String {
        liveId?.let { return it }
        val stored = _state.value.storedId
        val result = if (stored == null) {
            socket.call(
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
        } else {
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
        val live = result.string("session_id") ?: error("No session_id in the answer")
        liveId = live
        val storedId = result.string("stored_session_id") ?: stored
        val info = result["info"] as? JsonObject
        val running = starting || (result.bool("running") ?: info?.bool("running") ?: false)
        // A turn under way when the chat opens goes on in its answer, with what streamed while the chat was away.
        val resumed = running && !starting
        val streamed = (result["inflight"] as? JsonObject)?.string("assistant").orEmpty()
        _state.update {
            it.copy(
                storedId = storedId ?: it.storedId,
                running = running,
                model = info?.string("model") ?: it.model,
                items = if (resumed) resumeTurn(it.items, streamed) { "a${keys.incrementAndGet()}" } else it.items,
            )
        }
        if (stored == null && storedId != null) onSessionCreated(storedId)
        if (storedId != null) onLive(live, storedId, _state.value.title)
        (result["open_requests"] as? JsonArray)?.forEach { request ->
            val obj = request as? JsonObject ?: return@forEach
            val id = obj.string("id") ?: return@forEach
            onRequest(ServerRequest(id, obj.string("method").orEmpty(), obj["params"] as? JsonObject ?: JsonObject(emptyMap())))
        }
        return live
    }

    /** Runs [block] on the live session; a session the server dropped is joined again once. */
    private suspend fun <T> withLive(starting: Boolean = false, block: suspend (String) -> T): T = try {
        block(ensureLive(starting))
    } catch (e: RpcException) {
        if (!e.sessionGone()) throw e
        liveId = null
        block(ensureLive(starting))
    }

    fun send(text: String, attachments: List<LocalAttachment>) {
        if (text.isBlank() && attachments.isEmpty()) return
        val shown = attachments.map { Attachment(it.name, it.name, it.image) }
        _state.update { it.copy(items = it.items + ChatItem.User("local${keys.incrementAndGet()}", text, shown), running = true, error = null) }
        awaitingStart = true
        val hold = onTurnStart()
        val started = System.currentTimeMillis().also { sentAt = it }
        scope.launch {
            try {
                // A message sent before the socket is up says so instead of waiting in silence.
                if (socket.state.value != SocketState.READY) _state.update { it.copy(connecting = true) }
                try {
                    socket.awaitReady()
                } finally {
                    _state.update { it.copy(connecting = false) }
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
                    socket.call("prompt.submit", buildJsonObject { put("session_id", live); put("text", prompt) })
                    hold.submitted()
                    Log.i(
                        TIMING_TAG,
                        "Sent: socket ready after $socketMillis ms, session after $sessionMillis ms, " +
                            "prompt accepted after ${System.currentTimeMillis() - started} ms",
                    )
                }
            } catch (e: Exception) {
                hold.release()
                awaitingStart = false
                Log.w(TAG, "Send failed", e)
                _state.update { it.copy(running = false, error = e.message ?: strings.get(R.string.error_send_failed)) }
            }
        }.invokeOnCompletion { cause -> if (cause != null) hold.release() }
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
                _state.update { it.copy(error = e.message) }
            }
            awaitingStart = false
            _state.update { it.copy(items = closeOpen(it.items, interrupted = true), running = false) }
        }
    }

    /** Asks for the last answer again: the server drops the last turn and the same text goes again. */
    fun regenerate() = rewriteLast(null)

    /** Replaces the last user message with [text] and answers it anew. */
    fun editLast(text: String) = rewriteLast(text)

    private fun rewriteLast(replacement: String?) {
        val lastUser = _state.value.items.lastOrNull { it is ChatItem.User } as? ChatItem.User ?: return
        val text = replacement ?: lastUser.text
        var hold: TurnHold? = null
        scope.launch {
            try {
                val live = ensureLive()
                if (_state.value.running) socket.call("session.interrupt", buildJsonObject { put("session_id", live) })
                socket.call("session.undo", buildJsonObject { put("session_id", live); put("intent", "retry") })
                val index = _state.value.items.indexOfLast { it is ChatItem.User }
                val kept = if (index >= 0) _state.value.items.subList(0, index) else _state.value.items
                _state.update { it.copy(items = kept + lastUser.copy(key = "local${keys.incrementAndGet()}", text = text), running = true, error = null) }
                awaitingStart = true
                hold = onTurnStart().also { it.live(live) }
                socket.call("prompt.submit", buildJsonObject { put("session_id", live); put("text", text) })
                hold?.submitted()
            } catch (e: Exception) {
                hold?.release()
                awaitingStart = false
                _state.update { it.copy(running = false, error = e.message ?: strings.get(R.string.error_failed)) }
            }
        }.invokeOnCompletion { cause -> if (cause != null) hold?.release() }
    }

    fun rename(title: String) {
        val id = _state.value.storedId ?: return
        scope.launch {
            try {
                // A rename touches no pin.
                ChatMutations(session.chat, socket, liveOf = { stored -> liveId.takeIf { stored == id } }, pinLocally = { _, _ -> }).rename(id, title)
                _state.update { it.copy(title = title) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Switches the model of this chat; a running turn finishes on the old one. */
    fun chooseModel(provider: String, model: String, confirmed: Boolean = false) {
        _state.update { it.copy(modelConfirm = null) }
        if (liveId == null && _state.value.storedId == null) {
            draftModel = provider to model
            _state.update { it.copy(model = model) }
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
                    _state.update { it.copy(modelConfirm = Triple(provider, model, result.string("confirm_message") ?: strings.get(R.string.chat_model_expensive))) }
                } else {
                    _state.update { it.copy(model = model, error = result?.string("warning")?.takeIf { w -> w.isNotBlank() }) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun dismissModelConfirm() = _state.update { it.copy(modelConfirm = null) }

    fun answerApproval(choice: String) = answer { buildJsonObject { put("choice", choice) } }

    fun answerClarify(answers: Map<String, String?>) = answer {
        buildJsonObject {
            put("answers", JsonObject(answers.mapValues { (_, v) -> if (v == null) kotlinx.serialization.json.JsonNull else JsonPrimitive(v) }))
            put("outcome", "submitted")
        }
    }

    fun answerSecret(value: String?) {
        val prompt = _state.value.prompt ?: return
        if (value == null) {
            socket.respond(prompt.request.id, null, error = "cancelled")
            _state.update { it.copy(prompt = null) }
        } else {
            answer { buildJsonObject { put("value", value) } }
        }
    }

    fun cancelPrompt() {
        val prompt = _state.value.prompt ?: return
        when (prompt) {
            is Prompt.Approval -> answerApproval("deny")
            is Prompt.Clarify -> answer { buildJsonObject { put("outcome", "cancelled") } }
            is Prompt.Secret -> answerSecret(null)
        }
    }

    private fun answer(result: () -> JsonElement) {
        val prompt = _state.value.prompt ?: return
        socket.respond(prompt.request.id, result())
        _state.update { it.copy(prompt = null) }
    }

    private fun onEvent(event: GatewayEvent) {
        val live = liveId
        when (event.type) {
            "session.title" -> if (event.payload.string("session_id") == _state.value.storedId || event.sessionId == live) {
                _state.update { it.copy(title = event.payload.string("title").orEmpty()) }
            }
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
        when (event.type) {
            "message.start" -> _state.update { it.copy(running = true, status = null) }
            "message.complete" -> {
                _state.update { it.copy(running = false, status = null) }
                refreshUsage()
            }
            "session.info" -> _state.update { state ->
                // Idle before the sent turn started is the state the session had before the prompt, not the end of the turn.
                val running = event.payload.bool("running")?.takeUnless { !it && awaitingStart }
                state.copy(running = running ?: state.running, model = event.payload.string("model") ?: state.model)
            }
            "status.update" -> _state.update { it.copy(status = event.payload.string("text")?.takeIf { t -> t.isNotBlank() }) }
            "thinking.delta" -> _state.update { it.copy(status = strings.get(R.string.chat_thinking)) }
            "error" -> _state.update { it.copy(error = event.payload.string("message"), running = false) }
            "request.cancel" -> _state.update { state ->
                if (state.prompt?.request?.id == event.payload.string("id")) state.copy(prompt = null) else state
            }
        }
        _state.update { it.copy(items = reduce(it.items, event) { "a${keys.incrementAndGet()}" }) }
    }

    /**
     * The server totals tokens and cost in the stored chat when a turn ends; the socket reports tokens
     * alone, counted only since the session was joined.
     */
    private fun refreshUsage() {
        val id = _state.value.storedId ?: return
        scope.launch {
            try {
                session.chat.get(id)?.usage?.let { usage -> _state.update { it.copy(usage = usage) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The last known totals stay; the next turn or opening the chat reads them again.
                Log.w(TAG, "Usage not read", e)
            }
        }
    }

    private fun onRequest(request: ServerRequest) {
        val live = liveId
        val sessionId = request.params.string("session_id")
        if (live == null || sessionId != live) return
        val params = request.params
        val prompt = when (request.method) {
            "approval" -> Prompt.Approval(
                request,
                command = params.string("command").orEmpty(),
                description = params.string("description") ?: params.string("tool_name").orEmpty(),
                choices = (params["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: listOf("once", "deny"),
            )
            "clarify" -> Prompt.Clarify(
                request,
                (params["questions"] as? JsonArray)?.mapNotNull { q ->
                    val obj = q as? JsonObject ?: return@mapNotNull null
                    Prompt.Question(
                        id = obj.string("qid").orEmpty(),
                        text = obj.string("question").orEmpty(),
                        choices = (obj["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                    )
                }.orEmpty(),
            )
            "sudo" -> Prompt.Secret(request, strings.get(R.string.chat_sudo_password, params.string("command").orEmpty()), sudo = true)
            "secret" -> Prompt.Secret(request, params.string("prompt") ?: strings.get(R.string.chat_secret_value, params.string("env_var").orEmpty()), sudo = false)
            else -> {
                // Desktop-only questions (window, terminal, theme): saying no at once keeps the agent from waiting.
                socket.respond(request.id, null, error = "Method not supported by the Android client")
                null
            }
        }
        if (prompt != null) _state.update { it.copy(prompt = prompt) }
    }

    private companion object {
        const val TAG = "HermesChat"
    }
}

/** True when an RPC error says the live session is gone and a resume by the stored id will help. */
fun RpcException.sessionGone(): Boolean = code == 4001
