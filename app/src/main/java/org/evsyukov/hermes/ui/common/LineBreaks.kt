package org.evsyukov.hermes.ui.common

private val BLOCK_START = Regex("""^\s*([#>|]|[-*+]\s|\d+[.)]\s|```|~~~)""")

/**
 * Keeps single line breaks of a reply, as the ChatGPT app shows them: Markdown would join such
 * lines into one paragraph, so each gets a hard break. Code blocks, tables, headings, quotes and
 * list markers keep their own syntax.
 */
fun keepLineBreaks(text: String): String {
    val lines = text.split('\n')
    val out = StringBuilder()
    var inFence = false
    for ((i, line) in lines.withIndex()) {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) inFence = !inFence
        out.append(line)
        if (i == lines.lastIndex) break
        val next = lines[i + 1]
        val soft = !inFence && line.isNotBlank() && next.isNotBlank() &&
            !BLOCK_START.containsMatchIn(line) && !BLOCK_START.containsMatchIn(next) && !line.endsWith("  ")
        out.append(if (soft) "  \n" else "\n")
    }
    return out.toString()
}
