package org.evsyukov.hermes.data.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.evsyukov.hermes.AppStrings
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class IntegrationsTest {
    private val strings = AppStrings(ApplicationProvider.getApplicationContext<Application>().resources)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val gateway = ConnectorsGateway()
    private val rest = ConcurrentHashMap<String, String>()
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    private lateinit var http: HermesHttp

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (path == "/api/ws") return MockResponse.Builder().webSocketUpgrade(gateway).build()
                requests += request
                val body = rest[path] ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        http = HermesHttp(server.url("/"), OkHttpClient(), tokens = { null }, saveTokens = {}, strings = strings)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    @Test
    fun `a server of a plugin is not the user's to sign in, and no tool list means all tools`() = runBlocking<Unit> {
        rest["/api/mcp/servers"] = """{"servers":[
            {"name":"linear","transport":"http","url":"https://mcp.linear.app/sse","command":null,"args":[],"auth":"oauth","enabled":true,"tools":null,"source":"config","plugin":null},
            {"name":"files","transport":"stdio","url":null,"command":"npx","args":["-y","@mcp/files","/srv"],"auth":null,"enabled":false,"tools":["read_file"],"source":"plugin","plugin":"fs-pack"}
        ]}"""

        val (linear, files) = AdminApi(http).mcpServers()

        assertThat(linear.tools).isNull()
        assertThat(linear.plugin).isNull()
        assertThat(linear.launch).isEqualTo("https://mcp.linear.app/sse")
        assertThat(linear.signsIn).isTrue()
        assertThat(files.plugin).isEqualTo("fs-pack")
        assertThat(files.enabled).isFalse()
        assertThat(files.tools).containsExactly("read_file")
        assertThat(files.launch).isEqualTo("npx -y @mcp/files /srv")
        assertThat(files.signsIn).isFalse()
    }

    @Test
    fun `a failed test keeps the reason, a passed one the tools`() = runBlocking<Unit> {
        rest["/api/mcp/servers/linear/test"] = """{"ok":false,"error":"OAuth authentication required — no token found.","tools":[]}"""
        rest["/api/mcp/servers/files/test"] = """{"ok":true,"tools":[{"name":"read_file","description":"Reads"},{"name":"list_dir","description":""}],"prompts":0,"resources":0}"""
        val api = AdminApi(http)

        assertThat(api.testMcp("linear")).isEqualTo(McpTest(false, emptyList(), "OAuth authentication required — no token found."))
        assertThat(api.testMcp("files")).isEqualTo(McpTest(true, listOf("read_file", "list_dir"), null))
    }

    @Test
    fun `a catalog entry shows what it clones, builds and starts, and the install sends only the keys given`() = runBlocking<Unit> {
        rest["/api/mcp/catalog"] = """{"entries":[{"name":"github","description":"GitHub issues","connector_slug":null,"source":"official",
            "transport":"stdio","auth_type":"env","required_env":[{"name":"GITHUB_TOKEN","prompt":"Personal access token","required":true},{"name":"GITHUB_HOST","prompt":"","required":false}],
            "command":"node","args":["dist/index.js"],"url":null,"install_url":"https://github.com/example/github-mcp","install_ref":"v1.2.0",
            "bootstrap":["npm ci","npm run build"],"default_enabled":null,"post_install":"Restart the gateway.","suggest":null,"needs_install":true,"installed":false,"enabled":false}],
            "diagnostics":[]}"""
        rest["/api/mcp/catalog/install"] = """{"ok":true,"name":"github","background":true,"action":"mcp-install-github-1a2b3c4d"}"""
        val api = AdminApi(http)

        val entry = api.mcpCatalog().single()
        val install = api.installMcp(entry.name, mapOf("GITHUB_TOKEN" to "ghp_x"))

        assertThat(entry.installUrl).isEqualTo("https://github.com/example/github-mcp")
        assertThat(entry.installRef).isEqualTo("v1.2.0")
        assertThat(entry.bootstrap).containsExactly("npm ci", "npm run build").inOrder()
        assertThat(entry.launch).isEqualTo("node dist/index.js")
        assertThat(entry.env).containsExactly(McpEnv("GITHUB_TOKEN", "Personal access token", true), McpEnv("GITHUB_HOST", "GITHUB_HOST", false)).inOrder()
        assertThat(install).isEqualTo(McpInstall(true, "mcp-install-github-1a2b3c4d"))
        val body = HermesJson.parseToJsonElement(requests.last { it.url.encodedPath == "/api/mcp/catalog/install" }.body!!.utf8()).jsonObject
        assertThat(body["name"]!!.jsonPrimitive.content).isEqualTo("github")
        assertThat(body["env"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }).containsExactly("GITHUB_TOKEN", "ghp_x")
    }

    @Test
    fun `an MCP sign-in gives the page to open and is followed by its flow`() = runBlocking<Unit> {
        rest["/api/mcp/servers/linear/auth"] = """{"flow_id":"f1","server_name":"linear","status":"authorization_required","authorization_url":"https://linear.app/oauth/authorize?x=1","error":null}"""
        rest["/api/mcp/oauth/flows/f1"] = """{"flow_id":"f1","server_name":"linear","status":"approved","authorization_url":null,"error":null,"tools":["a","b"]}"""
        val api = AdminApi(http)

        val started = api.signInMcp("linear")
        val done = api.mcpSignIn(started)

        assertThat(started.url).isEqualTo("https://linear.app/oauth/authorize?x=1")
        assertThat(started.done).isFalse()
        assertThat(done.done).isTrue()
        assertThat(done.status).isEqualTo("approved")
        assertThat(done.tools).isEqualTo(2)
    }

    @Test
    fun `the services through Nous come with their names and accounts, the used ones first`() = runBlocking<Unit> {
        gateway.results["connectors.list"] = """{"available":true,"connectors":[
            {"connector":"slack","enabled":true,"connected":false,"connection_status":null,"status_reason":null,"gateway_disabled_tools":[]},
            {"connector":"gmail","enabled":true,"connected":true,"connection_status":"failed","status_reason":"Token refresh failed","gateway_disabled_tools":[]},
            {"connector":"jira","enabled":false,"connected":false,"connection_status":null,"status_reason":null,"gateway_disabled_tools":[]}
        ]}"""
        gateway.results["connectors.catalog"] = """{"connectors":[{"slug":"gmail","name":"Gmail","description":"Mail","category":"email"},{"slug":"slack","name":"Slack","description":"Chat","category":"chat"}]}"""
        gateway.results["connectors.accounts"] = """{"accounts":[
            {"connection_id":"c1","connector":"gmail","status":"failed","status_reason":"Token refresh failed","label":"me@example.org","alias":"work","active":true,"disabled":false,"created_at":"2026-10-01","updated_at":"2026-10-28"},
            {"connection_id":"c0","connector":"gmail","status":"revoked","status_reason":null,"label":"me@example.org","alias":null,"active":false,"disabled":true,"created_at":"2026-09-01","updated_at":"2026-10-01"}
        ]}"""

        val hosted = ConnectorsApi(socket()).hosted()

        assertThat(hosted.available).isTrue()
        assertThat(hosted.connectors.map { it.slug }).containsExactly("gmail", "slack").inOrder()
        val gmail = hosted.connectors.first()
        assertThat(gmail.name).isEqualTo("Gmail")
        assertThat(gmail.broken).isTrue()
        assertThat(gmail.reason).isEqualTo("Token refresh failed")
        assertThat(gmail.accounts.map { it.connectionId to it.disabled }).containsExactly("c1" to false, "c0" to true).inOrder()
        assertThat(gateway.params("connectors.list")["owner"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("account")
    }

    @Test
    fun `without Nous the accounts are unavailable with the server's reason`() = runBlocking<Unit> {
        gateway.errors["connectors.list"] = """{"code":4031,"message":"Connectors are not available.","data":{"reason":"CONNECTORS_UNAVAILABLE"}}"""

        val hosted = ConnectorsApi(socket()).hosted()

        assertThat(hosted).isEqualTo(Hosted(false, emptyList(), "CONNECTORS_UNAVAILABLE"))
    }

    @Test
    fun `a repair names the account, and only an https sign-in page is opened`() = runBlocking<Unit> {
        gateway.results["connectors.connect"] = """{"op_id":"op9","status":"initiated","targets":[{"name":"gmail","state":"initiated","connect_url":"https://connect.nousresearch.com/x"}]}"""
        val api = ConnectorsApi(socket())

        val started = api.connect("gmail", reconnect = true, connectionId = "c1")
        api.wake(started.opId!!)
        api.remove("c1")

        assertThat(started).isEqualTo(HostedConnect("op9", "https://connect.nousresearch.com/x"))
        val connect = gateway.params("connectors.connect")
        assertThat(connect["connectors"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("gmail")
        assertThat(connect["reconnect"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(connect["connection_id"]!!.jsonPrimitive.content).isEqualTo("c1")
        assertThat(gateway.params("connectors.operation.wake")["op_id"]!!.jsonPrimitive.content).isEqualTo("op9")
        assertThat(gateway.params("connectors.accounts.remove")["connection_id"]!!.jsonPrimitive.content).isEqualTo("c1")

        gateway.results["connectors.connect"] = """{"op_id":"op10","targets":[{"name":"gmail","connect_url":"javascript:alert(1)"}]}"""
        assertThat(api.connect("gmail", reconnect = false).url).isNull()
    }

    @Test
    fun `the tools of a service say what they do to the user's data`() = runBlocking<Unit> {
        gateway.results["connectors.tools"] = """{"connector":"gmail","toolkit_version":"1","etag":"e","fetched_at":0,"source":"cache","stale":false,"tools":[
            {"slug":"gmail_send","name":"send_email","description":"Sends","facet":"write","hints":[],"categories":[],"no_auth":false,"deprecated":false},
            {"slug":"gmail_delete","name":"delete_email","description":"","facet":"destructive","hints":[],"categories":[],"no_auth":false,"deprecated":false}
        ]}"""

        val tools = ConnectorsApi(socket()).tools("gmail")

        assertThat(tools).containsExactly(HostedTool("send_email", "Sends", "write"), HostedTool("delete_email", "", "destructive")).inOrder()
    }

    private fun socket() = GatewaySocket(http, scope, strings)
}

/** Answers each method with the result or the error set for it, and keeps the parameters of the last call. */
private class ConnectorsGateway : WebSocketListener() {
    val results = ConcurrentHashMap<String, String>()
    val errors = ConcurrentHashMap<String, String>()
    private val calls = ConcurrentHashMap<String, JsonObject>()

    fun params(method: String): JsonObject = calls[method] ?: error("$method was not called")

    override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = HermesJson.parseToJsonElement(text).jsonObject
        val id = frame["id"]?.jsonPrimitive?.content ?: return
        val method = frame["method"]?.jsonPrimitive?.content ?: return
        calls[method] = frame["params"]?.jsonObject ?: JsonObject(emptyMap())
        val error = errors[method]
        webSocket.send(
            if (error != null) """{"jsonrpc":"2.0","id":"$id","error":$error}"""
            else """{"jsonrpc":"2.0","id":"$id","result":${results[method] ?: "{}"}}""",
        )
    }
}
