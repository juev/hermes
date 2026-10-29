package org.evsyukov.hermes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import org.evsyukov.hermes.ui.theme.ChatTheme

/**
 * Markdown of a reply or of a `.md` file: tables, task lists, images and highlighted code.
 * [aside] sets it smaller and dimmer, for reasoning and for what tools returned.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, aside: Boolean = false) {
    val colors = ChatTheme.colors
    Markdown(
        content = text,
        colors = markdownColor(
            text = if (aside) colors.secondaryText else colors.text,
            codeBackground = colors.codeBackground,
            inlineCodeBackground = colors.userBubble,
            dividerColor = colors.divider,
            tableBackground = colors.codeBackground,
        ),
        typography = if (aside) asideTypography() else replyTypography(),
        modifier = modifier,
        imageTransformer = Coil3ImageTransformerImpl,
        // Code blocks get the bar with the language and a copy button.
        components = markdownComponents(
            codeBlock = { model -> MarkdownHighlightedCodeBlock(content = model.content, node = model.node, style = model.typography.code, showHeader = true) },
            codeFence = { model -> MarkdownHighlightedCodeFence(content = model.content, node = model.node, style = model.typography.code, showHeader = true) },
        ),
    )
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
    )
}
