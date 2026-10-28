package org.evsyukov.hermes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
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
        modifier = modifier,
        imageTransformer = Coil3ImageTransformerImpl,
        components = markdownComponents(
            codeBlock = highlightedCodeBlock,
            codeFence = highlightedCodeFence,
        ),
    )
}
