package org.evsyukov.hermes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.evsyukov.hermes.ui.theme.ChatTheme

/** Markdown of a reply or of a `.md` file: tables, task lists, images and highlighted code. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val colors = ChatTheme.colors
    Markdown(
        content = text,
        colors = markdownColor(
            text = colors.text,
            codeBackground = colors.codeBackground,
            inlineCodeBackground = colors.userBubble,
            dividerColor = colors.divider,
            tableBackground = colors.codeBackground,
        ),
        typography = markdownTypography(
            h1 = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
            h2 = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            h3 = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
            h4 = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            h5 = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
            h6 = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
            text = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            paragraph = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        ),
        modifier = modifier,
        imageTransformer = Coil3ImageTransformerImpl,
        // Code blocks get the bar with the language and a copy button.
        components = markdownComponents(
            codeBlock = { model -> MarkdownHighlightedCodeBlock(content = model.content, node = model.node, style = model.typography.code, showHeader = true) },
            codeFence = { model -> MarkdownHighlightedCodeFence(content = model.content, node = model.node, style = model.typography.code, showHeader = true) },
        ),
    )
}
