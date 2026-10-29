package org.evsyukov.hermes.chat

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

    data class Approval(
        override val request: ServerRequest,
        val kind: ApprovalKind,
        val command: String,
        val description: String,
        val choices: List<String>,
    ) : Prompt
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
    /** Connection operations seen in this chat, oldest first; a settled one stays as its outcome. */
    val connections: List<ConnectionOp> = emptyList(),
) {
    /** A turn runs, and no open answer shows it yet: the chat says it is waiting under the last message. */
    val awaitingAnswer: Boolean get() = running && (items.lastOrNull() as? ChatItem.Assistant)?.streaming != true
}

/**
 * One open chat. A new chat creates its server session while it is still empty, so the agent is built
 * before the first message; the server keeps no chat for it until that message, and leaving the empty
 * chat closes the session. An existing chat shows its stored history at once and joins the live
 * session in the background, so a turn that runs elsewhere keeps streaming here.
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

    /** One join at a time, so a message sent while the draft is being created does not create a second session. */
    private val joining = Mutex()

    /** The answer of `session.create` for a new chat that has not been sent; guarded by [joining]. */
    private var draft: JsonObject? = null

    @Volatile
    private var closed = false
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
                create()
            } catch (e: Exception) {
                // The first message creates the session then.
                Log.w(TAG, "Draft session not created", e)
                null
            }
        }
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
        return joining.withLock { liveId ?: join(starting) }
    }

    private suspend fun join(starting: Boolean): String {
        val stored = _state.value.storedId
        val result = if (stored == null) {
            draft?.also { draft = null } ?: create()
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
        // A card left open while the chat was away comes back with the server's deadline; one the
        // server no longer has open ended while the socket was down and takes no answers.
        val pending = connectionOf(result["pending_connection"] as? JsonObject)
        _state.update { state -> state.copy(connections = state.connections.map { if (it.settled || it.opId == pending?.opId) it else it.copy(settled = true) }) }
        pending?.let(::showConnection)
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
        val again = lastUser.rewritten(replacement, "local${keys.incrementAndGet()}")
        val text = again.sent
        var hold: TurnHold? = null
        scope.launch {
            try {
                val live = ensureLive()
                if (_state.value.running) socket.call("session.interrupt", buildJsonObject { put("session_id", live) })
                socket.call("session.undo", buildJsonObject { put("session_id", live); put("intent", "retry") })
                val index = _state.value.items.indexOfLast { it is ChatItem.User }
                val kept = if (index >= 0) _state.value.items.subList(0, index) else _state.value.items
                _state.update { it.copy(items = kept + again, running = true, error = null) }
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

    /** A newer snapshot of a known operation replaces it; a new operation joins the chat. */
    private fun showConnection(connection: ConnectionOp) = _state.update { state ->
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
                _state.update { it.copy(error = e.message ?: strings.get(R.string.error_failed)) }
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
                _state.update { it.copy(error = strings.get(R.string.connect_retry_failed, name)) }
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

    private fun replaceTarget(opId: String, name: String, change: (ConnectionTarget) -> ConnectionTarget) = _state.update { state ->
        state.copy(
            connections = state.connections.map { c ->
                if (c.opId != opId) c else c.copy(targets = c.targets.map { if (it.name == name) change(it) else it })
            },
        )
    }

    /** The user opened the sign-in link of a row; it waits for the browser now. */
    fun linkOpened(opId: String, name: String) = _state.update { state ->
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
                    _state.update { state -> state.copy(connections = state.connections.map { it.applying(status) }) }
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
            "connection.request" -> connectionOf(event.payload)?.let(::showConnection)
            "connection.update" -> _state.update { state -> state.copy(connections = state.connections.map { it.applying(event.payload) }) }
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
            "approval" -> approvalOf(request)
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
