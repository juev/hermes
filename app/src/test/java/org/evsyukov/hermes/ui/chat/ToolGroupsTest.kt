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
    fun `a cron run that thinks before each call folds into one work block`() {
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

        val blocks = blocksOf(answer.parts)

        assertThat(blocks).hasSize(2)
        val work = blocks[0] as Block.Work
        assertThat(work.parts.first()).isEqualTo(Part.Reasoning("Смотрю логи"))
        assertThat(work.runs.map { it.id }).containsExactly("c1", "c2", "c3", "c4", "c5").inOrder()
        assertThat(work.parts.filterIsInstance<Part.Reasoning>().map { it.text }).containsExactly("Смотрю логи", "Считаю", "Проверяю").inOrder()
        assertThat(blocks[1]).isEqualTo(Block.Text("Готово"))
    }

    @Test
    fun `a turn with one call folds into one work block`() {
        assertThat(blocksOf(listOf(tool("a"), Part.Text("Готово")))).containsExactly(Block.Work(listOf(tool("a"))), Block.Text("Готово")).inOrder()
    }

    @Test
    fun `a turn with two calls folds into one work block`() {
        assertThat(blocksOf(listOf(tool("a"), tool("b")))).containsExactly(Block.Work(listOf(tool("a"), tool("b"))))
    }

    @Test
    fun `a lone reasoning folds into a work block`() {
        assertThat(blocksOf(listOf(Part.Reasoning("думаю"), Part.Text("Ответ")))).containsExactly(
            Block.Work(listOf(Part.Reasoning("думаю"))),
            Block.Text("Ответ"),
        ).inOrder()
    }

    @Test
    fun `reasoning between calls stays in its place and reasoning after the last call is kept`() {
        val parts = listOf(tool("a"), Part.Reasoning("r1"), tool("b"), Part.Reasoning("still thinking"))

        assertThat(blocksOf(parts)).containsExactly(Block.Work(parts))
    }

    @Test
    fun `a text splits the work`() {
        val blocks = blocksOf(listOf(tool("a"), tool("b"), Part.Text("Промежуточный ответ"), tool("c"), Part.Reasoning("r")))

        assertThat(blocks).containsExactly(
            Block.Work(listOf(tool("a"), tool("b"))),
            Block.Text("Промежуточный ответ"),
            Block.Work(listOf(tool("c"), Part.Reasoning("r"))),
        ).inOrder()
    }

    @Test
    fun `the parts keep the order they were produced in`() {
        val parts = listOf(Part.Reasoning("r0"), Part.Text("t1"), tool("a"), Part.Reasoning("r1"), tool("b"), Part.Text("t2"), Part.Text("t3"), tool("c"))

        val flattened = blocksOf(parts).flatMap { block ->
            when (block) {
                is Block.Text -> listOf(Part.Text(block.text))
                is Block.Work -> block.parts
            }
        }

        assertThat(flattened).containsExactlyElementsIn(parts).inOrder()
    }
}
