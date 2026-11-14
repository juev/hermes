package org.evsyukov.hermes.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The composer parses a command exactly where the server would, and never eats prose. */
class SlashCommandsTest {

    @Test
    fun `a leading command token parses with its argument`() {
        val input = SlashCommands.parse("/help skills")
        assertThat(input).isNotNull()
        assertThat(input!!.name).isEqualTo("help")
        assertThat(input.arg).isEqualTo("skills")
        assertThat(input.typed).isEqualTo("/help skills")
    }

    @Test
    fun `names are case-insensitive and aliases resolve to the canonical name`() {
        assertThat(SlashCommands.parse("/COMPACT here 3")!!.name).isEqualTo("compress")
        assertThat(SlashCommands.parse("/reset")!!.name).isEqualTo("new")
        assertThat(SlashCommands.parse("/q list")!!.name).isEqualTo("queue")
        assertThat(SlashCommands.parse("/ctx")!!.name).isEqualTo("context")
    }

    @Test
    fun `a bare command has an empty argument and a multi-line argument is kept`() {
        assertThat(SlashCommands.parse("/stop")!!.arg).isEmpty()
        val bg = SlashCommands.parse("/bg write a poem\nabout the sea")
        assertThat(bg!!.name).isEqualTo("bg")
        assertThat(bg.arg).isEqualTo("write a poem\nabout the sea")
    }

    @Test
    fun `prose with slashes is not a command`() {
        assertThat(SlashCommands.parse("path/to/file")).isNull()
        assertThat(SlashCommands.parse("https://example.org/a/b")).isNull()
        assertThat(SlashCommands.parse(" /help")).isNull()
        assertThat(SlashCommands.parse("/path/to/file")).isNull()
        assertThat(SlashCommands.parse("/")).isNull()
        assertThat(SlashCommands.parse("")).isNull()
        assertThat(SlashCommands.parse("tell me about /restart")).isNull()
    }

    @Test
    fun `the dashboard routes the canonical verbs and sends the rest to the server`() {
        fun route(text: String) = SlashCommands.route(SlashCommands.parse(text)!!, channelMode = false)
        assertThat(route("/restart")).isEqualTo(CommandRoute.RESTART)
        assertThat(route("/stop")).isEqualTo(CommandRoute.STOP)
        assertThat(route("/new")).isEqualTo(CommandRoute.NEW_CHAT)
        assertThat(route("/reset")).isEqualTo(CommandRoute.NEW_CHAT)
        assertThat(route("/model gpt-5")).isEqualTo(CommandRoute.MODEL_PICKER)
        assertThat(route("/help")).isEqualTo(CommandRoute.SERVER_EXEC)
        assertThat(route("/status")).isEqualTo(CommandRoute.SERVER_EXEC)
        assertThat(route("/compress")).isEqualTo(CommandRoute.SERVER_EXEC)
        assertThat(route("/whoami")).isEqualTo(CommandRoute.SERVER_EXEC)
    }

    @Test
    fun `the channel offers only its stock equivalents`() {
        fun route(text: String) = SlashCommands.route(SlashCommands.parse(text)!!, channelMode = true)
        assertThat(route("/new")).isEqualTo(CommandRoute.NEW_CHAT)
        assertThat(route("/stop")).isEqualTo(CommandRoute.STOP)
        assertThat(route("/title Work")).isEqualTo(CommandRoute.RENAME)
        assertThat(route("/approve")).isEqualTo(CommandRoute.APPROVAL)
        assertThat(route("/deny")).isEqualTo(CommandRoute.APPROVAL)
        assertThat(route("/help")).isEqualTo(CommandRoute.HELP)
        assertThat(route("/restart")).isEqualTo(CommandRoute.UNAVAILABLE)
        assertThat(route("/status")).isEqualTo(CommandRoute.UNAVAILABLE)
        assertThat(route("/model")).isEqualTo(CommandRoute.UNAVAILABLE)
        assertThat(route("/compress")).isEqualTo(CommandRoute.UNAVAILABLE)
    }
}
