package org.evsyukov.hermes.data.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.Tokens
import org.evsyukov.hermes.data.server.WorkOwner
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The adapter speaks the gateway contract ChatController reduces: a submit is admitted as a detached
 * run and answered with the authority's own projection, the run's events become transcript events,
 * and the completed turn carries a row receipt read from the raw history after the send boundary.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ApiServerGatewayTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val strings = AppStrings(app.resources)
    private val server = MockStockServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var gateway: ApiServerGateway
    private val events = CopyOnWriteArrayList<GatewayEvent>()
    private val requests = CopyOnWriteArrayList<ServerRequest>()

    @Before
    fun setUp() {
        server.start()
        val http = HermesHttp(
            base = server.server.url("/"),
            client = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build(),
            tokens = { Tokens("fixture-key") },
            strings = strings,
        )
        gateway = ApiServerGateway(ApiServerChannel(http), { WorkOwner("profile-1", 7) }, scope, strings)
        scope.launch { gateway.events.collect { events += it } }
        scope.launch { gateway.requests.collect { requests += it } }
    }

    @After
    fun tearDown() {
        gateway.stop()
        scope.cancel()
        runCatching { server.close() }
    }

    private fun json(body: String): MockResponse =
        MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private fun sse(body: String): MockResponse =
        MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(body).build()

    private suspend fun ready() = gateway.awaitReady()

    private fun waitEvent(type: String): GatewayEvent = runBlocking {
        withTimeout(10_000) {
            while (events.none { it.type == type }) kotlinx.coroutines.delay(20)
            events.first { it.type == type }
        }
    }

    @Test
    fun `a submit is admitted as a run and answered with the authority projection`() = runBlocking<Unit> {
        server.historyRows = listOf(row(10, "user", "old"), row(11, "assistant", "old answer"))
        server.afterRunRows = listOf(row(10, "user", "old"), row(11, "assistant", "old answer"), row(20, "user", "hello"), row(21, "assistant", "Hi"))
        server.runProjection = """{"run_id":"run_1","status":"running","session_id":"api_1","admission_id":"adm_1","execution_generation":3,"pending_controls":[]}"""
        server.runSse = listOf(
            sse(
                """
                id: 1
                event: message.delta
                data: {"run_id":"run_1","delta":"Hi"}

                id: 2
                event: run.completed
                data: {"run_id":"run_1","output":"Hi"}

                """.trimIndent(),
            ),
        )
        ready()

        val ack = gateway.call("prompt.submit", buildJsonObject { put("session_id", "api_1"); put("text", "hello") }).jsonObject

        assertThat(ack.string("admission_id")).isEqualTo("adm_1")
        assertThat(ack.string("status")).isEqualTo("started")
        assertThat((ack["ref"] as JsonObject).string("session_id")).isEqualTo("api_1")
        assertThat(ack.long("authority_epoch")).isEqualTo(3)
        val start = server.requests.first { it.method == "POST" && it.url.encodedPath == "/v1/runs" }
        assertThat(start.headers["Idempotency-Key"]).isNotEmpty()
        assertThat(start.body!!.utf8()).contains("\"session_id\":\"api_1\"")
        assertThat(start.body!!.utf8()).contains("\"input\":\"hello\"")

        val complete = waitEvent("message.complete")
        assertThat(events.map { it.type }).containsAtLeast("message.start", "message.delta", "message.complete").inOrder()
        assertThat(complete.payload.string("text")).isEqualTo("Hi")
        assertThat(complete.payload.string("admission_id")).isEqualTo("adm_1")
        val receipt = complete.payload["persisted_turn"]!!.jsonObject
        assertThat(receipt.long("user_row_id")).isEqualTo(20)
        assertThat(receipt.long("final_assistant_row_id")).isEqualTo(21)
        assertThat((receipt["row_ids"] as JsonArray).map { it.toString() }).containsExactly("20", "21").inOrder()
        assertThat(complete.transport).isEqualTo(gateway.transport.value)
        assertThat(complete.owner).isEqualTo(WorkOwner("profile-1", 7))
    }

    @Test
    fun `a resume reports the running turn, its text and its open controls`() = runBlocking<Unit> {
        server.historyRows = listOf(row(10, "user", "old"))
        server.afterRunRows = server.historyRows
        server.runProjection = """{"run_id":"run_1","status":"running","session_id":"api_1","admission_id":"adm_1","execution_generation":3,
            "pending_controls":[{"kind":"clarify","prompt_id":"cl_1","execution_generation":3,"question":"Какой сервер?","choices":["ams","loffe"],"multi_select":false}]}"""
        // The first stream delivers a delta and closes; the run is still running, so the adapter
        // reattaches after its last seq and keeps waiting on an empty stream.
        server.runSse = listOf(
            sse(
                """
                id: 1
                event: message.delta
                data: {"run_id":"run_1","delta":"partial"}

                """.trimIndent(),
            ),
        )
        ready()
        gateway.call("prompt.submit", buildJsonObject { put("session_id", "api_1"); put("text", "hello") })
        waitEvent("message.delta")

        val answer = gateway.call("session.resume", buildJsonObject { put("session_id", "api_1") }).jsonObject

        assertThat(answer.string("session_id")).isEqualTo("api_1")
        assertThat(answer.long("running")).isEqualTo(1)
        assertThat((answer["inflight"] as JsonObject).string("assistant")).isEqualTo("partial")
        val open = answer["open_requests"] as JsonArray
        assertThat(open).hasSize(1)
        val listed = open.single().jsonObject
        assertThat(listed.string("id")).isEqualTo("cl_1")
        assertThat(listed.string("method")).isEqualTo("clarify")
        val questions = (listed["params"] as JsonObject)["questions"] as JsonArray
        assertThat(questions.single().jsonObject.string("qid")).isEqualTo("cl_1")
        // The reattach asked for the replay after the last seq it saw.
        withTimeout(10_000) {
            while (server.requests.none { it.url.encodedPath == "/v1/runs/run_1/events" && it.headers["Last-Event-ID"] == "1" }) {
                kotlinx.coroutines.delay(20)
            }
        }
    }

    @Test
    fun `a clarify card is answered with the exact generation body`() = runBlocking<Unit> {
        server.historyRows = listOf(row(10, "user", "old"))
        server.afterRunRows = server.historyRows
        server.runProjection = """{"run_id":"run_1","status":"running","session_id":"api_1","admission_id":"adm_1","execution_generation":3,"pending_controls":[]}"""
        server.runSse = listOf(
            sse(
                """
                id: 1
                event: clarify.request
                data: {"run_id":"run_1","kind":"clarify","prompt_id":"cl_1","execution_generation":3,"question":"Какой сервер?","choices":["ams","loffe"],"multi_select":false}

                """.trimIndent(),
            ),
        )
        ready()
        gateway.call("prompt.submit", buildJsonObject { put("session_id", "api_1"); put("text", "hello") })
        withTimeout(10_000) { while (requests.isEmpty()) kotlinx.coroutines.delay(20) }

        val request = requests.single()
        assertThat(request.method).isEqualTo("clarify")
        assertThat(request.id).isEqualTo("cl_1")
        assertThat(request.params.string("session_id")).isEqualTo("api_1")

        gateway.respond(
            "cl_1",
            buildJsonObject { put("answers", buildJsonObject { put("cl_1", "loffe") }) },
            null,
        )

        withTimeout(10_000) {
            while (server.requests.none { it.url.encodedPath == "/v1/runs/run_1/clarify" }) kotlinx.coroutines.delay(20)
        }
        val answer = server.requests.first { it.url.encodedPath == "/v1/runs/run_1/clarify" }
        val body = answer.body!!.utf8()
        assertThat(body).contains("\"request_id\":\"cl_1\"")
        assertThat(body).contains("\"execution_generation\":3")
        assertThat(body).contains("\"answer\":\"loffe\"")
    }

    @Test
    fun `an approval is answered with its choice and an interrupt stops the run`() = runBlocking<Unit> {
        server.historyRows = listOf(row(10, "user", "old"))
        server.afterRunRows = server.historyRows
        server.runProjection = """{"run_id":"run_1","status":"running","session_id":"api_1","admission_id":"adm_1","execution_generation":3,"pending_controls":[]}"""
        server.runSse = listOf(
            sse(
                """
                id: 1
                event: approval.request
                data: {"run_id":"run_1","kind":"approval","prompt_id":"req_1","execution_generation":3,"description":"rm -rf /tmp/x","choices":["once","deny"]}

                """.trimIndent(),
            ),
        )
        ready()
        gateway.call("prompt.submit", buildJsonObject { put("session_id", "api_1"); put("text", "hello") })
        withTimeout(10_000) { while (requests.isEmpty()) kotlinx.coroutines.delay(20) }
        assertThat(requests.single().method).isEqualTo("approval")

        gateway.respond("req_1", buildJsonObject { put("choice", "once") }, null)
        withTimeout(10_000) {
            while (server.requests.none { it.url.encodedPath == "/v1/runs/run_1/approval" }) kotlinx.coroutines.delay(20)
        }
        assertThat(server.requests.first { it.url.encodedPath == "/v1/runs/run_1/approval" }.body!!.utf8())
            .contains("\"choice\":\"once\"")

        gateway.call("session.interrupt", buildJsonObject { put("session_id", "api_1") })
        assertThat(server.requests.any { it.url.encodedPath == "/v1/runs/run_1/stop" }).isTrue()
    }

    @Test
    fun `mutations map to the stock session routes and attachments are refused`() = runBlocking<Unit> {
        ready()
        gateway.call("session.title", buildJsonObject { put("session_id", "api_1"); put("title", "New") })
        gateway.call("session.archive", buildJsonObject { put("session_key", "api_1"); put("archived", true) })
        gateway.call("session.delete", buildJsonObject { put("session_id", "api_1") })

        val patch = server.requests.filter { it.method == "PATCH" }
        assertThat(patch.map { it.url.encodedPath }).containsExactly("/api/sessions/api_1", "/api/sessions/api_1").inOrder()
        assertThat(patch[0].body!!.utf8()).contains("\"title\":\"New\"")
        assertThat(patch[1].body!!.utf8()).contains("\"archived\":true")
        assertThat(server.requests.any { it.method == "DELETE" && it.url.encodedPath == "/api/sessions/api_1" }).isTrue()

        val failure = runCatching {
            gateway.call("image.attach_bytes", buildJsonObject { put("session_id", "api_1") })
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(RpcException::class.java)
    }

    @Test
    fun `the receipt excludes rows before the boundary and server-injected user rows`() = runBlocking<Unit> {
        server.historyRows = listOf(row(10, "user", "old"), row(11, "assistant", "old answer"))
        server.afterRunRows = listOf(
            row(10, "user", "old"), row(11, "assistant", "old answer"),
            row(20, "user", "hello"), row(21, "user", "process done", displayKind = "process_complete"),
            row(22, "tool", "", toolCallId = "call_1"), row(23, "assistant", "Hi"),
        )
        server.runProjection = """{"run_id":"run_1","status":"running","session_id":"api_1","admission_id":"adm_1","execution_generation":3,"pending_controls":[]}"""
        server.runSse = listOf(
            sse(
                """
                id: 1
                event: run.completed
                data: {"run_id":"run_1","output":"Hi"}

                """.trimIndent(),
            ),
        )
        ready()
        gateway.call("prompt.submit", buildJsonObject { put("session_id", "api_1"); put("text", "hello") })

        val complete = waitEvent("message.complete")
        val receipt = complete.payload["persisted_turn"]!!.jsonObject
        assertThat(receipt.long("user_row_id")).isEqualTo(20)
        assertThat(receipt.long("final_assistant_row_id")).isEqualTo(23)
        assertThat((receipt["row_ids"] as JsonArray).map { it.toString() }).containsExactly("20", "22", "23").inOrder()
    }

    private fun row(id: Long, role: String, content: String, displayKind: String? = null, toolCallId: String? = null): String {
        val fields = buildString {
            append("\"id\":\"$id\",\"role\":\"$role\",\"content\":${quote(content)},\"timestamp\":$id.0")
            if (displayKind != null) append(",\"display_kind\":${quote(displayKind)}")
            if (toolCallId != null) append(",\"tool_call_id\":${quote(toolCallId)}")
        }
        return "{$fields}"
    }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** The stock routes the adapter uses, with a run whose rows land when it is admitted. */
    private class MockStockServer {
        val server = MockWebServer()
        val requests = ConcurrentLinkedQueue<RecordedRequest>()

        @Volatile
        var historyRows: List<String> = emptyList()

        @Volatile
        var afterRunRows: List<String> = emptyList()

        @Volatile
        var runProjection: String = """{"run_id":"run_1","status":"running","session_id":"api_1","pending_controls":[]}"""

        @Volatile
        var runSse: List<MockResponse> = emptyList()
        private var sseIndex = 0
        private var runStarted = false

        fun start() {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.url.encodedPath
                    val method = request.method
                    return when {
                        method == "GET" && path == "/api/sessions" ->
                            json("""{"object":"list","data":[{"id":"api_1","title":"Work","last_active":1.0,"message_count":1}]}""")
                        method == "POST" && path == "/api/sessions" ->
                            json("""{"object":"hermes.session","session":{"id":"api_1"}}""")
                        method == "GET" && path == "/api/sessions/api_1/messages" -> {
                            val rows = if (runStarted) afterRunRows else historyRows
                            val limit = request.url.queryParameter("limit")?.toIntOrNull() ?: 500
                            val offset = request.url.queryParameter("offset")?.toIntOrNull() ?: 0
                            val latest = request.url.queryParameter("order") == "latest"
                            val ordered = if (latest) rows.sortedByDescending { rowId(it) } else rows.sortedBy { rowId(it) }
                            val page = ordered.drop(offset).take(limit)
                            json(
                                """{"object":"list","session_id":"api_1","pagination":{"limit":$limit,"offset":$offset,"order":"${if (latest) "latest" else "oldest"}","returned":${page.size}},"data":[${page.joinToString(",")}]}""",
                            )
                        }
                        method == "POST" && path == "/v1/runs" -> {
                            runStarted = true
                            MockResponse.Builder().code(202).body("""{"run_id":"run_1","status":"started","replayed":false}""").build()
                        }
                        method == "GET" && path == "/v1/runs/run_1" -> json(runProjection)
                        method == "GET" && path == "/v1/runs/run_1/events" -> {
                            val index = sseIndex
                            sseIndex += 1
                            runSse.getOrElse(index) { sse("") }
                        }
                        method == "POST" && path.startsWith("/v1/runs/run_1/") -> json("""{"run_id":"run_1","status":"completed"}""")
                        method == "PATCH" && path == "/api/sessions/api_1" -> json("""{"object":"hermes.session","session":{"id":"api_1"}}""")
                        method == "DELETE" && path == "/api/sessions/api_1" -> json("""{"object":"hermes.session.deleted","id":"api_1","deleted":true}""")
                        else -> MockResponse.Builder().code(404).body("""{"error":{"message":"no route $path"}}""").build()
                    }
                }
            }
            server.start()
        }

        fun close() = server.close()

        private fun rowId(row: String): Long =
            Regex("\"id\":\"(\\d+)\"").find(row)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        private fun json(body: String): MockResponse =
            MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

        private fun sse(body: String): MockResponse =
            MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(body).build()
    }
}
