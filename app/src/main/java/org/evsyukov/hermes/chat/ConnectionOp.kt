package org.evsyukov.hermes.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.evsyukov.hermes.data.remote.bool
import org.evsyukov.hermes.data.remote.double
import org.evsyukov.hermes.data.remote.long
import org.evsyukov.hermes.data.remote.string

/**
 * An open connection operation of the agent: accounts to connect, MCP servers, catalog plugins and
 * skills to install. The server owns every state; the card shows the last snapshot it sent and
 * answers through `connection.respond`.
 */
data class ConnectionOp(
    val opId: String,
    /** The tool call that opened the operation; the card stands under the answer that made it. */
    val toolCallId: String,
    /** The write counter of the snapshot; an older frame changes nothing. */
    val seq: Long,
    /** Unix seconds. */
    val deadlineAt: Double,
    val targets: List<ConnectionTarget>,
    val settled: Boolean = false,
    /** `all_resolved`, `continue`, `deadline` or `interrupt`. */
    val settledBy: String? = null,
    /** A directory card: no targets, these services suggested. */
    val suggested: List<String>? = null,
    /** Rows whose sign-in link the user opened on this phone; they wait for the browser. */
    val opened: Set<String> = emptySet(),
) {
    /** Catalog installs, as opposed to accounts and MCP servers. */
    val catalog: Boolean get() = targets.any { it.catalog != null }

    /** A target waits for the browser: the user may come back from signing in. */
    val awaitsBrowser: Boolean get() = !settled && targets.any { it.state == TargetState.INITIATED }
}

/** One row of the card: what the server knows about the account, server, plugin or skill. */
data class ConnectionTarget(
    val name: String,
    /** `connector`, `mcp`, `plugin` or `skill`; an unknown kind shows as an MCP row. */
    val kind: String,
    /** `authorize`, `connect`, `enable`, `install` or `reconnect`. */
    val action: String,
    val state: TargetState,
    val resolved: Boolean = false,
    val detail: String = "",
    val instructions: String? = null,
    val discoveryError: String? = null,
    val connectUrl: String? = null,
    /** The vendor account the server created or saw; empty until it names one. */
    val connectionId: String = "",
    /** The name of the account, like `work` or `home`. */
    val alias: String? = null,
    val requiredEnv: List<EnvField> = emptyList(),
    val tools: List<String> = emptyList(),
    val hint: String? = null,
    /** Only on `plugin` and `skill` rows. */
    val catalog: CatalogEntry? = null,
)

enum class TargetState(val wire: String) {
    PENDING("pending"),
    INITIATED("initiated"),
    CONNECTED("connected"),
    SKIPPED("skipped"),
    FAILED("failed"),
    EXPIRED("expired"),
    NOT_CONNECTED("not_connected"),
    ;

    companion object {
        fun of(wire: String?): TargetState = entries.firstOrNull { it.wire == wire } ?: PENDING
    }
}

/** A credential an MCP server or a plugin still needs; the card asks for it before the install. */
data class EnvField(val name: String, val required: Boolean, val secret: Boolean, val default: String, val prompt: String?)

/** What the catalog says about a plugin or a skill. */
data class CatalogEntry(
    val display: String,
    val description: String = "",
    /** `official`, `verified` or `community`. */
    val tier: String? = null,
    /** Empty when it runs everywhere. */
    val platforms: List<String> = emptyList(),
    val repo: String? = null,
    val sha: String? = null,
    val subdir: String? = null,
    /** `passed`, `warnings` or `failed`, with the summary of the scan. */
    val scan: Pair<String, String>? = null,
    val requiresHermes: String? = null,
    val hasDesktopHalf: Boolean = false,
    val targetProfile: String? = null,
    /** `present`, `missing_app`, `app_not_running` or `unknown`. */
    val appState: String? = null,
    /** On an installed skill: the name the agent loads it by. */
    val skill: String? = null,
    /** The step of an install under way. */
    val phase: String? = null,
    val approved: Approved? = null,
    val enabled: Boolean? = null,
    val missingEnv: List<String> = emptyList(),
    /** MCP servers the plugin brought that did not connect, with the reason. */
    val serverErrors: List<Pair<String, String>> = emptyList(),
    val alreadyInstalled: Boolean = false,
)

/** The advanced choices of a catalog install; a retry repeats them. */
data class Approved(val force: Boolean = false, val enable: Boolean = true, val ref: String? = null)

/**
 * The answer of the card for one row. [env] carries the credentials an install asked for and, for a
 * catalog install, the advanced choices as strings, the way the server reads them.
 */
data class TargetAnswer(val name: String, val approved: Boolean, val env: Map<String, String> = emptyMap())

/** An app of the directory card: connected to the account or not, and how its last connection went. */
data class ConnectorApp(val slug: String, val connected: Boolean, val status: String?)

/**
 * The operation of a `connection.request` event or of `pending_connection` in a resumed session;
 * null when it has no id, call, deadline or anything to show.
 */
fun connectionOf(payload: JsonObject?): ConnectionOp? {
    payload ?: return null
    val opId = payload.string("op_id")?.takeIf { it.isNotBlank() } ?: return null
    val toolCallId = payload.string("tool_call_id")?.takeIf { it.isNotBlank() } ?: return null
    val deadline = payload.double("deadline_at")?.takeIf { it > 0 } ?: return null
    val targets = targetsOf(payload)
    val suggested = (payload["display"] as? JsonObject)?.let { strings(it["suggested"]) }
    if (targets.isEmpty() && suggested == null) return null
    return ConnectionOp(opId, toolCallId, payload.long("seq") ?: 0, deadline, targets, suggested = suggested)
}

/**
 * Lays a `connection.update` frame or a `connectors.operation.status` answer over the operation. A
 * frame of another operation, one older than what the card shows, or one after the settlement
 * changes nothing; every frame carries all targets.
 */
fun ConnectionOp.applying(snapshot: JsonObject): ConnectionOp {
    if (snapshot.string("op_id") != opId || settled) return this
    val seq = snapshot.long("seq") ?: return this
    if (seq <= this.seq) return this
    val live = (snapshot["targets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.associateBy { it.string("name")?.trim() }
    return copy(
        seq = seq,
        deadlineAt = snapshot.double("deadline_at") ?: deadlineAt,
        settled = snapshot.bool("settled") ?: false,
        settledBy = snapshot.string("settled_by"),
        targets = targets.map { old ->
            val raw = live[old.name] ?: return@map old
            val new = targetOf(raw) ?: return@map old
            // A frame without some fields keeps what the card knew of them, like the link once minted.
            new.copy(
                kind = old.kind,
                connectUrl = new.connectUrl ?: old.connectUrl,
                connectionId = new.connectionId.ifEmpty { old.connectionId },
                requiredEnv = if ("required_env" in raw) new.requiredEnv else old.requiredEnv,
                tools = if ("tools" in raw) new.tools else old.tools,
            )
        },
    )
}

private fun targetsOf(payload: JsonObject): List<ConnectionTarget> =
    (payload["targets"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::targetOf) }.orEmpty()

private fun targetOf(obj: JsonObject): ConnectionTarget? {
    val name = obj.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val kind = obj.string("kind")?.takeIf { it in KINDS } ?: "mcp"
    return ConnectionTarget(
        name = name,
        kind = kind,
        action = obj.string("action") ?: "install",
        state = TargetState.of(obj.string("state")),
        resolved = obj.bool("resolved") ?: false,
        detail = obj.string("detail").orEmpty(),
        instructions = obj.string("instructions"),
        discoveryError = obj.string("discovery_error"),
        connectUrl = obj.string("connect_url")?.takeIf { it.isNotBlank() },
        connectionId = obj.string("connection_id").orEmpty(),
        alias = obj.string("alias")?.takeIf { it.isNotBlank() },
        requiredEnv = (obj["required_env"] as? JsonArray)?.mapNotNull { field ->
            val f = field as? JsonObject ?: return@mapNotNull null
            EnvField(
                name = f.string("name") ?: return@mapNotNull null,
                required = f.bool("required") ?: false,
                secret = f.bool("secret") ?: false,
                default = f.string("default").orEmpty(),
                prompt = f.string("prompt")?.takeIf { it.isNotBlank() },
            )
        }.orEmpty(),
        tools = strings(obj["tools"]).orEmpty(),
        hint = obj.string("hint")?.takeIf { it.isNotBlank() },
        catalog = if (kind == "plugin" || kind == "skill") catalogOf(obj, name) else null,
    )
}

private fun catalogOf(obj: JsonObject, name: String) = CatalogEntry(
    display = obj.string("display")?.trim()?.takeIf { it.isNotEmpty() } ?: name,
    description = obj.string("description").orEmpty(),
    tier = obj.string("tier"),
    platforms = strings(obj["platforms"]).orEmpty(),
    repo = obj.string("repo")?.takeIf { it.isNotBlank() },
    sha = obj.string("sha")?.takeIf { it.isNotBlank() },
    subdir = obj.string("subdir")?.takeIf { it.isNotBlank() },
    scan = (obj["scan"] as? JsonObject)?.let { scan -> scan.string("status")?.let { it to scan.string("summary").orEmpty() } },
    requiresHermes = obj.string("requires_hermes")?.takeIf { it.isNotBlank() },
    hasDesktopHalf = obj.bool("has_desktop_half") ?: false,
    targetProfile = obj.string("target_profile")?.trim()?.takeIf { it.isNotEmpty() },
    appState = obj.string("app_state"),
    skill = obj.string("skill")?.takeIf { it.isNotBlank() },
    phase = obj.string("phase"),
    approved = (obj["approved"] as? JsonObject)?.let { Approved(it.bool("force") ?: false, it.bool("enable") ?: true, it.string("ref")?.takeIf { r -> r.isNotBlank() }) },
    enabled = obj.bool("enabled"),
    missingEnv = strings(obj["missing_env"]).orEmpty(),
    serverErrors = (obj["server_errors"] as? JsonArray)?.mapNotNull { e ->
        val err = e as? JsonObject ?: return@mapNotNull null
        (err.string("name") ?: return@mapNotNull null) to err.string("error").orEmpty()
    }.orEmpty(),
    alreadyInstalled = obj.bool("already_installed") ?: false,
)

private fun strings(element: kotlinx.serialization.json.JsonElement?): List<String>? =
    (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private val KINDS = setOf("connector", "mcp", "plugin", "skill")
