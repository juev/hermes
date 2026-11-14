package org.evsyukov.hermes.data.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.Tokens
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The channel speaks the stock API server: these fixtures are the documented response shapes, and the
 * request assertions pin the source filter, the detached run routes and the exact run-control bodies
 * the server validates. A turn runs through `/v1/runs` + `/v1/runs/{id}/events`, not `chat/stream`:
 * the latter interrupts the run when the client disconnects, the former survives it and replays.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ApiServerChannelTest {

    private fun channel(server: MockWebServer): ApiServerChannel = ApiServerChannel(
        HermesHttp(
            base = server.url("/"),
            client = OkHttpClient(),
            tokens = { Tokens("fixture-key") },
            strings = AppStrings(ApplicationProvider.getApplicationContext<Application>().resources),
        ),
    )

    private fun sse(body: String): MockResponse =
        MockResponse.Builder().body(body).addHeader("Content-Type", "text/event-stream").build()

    @Test
    fun `chats are read from the api_server source only`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body(
                    """
                    {"object":"list","limit":50,"offset":0,"has_more":false,"data":[
                      {"id":"api_1","title":"Work","last_active":1700000100.0,"preview":"готово","message_count":4},
                      {"id":"api_2","title":"Home","last_active":1700000200.0,"preview":null,"message_count":0}
                    ]}
                    """.trimIndent(),
                ).build(),
            )
            val chats = channel(server).chats()
            val request = server.takeRequest()
            assertThat(request.url.encodedPath).isEqualTo("/api/sessions")
            assertThat(request.url.queryParameter("source")).isEqualTo("api_server")
            assertThat(chats.map { it.id }).containsExactly("api_1", "api_2").inOrder()
            assertThat(chats.first().title).isEqualTo("Work")
            assertThat(chats.first().messages).isEqualTo(4)
            assertThat(chats[1].preview).isNull()
        }
    }

    @Test
    fun `a new channel conversation takes the id the server reports`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"object":"hermes.session","session":{"id":"api_9","title":"New"}}""").build())
            val id = channel(server).create("New")
            val request = server.takeRequest()
            assertThat(id).isEqualTo("api_9")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.url.encodedPath).isEqualTo("/api/sessions")
            assertThat(request.body!!.utf8()).contains("\"title\":\"New\"")
        }
    }

    @Test
    fun `a turn starts a detached run with an idempotency key`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(202)
                    .body("""{"run_id":"run_1","status":"started","replayed":false}""").build(),
            )
            val runId = channel(server).startRun("api_1", "Reply with exactly: OK", "send-7")
            val request = server.takeRequest()
            assertThat(runId).isEqualTo("run_1")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.url.encodedPath).isEqualTo("/v1/runs")
            assertThat(request.headers["Idempotency-Key"]).isEqualTo("send-7")
            val body = request.body!!.utf8()
            assertThat(body).contains("\"session_id\":\"api_1\"")
            assertThat(body).contains("\"input\":\"Reply with exactly: OK\"")
        }
    }

    @Test
    fun `the run stream maps deltas, tools and the final answer with their seq`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                sse(
                    """
                    id: 1
                    event: message.delta
                    data: {"run_id":"run_1","delta":"O"}

                    id: 2
                    event: tool.started
                    data: {"run_id":"run_1","tool":"terminal","preview":"ls"}

                    id: 3
                    event: reasoning.available
                    data: {"run_id":"run_1","text":"thinking hard"}

                    id: 4
                    event: tool.completed
                    data: {"run_id":"run_1","tool":"terminal","error":false}

                    id: 5
                    event: message.interim
                    data: {"run_id":"run_1","text":"Проверил"}

                    id: 6
                    event: message.delta
                    data: {"run_id":"run_1","delta":"K"}

                    id: 7
                    event: run.completed
                    data: {"run_id":"run_1","output":"OK","usage":{"input_tokens":10}}

                    """.trimIndent(),
                ),
            )
            val events = mutableListOf<Pair<ApiServerChannel.Event, Long>>()
            channel(server).runEvents("run_1") { event, seq -> events += event to seq }
            val request = server.takeRequest()
            assertThat(request.url.encodedPath).isEqualTo("/v1/runs/run_1/events")
            assertThat(request.headers["Accept"]).isEqualTo("text/event-stream")
            assertThat(events.map { it.first }).containsExactly(
                ApiServerChannel.Event.Delta("O"),
                ApiServerChannel.Event.Working("terminal"),
                ApiServerChannel.Event.Working(null),
                ApiServerChannel.Event.Working("terminal"),
                ApiServerChannel.Event.Commentary("Проверил"),
                ApiServerChannel.Event.Delta("K"),
                ApiServerChannel.Event.Completed("OK", "run_1"),
                ApiServerChannel.Event.Finished,
            ).inOrder()
            assertThat(events.map { it.second }).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 7L).inOrder()
        }
    }

    @Test
    fun `a reattach asks for the replay after the last seq it saw`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                sse(
                    """
                    id: 9
                    event: replay.truncated
                    data: {"run_id":"run_1","oldest_retained_seq":5,"requested_seq":2}

                    id: 10
                    event: run.completed
                    data: {"run_id":"run_1","output":"done"}

                    """.trimIndent(),
                ),
            )
            val events = mutableListOf<ApiServerChannel.Event>()
            channel(server).runEvents("run_1", lastSeq = 2) { event, _ -> events += event }
            val request = server.takeRequest()
            assertThat(request.headers["Last-Event-ID"]).isEqualTo("2")
            assertThat(events).containsExactly(
                ApiServerChannel.Event.Truncated(5),
                ApiServerChannel.Event.Completed("done", "run_1"),
                ApiServerChannel.Event.Finished,
            ).inOrder()
        }
    }

    @Test
    fun `control prompts and their settlement ride the run stream`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                sse(
                    """
                    id: 1
                    event: clarify.request
                    data: {"run_id":"run_1","kind":"clarify","prompt_id":"cl_1","execution_generation":7,"question":"Какой сервер?","choices":["ams","loffe"],"multi_select":false}

                    id: 2
                    event: approval.request
                    data: {"run_id":"run_1","kind":"approval","prompt_id":"req_1","execution_generation":7,"description":"rm -rf /tmp/x","choices":["once","deny"]}

                    id: 3
                    event: clarify.settled
                    data: {"run_id":"run_1","kind":"clarify","request_id":"cl_1"}

                    """.trimIndent(),
                ),
            )
            val events = mutableListOf<ApiServerChannel.Event>()
            channel(server).runEvents("run_1") { event, _ -> events += event }
            assertThat(events).hasSize(4)
            val clarify = events[0] as ApiServerChannel.Event.Control
            assertThat(clarify.prompt.kind).isEqualTo("clarify")
            assertThat(clarify.prompt.promptId).isEqualTo("cl_1")
            assertThat(clarify.prompt.generation).isEqualTo(7)
            assertThat(clarify.prompt.question).isEqualTo("Какой сервер?")
            assertThat(clarify.prompt.choices).containsExactly("ams", "loffe").inOrder()
            val approval = events[1] as ApiServerChannel.Event.Control
            assertThat(approval.prompt.kind).isEqualTo("approval")
            assertThat(approval.prompt.question).isEqualTo("rm -rf /tmp/x")
            val settled = events[2] as ApiServerChannel.Event.Settled
            assertThat(settled.kind).isEqualTo("clarify")
            assertThat(settled.requestId).isEqualTo("cl_1")
            assertThat(events[3]).isEqualTo(ApiServerChannel.Event.Finished)
        }
    }

    @Test
    fun `a failed run reports its error and closes`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                sse(
                    """
                    id: 1
                    event: run.failed
                    data: {"run_id":"run_1","error":"provider auth failed"}

                    """.trimIndent(),
                ),
            )
            val events = mutableListOf<ApiServerChannel.Event>()
            channel(server).runEvents("run_1") { event, _ -> events += event }
            assertThat(events).containsExactly(
                ApiServerChannel.Event.Failed("provider auth failed"),
                ApiServerChannel.Event.Finished,
            ).inOrder()
        }
    }

    @Test
    fun `the run status is pollable and a forgotten run is null`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body(
                    """{"run_id":"run_1","status":"completed","session_id":"api_1","output":"OK","pending_controls":[]}""",
                ).build(),
            )
            server.enqueue(MockResponse.Builder().code(404).body("""{"error":{"message":"Unknown run"}}""").build())
            val channel = channel(server)
            val status = channel.runStatus("run_1")
            assertThat(status.string("status")).isEqualTo("completed")
            assertThat(status.string("output")).isEqualTo("OK")
            assertThat(channel.runStatusOrNull("run_gone")).isNull()
        }
    }

    @Test
    fun `a pending question carries the generation its answer must repeat`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body(
                    """
                    {"run_id":"run_1","status":"started","session_id":"api_1","pending_controls":[
                      {"kind":"clarify","prompt_id":"cl_1","execution_generation":7,"question":"Какой сервер?",
                       "choices":["ams","loffe"],"multi_select":false}
                    ]}
                    """.trimIndent(),
                ).build(),
            )
            val prompts = channel(server).pending("run_1")
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/v1/runs/run_1")
            assertThat(prompts).hasSize(1)
            val prompt = prompts.single()
            assertThat(prompt.kind).isEqualTo("clarify")
            assertThat(prompt.promptId).isEqualTo("cl_1")
            assertThat(prompt.generation).isEqualTo(7)
            assertThat(prompt.choices).containsExactly("ams", "loffe").inOrder()
        }
    }

    @Test
    fun `an answer uses the exact run control body`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"run_id":"run_1","status":"completed"}""").build())
            server.enqueue(MockResponse.Builder().body("""{"run_id":"run_1"}""").build())
            val channel = channel(server)
            channel.answer(
                "run_1",
                ApiServerChannel.Prompt("clarify", "cl_1", 7, "Какой сервер?", listOf("ams", "loffe"), false),
                "loffe",
            )
            channel.answer(
                "run_1",
                ApiServerChannel.Prompt("approval", "req_1", 9, "rm -rf /tmp/x", listOf("once", "deny"), false),
                "once",
            )
            val clarify = server.takeRequest()
            assertThat(clarify.url.encodedPath).isEqualTo("/v1/runs/run_1/clarify")
            val clarifyBody = clarify.body!!.utf8()
            assertThat(clarifyBody).contains("\"request_id\":\"cl_1\"")
            assertThat(clarifyBody).contains("\"execution_generation\":7")
            assertThat(clarifyBody).contains("\"answer\":\"loffe\"")
            val approval = server.takeRequest()
            assertThat(approval.url.encodedPath).isEqualTo("/v1/runs/run_1/approval")
            val approvalBody = approval.body!!.utf8()
            assertThat(approvalBody).contains("\"request_id\":\"req_1\"")
            assertThat(approvalBody).contains("\"execution_generation\":9")
            assertThat(approvalBody).contains("\"choice\":\"once\"")
        }
    }

    @Test
    fun `history reads the documented data envelopes`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body(
                    """
                    {"object":"list","session_id":"api_1","pagination":{"limit":200,"offset":0,"order":"oldest","returned":2},"data":[
                      {"id":"42","role":"user","content":"привет","timestamp":1700000000.0},
                      {"id":"43","role":"assistant","content":"готово","timestamp":1700000001.0,"display_kind":"final"}
                    ]}
                    """.trimIndent(),
                ).build(),
            )
            val page = channel(server).history("api_1")
            val request = server.takeRequest()
            assertThat(request.url.encodedPath).isEqualTo("/api/sessions/api_1/messages")
            assertThat(page.hasMore).isFalse()
            assertThat(page.rows.map { it.role }).containsExactly("user", "assistant").inOrder()
            assertThat(page.rows.last().text).isEqualTo("готово")
            assertThat(page.rows.last().displayKind).isEqualTo("final")
        }
    }

    @Test
    fun `a full history page means there may be more`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body(
                    """
                    {"object":"list","session_id":"api_1","pagination":{"limit":2,"offset":0,"order":"oldest","returned":2},"data":[
                      {"id":"42","role":"user","content":"a","timestamp":1.0},
                      {"id":"43","role":"assistant","content":"b","timestamp":2.0}
                    ]}
                    """.trimIndent(),
                ).build(),
            )
            assertThat(channel(server).history("api_1", limit = 2).hasMore).isTrue()
        }
    }

    @Test
    fun `the newest row id bounds the turn about to be sent`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body(
                    """
                    {"object":"list","session_id":"api_1","pagination":{"limit":1,"offset":0,"order":"latest","returned":1},"data":[
                      {"id":"43","role":"assistant","content":"готово","timestamp":2.0}
                    ]}
                    """.trimIndent(),
                ).build(),
            )
            server.enqueue(
                MockResponse.Builder().body(
                    """{"object":"list","session_id":"api_1","pagination":{"limit":1,"offset":0,"order":"latest","returned":0},"data":[]}""",
                ).build(),
            )
            val channel = channel(server)
            assertThat(channel.newestRowId("api_1")).isEqualTo(43L)
            val request = server.takeRequest()
            assertThat(request.url.queryParameter("order")).isEqualTo("latest")
            assertThat(request.url.queryParameter("limit")).isEqualTo("1")
            assertThat(channel.newestRowId("api_1")).isNull()
        }
    }
}