package org.evsyukov.hermes.ui.chat

import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.chat.ChatItem
import org.evsyukov.hermes.chat.Part
import org.evsyukov.hermes.chat.ToolRun
import org.evsyukov.hermes.chat.transcriptOf
import org.evsyukov.hermes.data.remote.StoredMessage
import org.evsyukov.hermes.data.remote.ToolCallInfo
import org.junit.Test

class ToolGroupsTest {
    private fun tool(id: String) = Part.Tool(ToolRun(id, "terminal", "", "", done = true))

    @Test
    fun `a cron run that thinks before each call folds into one group`() {
        // The shape of a stored cron run: every step is an assistant message with reasoning and a call, no text.
        var row = 0L
        fun step(id: String, reasoning: String?) = listOf(
            StoredMessage(++row, "assistant", "", reasoning, listOf(ToolCallInfo(id, "terminal", "{}")), null, null, row.toDouble(), false, emptyList()),
            StoredMessage(++row, "tool", "ok", null, emptyList(), "terminal", id, row.toDouble(), false, emptyList()),
        )
        val messages = listOf(StoredMessage(++row, "user", "[cron prompt]", null, emptyList(), null, null, 0.0, false, emptyList())) +
            step("c1", "Смотрю логи") + step("c2", null) + step("c3", "Считаю") + step("c4", null) + step("c5", "Проверяю") +
            listOf(StoredMessage(++row, "assistant", "Готово", null, emptyList(), null, null, row.toDouble(), false, emptyList()))
        val answer = transcriptOf(messages).last() as ChatItem.Assistant

        val blocks = groupTools(answer.parts)

        assertThat(blocks).hasSize(3)
        assertThat(blocks[0]).isEqualTo(Block.Single(Part.Reasoning("Смотрю логи")))
        assertThat((blocks[1] as Block.Tools).runs.map { it.id }).containsExactly("c1", "c2", "c3", "c4", "c5").inOrder()
        assertThat(blocks[2]).isEqualTo(Block.Single(Part.Text("Готово")))
    }

    @Test
    fun `reasoning between tools stays in the group in its place`() {
        val blocks = groupTools(listOf(tool("a"), Part.Reasoning("r1"), tool("b"), tool("c")))

        assertThat(blocks).containsExactly(Block.Tools(listOf(tool("a"), Part.Reasoning("r1"), tool("b"), tool("c"))))
    }

    @Test
    fun `reasoning after the last tool stays outside the group`() {
        val blocks = groupTools(listOf(tool("a"), tool("b"), tool("c"), Part.Reasoning("still thinking")))

        assertThat(blocks).containsExactly(
            Block.Tools(listOf(tool("a"), tool("b"), tool("c"))),
            Block.Single(Part.Reasoning("still thinking")),
        ).inOrder()
    }

    @Test
    fun `text between tools splits the run`() {
        val blocks = groupTools(listOf(tool("a"), tool("b"), Part.Text("Промежуточный ответ"), tool("c"), tool("d")))

        assertThat(blocks.none { it is Block.Tools }).isTrue()
        assertThat(blocks).hasSize(5)
    }

    @Test
    fun `fewer than three tools stay single with their reasoning`() {
        val blocks = groupTools(listOf(tool("a"), Part.Reasoning("r"), tool("b")))

        assertThat(blocks).containsExactly(Block.Single(tool("a")), Block.Single(Part.Reasoning("r")), Block.Single(tool("b"))).inOrder()
    }
}
