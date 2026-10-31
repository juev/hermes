package org.evsyukov.hermes.ui.chat

import org.evsyukov.hermes.chat.Part
import org.evsyukov.hermes.chat.ToolRun

/** The words of an answer and the work it did between them, as the desktop app shows a turn. */
internal sealed interface Block {
    data class Text(val text: String) : Block

    /** Everything the answer did between two pieces of text: its reasoning and its tool runs, in order. */
    data class Work(val parts: List<Part>) : Block {
        val runs: List<ToolRun> get() = parts.filterIsInstance<Part.Tool>().map { it.run }
        val reasoning: Boolean get() = parts.any { it is Part.Reasoning }
    }
}

/**
 * Groups the parts of an answer for showing. Reasoning and calls between two pieces of text fold
 * into one line whatever their number: the answer is what the person came to read, and one call
 * shown as a row of its own would push it down as much as ten.
 */
internal fun blocksOf(parts: List<Part>): List<Block> {
    val blocks = mutableListOf<Block>()
    var work = mutableListOf<Part>()
    for (part in parts) {
        if (part is Part.Text) {
            if (work.isNotEmpty()) blocks += Block.Work(work)
            work = mutableListOf()
            blocks += Block.Text(part.text)
        } else {
            work += part
        }
    }
    if (work.isNotEmpty()) blocks += Block.Work(work)
    return blocks
}
