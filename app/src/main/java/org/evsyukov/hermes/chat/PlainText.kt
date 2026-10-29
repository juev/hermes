package org.evsyukov.hermes.chat

private val FENCE = Regex("""(?m)^[\t ]*(```|~~~).*$\n?""")
private val RULE = Regex("""(?m)^[\t ]*([-*_])([\t ]*\1){2,}[\t ]*$""")
private val TABLE_DIVIDER = Regex("""(?m)^[\t ]*\|?[\t ]*:?-+:?[\t ]*(\|[\t ]*:?-+:?[\t ]*)+\|?[\t ]*$\n?""")
private val TABLE_EDGES = Regex("""(?m)^[\t ]*\|(.*?)\|?[\t ]*$""")
private val HEADING = Regex("""(?m)^[\t ]*#{1,6}[\t ]+(.*?)[\t ]*#*[\t ]*$""")
private val QUOTE = Regex("""(?m)^[\t ]*>[\t ]?""")
private val TASK = Regex("""(?m)^([\t ]*)[-*+][\t ]+\[[ xX]][\t ]+""")
private val BULLET = Regex("""(?m)^([\t ]*)[-*+][\t ]+""")
private val IMAGE = Regex("""!\[([^\]]*)]\([^)]*\)""")
private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
private val CODE = Regex("""`([^`\n]+)`""")
private val STRONG = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")
private val STRIKE = Regex("""~~(?=\S)(.+?)(?<=\S)~~""")
private val EMPHASIS_STAR = Regex("""(?<![*\w])\*(?=\S)([^*\n]+?)(?<=\S)\*(?![*\w])""")
private val EMPHASIS_UNDERSCORE = Regex("""(?<![_\w])_(?=\S)([^_\n]+?)(?<=\S)_(?![_\w])""")
private val BLANK_LINES = Regex("""\n{3,}""")

/**
 * Markdown as plain text for places that cannot render it: notifications and search snippets.
 * The words stay, the syntax goes: emphasis, headings, quotes, link targets, fences and table
 * borders. List items keep a bullet.
 */
fun plainText(markdown: String): String {
    var text = markdown.replace("\r\n", "\n")
    text = FENCE.replace(text, "")
    text = TABLE_DIVIDER.replace(text, "")
    text = RULE.replace(text, "")
    text = TABLE_EDGES.replace(text) { it.groupValues[1].trim().replace(Regex("""[\t ]*\|[\t ]*"""), " · ") }
    text = HEADING.replace(text) { it.groupValues[1] }
    text = QUOTE.replace(text, "")
    text = TASK.replace(text) { it.groupValues[1] + "• " }
    text = BULLET.replace(text) { it.groupValues[1] + "• " }
    text = IMAGE.replace(text) { it.groupValues[1] }
    text = LINK.replace(text) { it.groupValues[1] }
    text = CODE.replace(text) { it.groupValues[1] }
    text = STRONG.replace(text) { it.groupValues[2] }
    text = STRIKE.replace(text) { it.groupValues[1] }
    text = EMPHASIS_STAR.replace(text) { it.groupValues[1] }
    text = EMPHASIS_UNDERSCORE.replace(text) { it.groupValues[1] }
    return BLANK_LINES.replace(text, "\n\n").trim()
}
