package org.evsyukov.hermes.data.remote

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.server.WorkOwner
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The stock api_server channel as the [GatewayChannel] a chat speaks to.
 *
 * There is no persistent connection: a turn is a detached run, and its events are translated into
 * the gateway events the transcript reduces. The transport epoch plays the role of the socket's
 * connection number — it grows on every (re)admission of the channel, so a chat ties its join and
 * its answers to the epoch they came over, exactly as over a websocket.
 *
 * Recovery evidence stays server-derived: the submit ack is the authority's own run projection
 * (admission, generation, session), and the completed turn's row receipt is read from the raw
 * history after the row boundary captured before the send. Nothing is selected by text or time.
 */
class ApiServerGateway(
    private val channel: ApiServerChannel,
    private val owner: () -> WorkOwner,
    private val scope: CoroutineScope,
    private val strings: AppStrings,
) : GatewayChannel {

    private val _state = MutableStateFlow(SocketState.DISCONNECTED)
    override val state: StateFlow<SocketState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 1024)
    override val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val _requests = MutableSharedFlow<ServerRequest>(extraBufferCapacity = 64)
    override val requests: SharedFlow<ServerRequest> = _requests.asSharedFlow()

    private val _ready = MutableSharedFlow<String?>(replay = 1, extraBufferCapacity = 4)
    override val ready: SharedFlow<String?> = _ready.asSharedFlow()

    private val _connections = MutableStateFlow(0)
    override val connections: StateFlow<Int> = _connections.asStateFlow()

    private val _transport = MutableStateFlow(0L)
    override val transport: StateFlow<Long> = _transport.asStateFlow()

    @Volatile
    private var stopped = false
    private var connector: Job? = null
    private var epoch = 0L

    /** The run each session is on, the last seq its stream saw, and the answer text so far. */
    private val activeRuns = ConcurrentHashMap<String, String>()
    private val runSeq = ConcurrentHashMap<String, Long>()
    private val inflight = ConcurrentHashMap<String, StringBuilder>()
    private val boundaries = ConcurrentHashMap<String, Long>()
    private val admissions = ConcurrentHashMap<String, String>()
    private val streamJobs = ConcurrentHashMap<String, Job>()

    /** The controls the server holds open, by request id: the exact prompt an answer must carry. */
    private val pendingControls = ConcurrentHashMap<String, Pair<String, ApiServerChannel.Prompt>>()

    fun start() {
        if (stopped || connector?.isActive == true) return
        connector = scope.launch {
            var pause = 1_000L
            while (isActive && !stopped) {
                try {
                    channel.chats(limit = 1)
                    if (stopped) break
                    epoch += 1
                    _transport.value = epoch
                    _state.value = SocketState.READY
                    _connections.update { it + 1 }
                    _ready.tryEmit(epoch.toString())
                    return@launch
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    _state.value = SocketState.DISCONNECTED
                    delay(pause)
                    pause = (pause * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    fun stop() {
        stopped = true
        connector?.cancel()
        streamJobs.values.forEach { it.cancel() }
        streamJobs.clear()
        _state.value = SocketState.DISCONNECTED
        _transport.value = 0L
    }

    override suspend fun awaitReady() {
        if (stopped) throw IOException("Channel stopped")
        if (_state.value == SocketState.READY) return
        start()
        withTimeout(30_000) { _state.first { it == SocketState.READY } }
    }

    override suspend fun call(method: String, params: JsonObject, timeoutMillis: Long): JsonElement = when (method) {
        "session.create" -> {
            val id = channel.create()
            buildJsonObject { put("session_id", id); put("stored_session_id", id) }
        }
        "session.active_list" -> {
            val chats = channel.chats(limit = 500)
            buildJsonObject {
                put("sessions", JsonArray(chats.map { chat ->
                    buildJsonObject { put("session_key", chat.id); put("id", chat.id) }
                }))
            }
        }
        "session.resume" -> resume(params.string("session_id") ?: error("session_id is required"))
        // A REST conversation has no live handle to close; it stays on the server.
        "session.close" -> JsonObject(emptyMap())
        "session.delete" -> {
            channel.delete(params.string("session_id") ?: error("session_id is required"))
            buildJsonObject { put("ok", true) }
        }
        "session.title" -> {
            channel.rename(
                params.string("session_id") ?: error("session_id is required"),
                params.string("title").orEmpty(),
            )
            buildJsonObject { put("ok", true) }
        }
        "session.archive" -> {
            channel.archive(
                params.string("session_key") ?: error("session_key is required"),
                params.bool("archived") ?: true,
            )
            buildJsonObject { put("ok", true) }
        }
        "session.interrupt" -> {
            val sid = params.string("session_id") ?: error("session_id is required")
            activeRuns[sid]?.let { channel.stop(it) }
            buildJsonObject { put("ok", true) }
        }
        "prompt.submit" -> submit(params)
        "image.attach_bytes", "file.attach" ->
            throw RpcException(-32601, strings.get(R.string.error_channel_attachments), null)
        else -> throw RpcException(-32601, "Method not supported by the Android channel: $method", null)
    }

    override fun respond(id: String, result: JsonElement?, error: String?) {
        val (runId, prompt) = pendingControls[id] ?: return
        scope.launch {
            try {
                if (prompt.kind == "approval") {
                    val choice = if (error != null) "deny" else (result as? JsonObject)?.string("choice") ?: "deny"
                    channel.answer(runId, prompt, choice)
                    pendingControls.remove(id)
                } else if (error == null) {
                    // A cancelled clarify takes no fabricated answer: the run waits or is stopped.
                    val answers = (result as? JsonObject)?.get("answers") as? JsonObject
                    val value = (answers?.get(prompt.promptId) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    if (value != null) {
                        channel.answer(runId, prompt, value)
                        pendingControls.remove(id)
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                Log.w(TAG, "Channel control answer failed", failure)
            }
        }
    }

    private suspend fun submit(params: JsonObject): JsonElement {
        val sid = params.string("session_id") ?: error("session_id is required")
        val text = params.string("text").orEmpty()
        // The row boundary is captured before the run is admitted: the turn's rows are the ones after it.
        val boundary = channel.newestRowId(sid) ?: 0L
        val runId = channel.startRun(sid, text, UUID.randomUUID().toString())
        activeRuns[sid] = runId
        boundaries[runId] = boundary
        inflight[sid] = StringBuilder()
        runSeq.remove(sid)
        val projection = channel.runStatusOrNull(runId)
        val admission = projection?.string("admission_id")
        if (admission != null) admissions[runId] = admission
        startStream(sid, runId)
        return if (admission != null) {
            buildJsonObject {
                put("admission_id", admission)
                put("status", when (projection?.string("status")) {
                    "queued" -> "queued"
                    "running", "waiting_for_approval" -> "started"
                    else -> "terminal"
                })
                put("ref", buildJsonObject { put("session_id", sid) })
                put("authority_epoch", projection?.long("execution_generation") ?: 1L)
            }
        } else {
            // No authority projection: no verifiable admission, so the local copy is retained, not bound.
            buildJsonObject { put("run_id", runId) }
        }
    }

    private suspend fun resume(sid: String): JsonObject {
        val runId = activeRuns[sid]
        var running = false
        var open = JsonArray(emptyList())
        if (runId != null) {
            val projection = channel.runStatusOrNull(runId)
            if (projection == null) {
                activeRuns.remove(sid)
            } else {
                running = projection.string("status") in LIVE_RUN_STATUSES
                open = JsonArray((projection["pending_controls"] as? JsonArray).orEmpty().mapNotNull { control ->
                    val obj = control as? JsonObject ?: return@mapNotNull null
                    val promptId = obj.string("prompt_id") ?: return@mapNotNull null
                    val kind = obj.string("kind") ?: return@mapNotNull null
                    buildJsonObject {
                        put("id", promptId)
                        put("method", kind)
                        put("params", controlParams(sid, obj))
                    }
                })
                if (running) {
                    if (streamJobs[sid]?.isActive != true) startStream(sid, runId)
                } else {
                    activeRuns.remove(sid)
                    inflight.remove(sid)
                }
            }
        }
        return buildJsonObject {
            put("session_id", sid)
            put("stored_session_id", sid)
            put("running", if (running) 1 else 0)
            put("inflight", buildJsonObject { put("assistant", inflight[sid]?.toString().orEmpty()) })
            put("open_requests", open)
        }
    }

    private fun startStream(sid: String, runId: String) {
        streamJobs[sid]?.cancel()
        streamJobs[sid] = scope.launch {
            var started = false
            var terminal = false
            try {
                while (isActive && !stopped && !terminal) {
                    try {
                        channel.runEvents(runId, runSeq[sid]) { event, seq ->
                            if (seq >= 0) runSeq[sid] = seq
                            when (event) {
                                is ApiServerChannel.Event.Delta -> {
                                    if (!started) {
                                        started = true
                                        emit(sid, "message.start", buildJsonObject { })
                                    }
                                    inflight.getOrPut(sid) { StringBuilder() }.append(event.text)
                                    emit(sid, "message.delta", buildJsonObject { put("text", event.text) })
                                }
                                is ApiServerChannel.Event.Commentary ->
                                    emit(sid, "message.interim", buildJsonObject { put("text", event.text) })
                                is ApiServerChannel.Event.Working ->
                                    emit(sid, "status.update", buildJsonObject {
                                        put("text", event.tool ?: strings.get(R.string.chat_work_reasoning))
                                    })
                                is ApiServerChannel.Event.Control -> {
                                    pendingControls[event.prompt.promptId] = runId to event.prompt
                                    _requests.tryEmit(
                                        ServerRequest(event.prompt.promptId, event.prompt.kind, controlParams(sid, event.prompt), _transport.value),
                                    )
                                }
                                is ApiServerChannel.Event.Settled -> {
                                    event.requestId?.let { pendingControls.remove(it) }
                                    emit(sid, "request.cancel", buildJsonObject { put("request_id", event.requestId.orEmpty()) })
                                }
                                is ApiServerChannel.Event.Completed -> {
                                    terminal = true
                                    completeTurn(sid, runId, event.text)
                                }
                                is ApiServerChannel.Event.Failed -> {
                                    terminal = true
                                    failTurn(sid, runId, event.message)
                                }
                                // A lost replay tail is repaired from the raw history at the turn's end.
                                is ApiServerChannel.Event.Truncated -> Unit
                                ApiServerChannel.Event.Finished -> Unit
                            }
                        }
                        if (!terminal) {
                            val projection = channel.runStatusOrNull(runId)
                            when {
                                projection == null -> {
                                    terminal = true
                                    failTurn(sid, runId, strings.get(R.string.error_channel_run_lost))
                                }
                                projection.string("status") !in LIVE_RUN_STATUSES -> {
                                    terminal = true
                                    completeTurn(sid, runId, projection.string("output").orEmpty())
                                }
                                else -> delay(500)
                            }
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (error: IOException) {
                        if (!isActive || stopped) break
                        // The stream dropped: a new epoch makes the chat rejoin and reattach after its last seq.
                        lose()
                        break
                    }
                }
            } finally {
                streamJobs.remove(sid)
            }
        }
    }

    private suspend fun completeTurn(sid: String, runId: String, text: String) {
        activeRuns.remove(sid)
        inflight.remove(sid)
        val boundary = boundaries.remove(runId) ?: 0L
        val admission = admissions.remove(runId)
        val receipt = runCatching { persistedTurn(sid, boundary) }.getOrNull()
        val payload = buildJsonObject {
            put("text", text)
            put("status", "complete")
            put("partial", false)
            admission?.let { put("admission_id", it) }
            receipt?.let { put("persisted_turn", it) }
        }
        emit(sid, "message.complete", payload)
    }

    private suspend fun failTurn(sid: String, runId: String, message: String) {
        activeRuns.remove(sid)
        inflight.remove(sid)
        boundaries.remove(runId)
        admissions.remove(runId)
        emit(sid, "error", buildJsonObject { put("message", message) })
    }

    /**
     * The turn's raw rows, addressed by the boundary captured before the send — never by text or
     * time. Rows the server injected for the model (internal notifications) are not the prompt.
     */
    private suspend fun persistedTurn(sid: String, boundary: Long): JsonObject? {
        val collected = ArrayList<ApiServerChannel.Row>()
        var offset = 0
        while (true) {
            val page = channel.history(sid, limit = 500, offset = offset, order = "latest")
            if (page.rows.isEmpty()) break
            collected.addAll(0, page.rows)
            val oldest = page.rows.first().id?.toLongOrNull() ?: 0L
            if (oldest <= boundary || !page.hasMore) break
            offset += page.rows.size
        }
        val fresh = collected
            .filter { (it.id?.toLongOrNull() ?: 0L) > boundary }
            .filterNot { it.role == "user" && it.displayKind != null }
            .sortedBy { it.id?.toLongOrNull() ?: 0L }
        val user = fresh.firstOrNull { it.role == "user" } ?: return null
        val final = fresh.lastOrNull { it.role == "assistant" } ?: return null
        val userRow = user.id?.toLongOrNull() ?: return null
        val finalRow = final.id?.toLongOrNull() ?: return null
        val rowIds = fresh.mapNotNull { it.id?.toLongOrNull() }
        if (rowIds.isEmpty() || rowIds.first() != userRow || rowIds.last() != finalRow) return null
        return buildJsonObject {
            put("complete", true)
            put("user_row_id", userRow)
            put("final_assistant_row_id", finalRow)
            put("row_ids", JsonArray(rowIds.map { JsonPrimitive(it) }))
            put("user_row_ids", JsonArray(listOf(JsonPrimitive(userRow))))
        }
    }

    private fun controlParams(sid: String, prompt: ApiServerChannel.Prompt): JsonObject = buildJsonObject {
        put("session_id", sid)
        if (prompt.kind == "clarify") {
            put("questions", JsonArray(listOf(buildJsonObject {
                put("qid", prompt.promptId)
                put("question", prompt.question)
                put("choices", JsonArray(prompt.choices.map { JsonPrimitive(it) }))
            })))
        } else {
            put("command", prompt.question)
            put("description", prompt.question)
            put("choices", JsonArray(prompt.choices.map { JsonPrimitive(it) }))
        }
    }

    private fun controlParams(sid: String, obj: JsonObject): JsonObject {
        val prompt = ApiServerChannel.Prompt(
            kind = obj.string("kind").orEmpty(),
            promptId = obj.string("prompt_id").orEmpty(),
            generation = obj.long("execution_generation") ?: 0L,
            question = obj.string("question") ?: obj.string("description") ?: obj.string("command").orEmpty(),
            choices = (obj["choices"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            multi = obj.bool("multi_select") ?: false,
        )
        return controlParams(sid, prompt)
    }

    private suspend fun emit(sid: String, type: String, payload: JsonObject) {
        _events.emit(GatewayEvent(type, sid, runSeq[sid], payload, _transport.value, owner()))
    }

    /** The channel lost its stream: the next admission is a new epoch, and chats rejoin over it. */
    private fun lose() {
        _state.value = SocketState.DISCONNECTED
        _transport.value = 0L
        connector = null
        start()
    }

    private companion object {
        const val TAG = "HermesChannel"
        val LIVE_RUN_STATUSES = setOf("queued", "running", "waiting_for_approval")
    }
}
