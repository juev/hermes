package org.evsyukov.hermes.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownDimens
import org.evsyukov.hermes.ui.theme.ChatTheme
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * Markdown of a reply or of a `.md` file: tables, task lists, images and highlighted code.
 * [aside] sets it smaller and dimmer, for reasoning and for what tools returned. [inBubble] gives
 * code the page background, which stands out from the bubble of a user message.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, aside: Boolean = false, inBubble: Boolean = false) {
    val colors = ChatTheme.colors
    val code = if (inBubble) colors.background else colors.codeBackground
    Markdown(
        content = text,
        colors = markdownColor(
            text = if (aside) colors.secondaryText else colors.text,
            codeBackground = code,
            inlineCodeBackground = if (inBubble) colors.background else colors.userBubble,
            dividerColor = colors.divider,
            tableBackground = code,
        ),
        typography = if (aside) asideTypography() else replyTypography(),
        modifier = modifier,
        dimens = markdownDimens(tableCellPadding = 8.dp),
        imageTransformer = Coil3ImageTransformerImpl,
        // Code blocks get the bar with the language and a copy button.
        components = markdownComponents(
            codeBlock = { model -> MarkdownHighlightedCodeBlock(content = model.content, node = model.node, style = model.typography.code, showHeader = true) },
            codeFence = { model -> MarkdownHighlightedCodeFence(content = model.content, node = model.node, style = model.typography.code, showHeader = true) },
            table = { model -> FittedTable(model.content, model.node, model.typography.table) },
        ),
    )
}

/**
 * A table laid out the way a browser lays one out: no column is narrower than its longest word,
 * the room left goes to the columns with more text, and a table whose words do not fit the
 * screen scrolls sideways. The library gives every column the same width, which breaks words
 * in the middle of a narrow column and leaves a column of short words half empty.
 */
@Composable
private fun FittedTable(content: String, node: ASTNode, style: TextStyle) {
    val padding = LocalMarkdownDimens.current.tableCellPadding
    val headerStyle = style.copy(fontWeight = FontWeight.Bold)
    val settings = annotatorSettings()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val rows = remember(node) { node.children.filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW } }
    val cells = remember(rows) { rows.map { row -> row.children.filter { it.type == GFMTokenTypes.CELL } } }
    // The longest word and the whole text of each column, in pixels with the padding of a cell.
    val (least, most) = remember(content, cells, style) {
        val pad = with(density) { (padding * 2).roundToPx() }
        val columns = cells.maxOfOrNull { it.size } ?: 0
        val least = IntArray(columns)
        val most = IntArray(columns)
        cells.forEachIndexed { r, row ->
            row.forEachIndexed { c, cell ->
                val text = buildAnnotatedString { buildMarkdownAnnotatedString(content, cell, settings) }.text.trim()
                val cellStyle = if (r == 0) headerStyle else style
                fun width(s: String) = measurer.measure(s, cellStyle, softWrap = false, maxLines = 1).size.width + pad
                least[c] = maxOf(least[c], text.split(WHITESPACE).maxByOrNull { it.length }?.let(::width) ?: pad)
                most[c] = maxOf(most[c], width(text))
            }
        }
        least to most
    }
    BoxWithConstraints(Modifier.background(LocalMarkdownColors.current.tableBackground, RoundedCornerShape(LocalMarkdownDimens.current.tableCornerSize))) {
        val widths = columnWidths(least, most, constraints.maxWidth)
        Column(if (widths.sum() > constraints.maxWidth) Modifier.horizontalScroll(rememberScrollState()) else Modifier) {
            node.children.forEach { child ->
                val r = rows.indexOf(child)
                if (child.type == GFMTokenTypes.TABLE_SEPARATOR) MarkdownDivider()
                if (r < 0) return@forEach
                Row(Modifier.height(IntrinsicSize.Max)) {
                    cells[r].forEachIndexed { c, cell ->
                        Box(Modifier.width(with(density) { widths[c].toDp() }).padding(padding)) {
                            MarkdownTableBasicText(content, cell, if (r == 0) headerStyle else style, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
                        }
                    }
                }
            }
        }
    }
}

private val WHITESPACE = Regex("""\s+""")

/**
 * Widths of table columns in [available] pixels from the width of the longest word ([least]) and
 * of the whole text ([most]) of each column. A table whose text fits on one line in every column
 * stretches all columns alike; otherwise each column gets its longest word and the rest of the
 * room in proportion to the text it still has to wrap. A word wider than half the room may break,
 * so that one long link does not push the table off the screen. When the words alone do not fit,
 * the widths add up to more than [available] and the table scrolls.
 */
internal fun columnWidths(least: IntArray, most: IntArray, available: Int): IntArray {
    val min = IntArray(least.size) { minOf(least[it], available / 2) }
    val max = IntArray(most.size) { maxOf(most[it], min[it]) }
    val spare = available - min.sum()
    if (spare <= 0) return min
    if (max.sum() <= available) {
        val total = max.sum()
        return share(available, IntArray(max.size) { max[it] * available / total })
    }
    val wrap = IntArray(max.size) { max[it] - min[it] }
    val total = wrap.sum()
    return share(available, IntArray(min.size) { min[it] + (wrap[it].toLong() * spare / total).toInt() })
}

/** Gives the pixels lost to rounding to the last column, so the widths add up to [available]. */
private fun share(available: Int, widths: IntArray): IntArray {
    if (widths.isNotEmpty()) widths[widths.lastIndex] += available - widths.sum()
    return widths
}

@Composable
private fun replyTypography() = markdownTypography(
    h1 = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    h2 = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    h3 = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    h4 = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    h5 = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    h6 = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    text = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    paragraph = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    // A table holds several columns on a phone, so its text is a step smaller than the reply.
    table = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
)

/** The size of the reasoning and tool rows: headings barely above the text. */
@Composable
private fun asideTypography(): MarkdownTypography {
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val heading = body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    return markdownTypography(
        h1 = heading, h2 = heading, h3 = heading, h4 = heading, h5 = heading, h6 = heading,
        text = body,
        code = body.copy(fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        table = body,
    )
}
