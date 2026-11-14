package org.evsyukov.hermes.chat

/**
 * The composer text that is a Hermes command, not a prompt: a leading `/name` token with the rest
 * as its argument. Prose that merely contains a slash (`path/to/file`, a URL, an indented line)
 * is not a command, and a command with attachments stays an ordinary prompt — the server's
 * command paths take text only.
 */
data class SlashInput(val name: String, val arg: String, val typed: String)

/** Where a recognized command goes on a connection mode; the server still decides permission. */
enum class CommandRoute {
    /** The gateway restart: dashboard admin route with the force semantics, recovery and no blind retry. */
    RESTART,

    /** Interrupt the running turn through the connection's own interrupt. */
    STOP,

    /** Open a fresh chat, as the desktop surface does for /new. */
    NEW_CHAT,

    /** Open the chat's model picker, as the desktop surface does for /model. */
    MODEL_PICKER,

    /** The dashboard's reviewed slash.exec path; the server answers exec/send/skill or refuses. */
    SERVER_EXEC,

    /** Rename this chat through the connection's title route (channel). */
    RENAME,

    /** Answer the pending approval (channel): /approve and /deny. */
    APPROVAL,

    /** The client's own command catalog (channel). */
    HELP,

    /** No supported path on this transport; reported, never sent to the model. */
    UNAVAILABLE,
}

object SlashCommands {
    private val COMMAND = Regex("^/([A-Za-z][A-Za-z0-9_-]*)(?:[ \t]+([\\s\\S]*))?$")

    /** Aliases resolve to the canonical registry name, as the server's resolve_command does. */
    private val ALIASES = mapOf(
        "reset" to "new", "compact" to "compress", "q" to "queue", "s" to "steer",
        "ctx" to "context", "tasks" to "agents", "fork" to "branch",
        "hb" to "heartbeat", "proactive" to "loop", "snap" to "snapshot",
        "set-home" to "sethome", "gateway" to "platforms", "upgrade" to "subscription",
        "v" to "version", "sb" to "statusbar", "ts" to "timestamps", "compose" to "prompt",
        "learning" to "journey", "memory-graph" to "journey", "codex_runtime" to "codex-runtime",
        "reload_mcp" to "reload-mcp", "reload_skills" to "reload-skills", "exit" to "quit",
        "suggest" to "suggestions", "generate-pet" to "hatch", "bp" to "blueprint",
    )

    fun parse(text: String): SlashInput? {
        val match = COMMAND.matchEntire(text) ?: return null
        val name = match.groupValues[1].lowercase()
        val arg = match.groupValues[2].trim()
        return SlashInput(ALIASES[name] ?: name, arg, text)
    }

    /**
     * The route of a parsed command on a connection mode. Discovery is not permission: a routed
     * command still meets the server's authorization and busy policy, and a refusal is shown.
     */
    fun route(input: SlashInput, channelMode: Boolean): CommandRoute = when {
        channelMode -> when (input.name) {
            "new" -> CommandRoute.NEW_CHAT
            "stop" -> CommandRoute.STOP
            "title" -> CommandRoute.RENAME
            "approve", "deny" -> CommandRoute.APPROVAL
            "help" -> CommandRoute.HELP
            else -> CommandRoute.UNAVAILABLE
        }
        input.name == "restart" -> CommandRoute.RESTART
        input.name == "stop" -> CommandRoute.STOP
        input.name == "new" -> CommandRoute.NEW_CHAT
        input.name == "model" -> CommandRoute.MODEL_PICKER
        else -> CommandRoute.SERVER_EXEC
    }
}
