package org.evsyukov.hermes.data.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
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
 * request assertions pin the source filter and the exact run-control bodies the server validates.
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
    fun `one turn streams the assistant deltas and the final answer`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body(
                    """
                    event: run.started
                    data: {"run_id":"run_1","session_id":"api_1","seq":1}

                    event: message.started
                    data: {"run_id":"run_1","message":{"id":"msg_1","role":"assistant"}}

                    event: assistant.delta
                    data: {"run_id":"run_1","message_id":"msg_1","delta":"O"}

                    event: assistant.delta
                    data: {"run_id":"run_1","message_id":"msg_1","delta":"K"}

                    event: assistant.completed
                    data: {"run_id":"run_1","message_id":"msg_1","content":"OK"}

                    event: run.completed
                    data: {"run_id":"run_1","seq":6}

                    event: done
                    data: {"run_id":"run_1","seq":7}

                    """.trimIndent(),
                ).header("Content-Type", "text/event-stream").build(),
            )
            val events = mutableListOf<ApiServerChannel.Event>()
            channel(server).stream("api_1", "Reply with exactly: OK") { events += it }
            val request = server.takeRequest()
            assertThat(request.url.encodedPath).isEqualTo("/api/sessions/api_1/chat/stream")
            assertThat(request.headers["Accept"]).isEqualTo("text/event-stream")
            assertThat(request.body!!.utf8()).contains("\"message\":\"Reply with exactly: OK\"")
            assertThat(events.filterIsInstance<ApiServerChannel.Event.Delta>().map { it.text }).containsExactly("O", "K").inOrder()
            assertThat(events.filterIsInstance<ApiServerChannel.Event.Completed>().last().text).isEqualTo("OK")
            assertThat(events.first()).isInstanceOf(ApiServerChannel.Event.Started::class.java)
        }
    }

    @Test
    fun `tool frames become one working state, not payload`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body(
                    """
                    event: tool.started
                    data: {"run_id":"run_1","tool_name":"terminal","preview":"ls"}

                    event: tool.completed
                    data: {"run_id":"run_1","tool_name":"terminal"}

                    event: assistant.commentary
                    data: {"run_id":"run_1","text":"Проверил"}

                    """.trimIndent(),
                ).header("Content-Type", "text/event-stream").build(),
            )
            val events = mutableListOf<ApiServerChannel.Event>()
            channel(server).stream("api_1", "go") { events += it }
            assertThat(events.filterIsInstance<ApiServerChannel.Event.Working>().map { it.tool })
                .containsExactly("terminal", "terminal").inOrder()
            assertThat(events.filterIsInstance<ApiServerChannel.Event.Commentary>().single().text).isEqualTo("Проверил")
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
                    {"object":"list","session_id":"api_1","pagination":{"has_more":true},"data":[
                      {"id":"42","role":"user","content":"привет","timestamp":1700000000.0},
                      {"id":"43","role":"assistant","content":"готово","timestamp":1700000001.0,"display_kind":"final"}
                    ]}
                    """.trimIndent(),
                ).build(),
            )
            val page = channel(server).history("api_1")
            val request = server.takeRequest()
            assertThat(request.url.encodedPath).isEqualTo("/api/sessions/api_1/messages")
            assertThat(page.hasMore).isTrue()
            assertThat(page.rows.map { it.role }).containsExactly("user", "assistant").inOrder()
            assertThat(page.rows.last().text).isEqualTo("готово")
            assertThat(page.rows.last().displayKind).isEqualTo("final")
        }
    }
}
