package org.evsyukov.hermes.ui.chat

import org.evsyukov.hermes.chat.Part
import org.evsyukov.hermes.chat.ToolRun

/** A run of three and more tools in a row folds into one line, as ChatGPT shows its steps. */
internal sealed interface Block {
    data class Single(val part: Part) : Block

    /** The tools of a run and the reasoning between them, in order. */
    data class Tools(val steps: List<Part>) : Block {
        val runs: List<ToolRun> get() = steps.filterIsInstance<Part.Tool>().map { it.run }
    }
}

/**
 * Groups the parts of an answer for showing. Reasoning between two tools stays in their run: a
 * model that thinks before each call, as cron jobs mostly do, would otherwise split the run into
 * single tools. Reasoning before the first tool and after the last one stays outside, so a
 * reasoning still streaming is not folded away.
 */
internal fun groupTools(parts: List<Part>): List<Block> {
    val blocks = mutableListOf<Block>()
    var run = mutableListOf<Part>()
    fun flush() {
        val tail = run.takeLastWhile { it is Part.Reasoning }
        val steps = run.dropLast(tail.size)
        if (steps.count { it is Part.Tool } >= 3) blocks += Block.Tools(steps) else steps.forEach { blocks += Block.Single(it) }
        tail.forEach { blocks += Block.Single(it) }
        run = mutableListOf()
    }
    for (part in parts) {
        if (part is Part.Tool || part is Part.Reasoning && run.isNotEmpty()) run += part else {
            flush()
            blocks += Block.Single(part)
        }
    }
    flush()
    return blocks
}
