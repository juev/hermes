package org.evsyukov.hermes.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.evsyukov.hermes.data.remote.ServerRequest
import org.evsyukov.hermes.data.remote.string

/** What an approval asks about. The server says it only in the pattern key of the request. */
sealed interface ApprovalKind {
    /** A shell command the server flagged as dangerous, and any approval of unknown origin. */
    data object Command : ApprovalKind

    /** A plugin rule holds a call of the tool until the user allows it; the reason is the description. */
    data class Tool(val name: String) : ApprovalKind

    /**
     * An MCP server asks to confirm one action: its own question, a write by an untrusted server,
     * a spend limit, an event subscription. The server takes only yes or no and remembers nothing.
     */
    data object Mcp : ApprovalKind
}

private val pluginRuleCommand = Regex("""^<(.+)> \(plugin approval rule\)$""")

/** What the approval asks, in a notification: the command or the server's question, then the reason. */
val Prompt.Approval.summary: String get() = listOf(command, description).filter { it.isNotBlank() }.joinToString("\n")

/** The prompt of an `approval` request. */
fun approvalOf(request: ServerRequest): Prompt.Approval {
    val params = request.params
    val command = params.string("command").orEmpty()
    val patternKey = params.string("pattern_key").orEmpty()
    val kind = when {
        patternKey == "mcp_elicitation" -> ApprovalKind.Mcp
        patternKey.startsWith("plugin_rule:") ->
            ApprovalKind.Tool(pluginRuleCommand.find(command)?.groupValues?.get(1) ?: params.string("tool_name") ?: patternKey.removePrefix("plugin_rule:"))
        else -> ApprovalKind.Command
    }
    val offered = (params["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: listOf("once", "deny")
    return Prompt.Approval(
        request,
        kind = kind,
        // The command of a plugin rule is the server's label for the allowlist, not something to read.
        command = if (kind is ApprovalKind.Tool) "" else command,
        description = params.string("description") ?: params.string("tool_name").orEmpty(),
        // Every MCP answer but deny is the same yes: a choice for the chat or for always would promise what it does not keep.
        choices = if (kind == ApprovalKind.Mcp) listOf("once", "deny") else offered,
    )
}
