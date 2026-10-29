package org.evsyukov.hermes.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** An MCP server of `config.yaml` or of a plugin, as `/api/mcp/servers` lists it. */
data class McpServer(
    val name: String,
    /** `http`, `stdio` or `unknown`. */
    val transport: String,
    val url: String?,
    val command: String?,
    val args: List<String>,
    /** `oauth`, `header` or null. */
    val auth: String?,
    val enabled: Boolean,
    /** The tools the agent may call; null means all of them. */
    val tools: List<String>?,
    /** The plugin that brought the server; null for a server of the config, which the user manages. */
    val plugin: String?,
) {
    /** What the gateway starts or calls: the command line of a local server or the address of a remote one. */
    val launch: String get() = url ?: listOfNotNull(command).plus(args).joinToString(" ")

    /** Only a remote server of the config without its own key signs in with OAuth. */
    val signsIn: Boolean get() = plugin == null && url != null && auth != "header"
}

/** The answer of a server test: the tools it offered, or why it did not answer. */
data class McpTest(val ok: Boolean, val tools: List<String>, val error: String?)

/** A credential a catalog server needs; the values go to the server's `.env`. */
data class McpEnv(val name: String, val prompt: String, val required: Boolean)

/** An entry of the MCP catalog Nous approved, with all the user should see before installing it. */
data class McpCatalogEntry(
    val name: String,
    val description: String,
    val source: String?,
    val transport: String?,
    val authType: String?,
    val env: List<McpEnv>,
    val command: String?,
    val args: List<String>,
    val url: String?,
    /** A repository cloned and built before the first start. */
    val installUrl: String?,
    val installRef: String?,
    val bootstrap: List<String>,
    val postInstall: String,
    val installed: Boolean,
    val enabled: Boolean,
) {
    val launch: String get() = url ?: listOfNotNull(command).plus(args).joinToString(" ")
}

/** An install that clones a repository goes on as a background action of this name. */
data class McpInstall(val background: Boolean, val action: String?)

/** A sign-in of an MCP server: the page to open, then `approved` or `error`. */
data class McpAuthFlow(val id: String, val server: String, val status: String, val url: String?, val error: String?, val tools: Int?) {
    val done: Boolean get() = status == "approved" || status == "error"
}

/** An account the user connected through Nous. */
data class HostedAccount(
    val connectionId: String,
    val connector: String,
    /** `pending`, `active`, `failed`, `expired`, `revoked` or `inactive`. */
    val status: String,
    val reason: String?,
    val label: String,
    val alias: String?,
    /** A reconnect replaced it; it stays listed and is never used. */
    val disabled: Boolean,
) {
    val broken: Boolean get() = status in BROKEN
}

/** A service of the Nous catalog with the accounts the user connected to it. */
data class HostedConnector(
    val slug: String,
    val name: String,
    val description: String,
    val connected: Boolean,
    val status: String?,
    val reason: String?,
    val accounts: List<HostedAccount>,
) {
    /** Connected once and failed since: connecting again repairs it. */
    val broken: Boolean get() = status in BROKEN
}

/** The integrations through Nous; [reason] says why there are none when they are unavailable. */
data class Hosted(val available: Boolean, val connectors: List<HostedConnector>, val reason: String? = null)

/** A tool of a hosted service and what it does to the user's data. */
data class HostedTool(val name: String, val description: String, val facet: String)

/** A connection the server opened for the user; [url] is the page to sign in on, when there is one. */
data class HostedConnect(val opId: String?, val url: String?)

private val BROKEN = setOf("failed", "expired", "revoked", "inactive")

/**
 * The accounts connected through Nous. The dashboard serves them only over the chat socket, as the
 * desktop Connectors page reads them, with the account of the server as the owner.
 */
class ConnectorsApi(private val socket: GatewaySocket) {
    /** The catalog with the state of each service and its accounts; unavailable without Nous. */
    suspend fun hosted(): Hosted {
        val list = try {
            socket.call("connectors.list", buildJsonObject { put("owner", ACCOUNT) }) as? JsonObject
        } catch (e: RpcException) {
            return Hosted(false, emptyList(), reasonOf(e) ?: e.message)
        }
        if (list?.bool("available") == false) return Hosted(false, emptyList())
        val names = try {
            objects(socket.call("connectors.catalog") as? JsonObject, "connectors").associateBy { it.string("slug") }
        } catch (e: RpcException) {
            emptyMap()
        }
        val accounts = objects(socket.call("connectors.accounts") as? JsonObject, "accounts").mapNotNull(::accountOf).groupBy { it.connector }
        return Hosted(true, connectorsOf(list, names, accounts))
    }

    /** Opens a connection of [slug]: a new account, or with [connectionId] a repair of that one. */
    suspend fun connect(slug: String, reconnect: Boolean, connectionId: String? = null): HostedConnect {
        val result = socket.call(
            "connectors.connect",
            buildJsonObject {
                put("owner", ACCOUNT)
                put("connectors", JsonArray(listOf(JsonPrimitive(slug))))
                put("reconnect", reconnect)
                if (reconnect && connectionId != null) put("connection_id", connectionId)
            },
            timeoutMillis = 45_000,
        ) as? JsonObject
        val url = objects(result, "targets").firstNotNullOfOrNull { it.string("connect_url")?.takeIf { u -> u.startsWith("https://") } }
        return HostedConnect(result?.string("op_id"), url)
    }

    /** The user is back from the browser: the server reads the accounts now instead of at its next check. */
    suspend fun wake(opId: String) {
        socket.call("connectors.operation.wake", buildJsonObject { put("owner", ACCOUNT); put("op_id", opId) })
    }

    suspend fun remove(connectionId: String) {
        socket.call("connectors.accounts.remove", buildJsonObject { put("connection_id", connectionId) })
    }

    suspend fun tools(slug: String): List<HostedTool> {
        val result = socket.call("connectors.tools", buildJsonObject { put("slug", slug); put("refresh", false) }) as? JsonObject
        return objects(result, "tools").mapNotNull { tool ->
            HostedTool(tool.string("name") ?: return@mapNotNull null, tool.string("description").orEmpty(), tool.string("facet") ?: "unclassified")
        }
    }

    private companion object {
        val ACCOUNT = buildJsonObject { put("type", "account") }
    }
}

private fun connectorsOf(list: JsonObject?, names: Map<String?, JsonObject>, accounts: Map<String, List<HostedAccount>>): List<HostedConnector> =
    objects(list, "connectors").mapNotNull { row ->
        val slug = row.string("connector") ?: return@mapNotNull null
        if (row.bool("enabled") == false) return@mapNotNull null
        val entry = names[slug]
        HostedConnector(
            slug = slug,
            name = entry?.string("name")?.takeIf { it.isNotBlank() } ?: slug,
            description = entry?.string("description").orEmpty(),
            connected = row.bool("connected") ?: false,
            status = row.string("connection_status"),
            reason = row.string("status_reason")?.takeIf { it.isNotBlank() },
            accounts = accounts[slug].orEmpty(),
        )
    }.sortedWith(compareByDescending<HostedConnector> { it.connected || it.accounts.isNotEmpty() }.thenBy { it.name.lowercase() })

private fun accountOf(obj: JsonObject): HostedAccount? = HostedAccount(
    connectionId = obj.string("connection_id") ?: return null,
    connector = obj.string("connector") ?: return null,
    status = obj.string("status") ?: "pending",
    reason = obj.string("status_reason")?.takeIf { it.isNotBlank() },
    label = obj.string("label").orEmpty(),
    alias = obj.string("alias")?.takeIf { it.isNotBlank() },
    disabled = obj.bool("disabled") ?: false,
)

/** The connector error's own reason, like `NEEDS_NOUS_AUTH`, which says more than the message. */
private fun reasonOf(e: RpcException): String? = ((e.data as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull

internal fun mcpServerOf(obj: JsonObject): McpServer? = McpServer(
    name = obj.string("name") ?: return null,
    transport = obj.string("transport") ?: "unknown",
    url = obj.string("url")?.takeIf { it.isNotBlank() },
    command = obj.string("command")?.takeIf { it.isNotBlank() },
    args = strings(obj["args"]).orEmpty(),
    auth = obj.string("auth"),
    enabled = obj.bool("enabled") != false,
    tools = strings(obj["tools"]),
    plugin = obj.string("plugin")?.takeIf { obj.string("source") == "plugin" },
)

internal fun mcpTestOf(obj: JsonObject): McpTest = McpTest(
    ok = obj.bool("ok") == true,
    tools = objects(obj, "tools").mapNotNull { it.string("name") },
    error = obj.string("error")?.takeIf { it.isNotBlank() },
)

internal fun mcpCatalogEntryOf(obj: JsonObject): McpCatalogEntry? = McpCatalogEntry(
    name = obj.string("name") ?: return null,
    description = obj.string("description").orEmpty(),
    source = obj.string("source")?.takeIf { it.isNotBlank() },
    transport = obj.string("transport"),
    authType = obj.string("auth_type"),
    env = objects(obj, "required_env").mapNotNull { e ->
        val name = e.string("name") ?: return@mapNotNull null
        McpEnv(name, e.string("prompt")?.takeIf { it.isNotBlank() } ?: name, e.bool("required") ?: true)
    },
    command = obj.string("command")?.takeIf { it.isNotBlank() },
    args = strings(obj["args"]).orEmpty(),
    url = obj.string("url")?.takeIf { it.isNotBlank() },
    installUrl = obj.string("install_url")?.takeIf { it.isNotBlank() },
    installRef = obj.string("install_ref")?.takeIf { it.isNotBlank() },
    bootstrap = strings(obj["bootstrap"]).orEmpty(),
    postInstall = obj.string("post_install").orEmpty(),
    installed = obj.bool("installed") ?: false,
    enabled = obj.bool("enabled") ?: false,
)

internal fun mcpAuthFlowOf(obj: JsonObject, server: String): McpAuthFlow = McpAuthFlow(
    id = obj.string("flow_id").orEmpty(),
    server = obj.string("server_name") ?: server,
    status = obj.string("status") ?: "error",
    url = obj.string("authorization_url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
    error = obj.string("error")?.takeIf { it.isNotBlank() },
    tools = (obj["tools"] as? JsonArray)?.size,
)

private fun strings(element: JsonElement?): List<String>? = (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun objects(root: JsonObject?, key: String): List<JsonObject> = (root?.get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
