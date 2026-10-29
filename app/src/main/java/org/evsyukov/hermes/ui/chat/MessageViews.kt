package org.evsyukov.hermes.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.evsyukov.hermes.R
import java.io.File
import kotlinx.coroutines.delay
import org.evsyukov.hermes.chat.Attachment
import org.evsyukov.hermes.chat.ChatItem
import org.evsyukov.hermes.chat.ConnectionOp
import org.evsyukov.hermes.chat.Part
import org.evsyukov.hermes.chat.ToolRun
import org.evsyukov.hermes.chat.stripMedia
import org.evsyukov.hermes.ui.common.MarkdownText
import org.evsyukov.hermes.ui.common.keepLineBreaks
import org.evsyukov.hermes.ui.theme.ChatTheme

/** What a message can ask the screen to do. */
class MessageActions(
    val copy: (String) -> Unit,
    val share: (String) -> Unit,
    val speak: (String) -> Unit,
    val regenerate: () -> Unit,
    val edit: (ChatItem.User) -> Unit,
    val openFile: (String) -> Unit,
    /** Downloads a file of the server for an inline picture; null while it cannot. */
    val loadFile: suspend (String) -> File?,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserBubble(item: ChatItem.User, last: Boolean, actions: MessageActions) {
    val colors = ChatTheme.colors
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 48.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), horizontalAlignment = Alignment.End) {
        if (item.attachments.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.attachments.forEach { AttachmentThumb(it, actions) }
            }
            Spacer(Modifier.size(6.dp))
        }
        if (item.text.isNotBlank()) {
            Box {
                Text(
                    item.text,
                    color = colors.text,
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(colors.userBubble)
                        .combinedClickable(onClick = {}, onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menu = true
                        })
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_copy)) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { menu = false; actions.copy(item.text) })
                    if (last) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_edit)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; actions.edit(item) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_share)) }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { menu = false; actions.share(item.text) })
                }
            }
        }
    }
}

@Composable
private fun AttachmentThumb(attachment: Attachment, actions: MessageActions) {
    val colors = ChatTheme.colors
    val onServer = attachment.path.startsWith("/") || attachment.path.contains('/')
    if (attachment.image && onServer) {
        val file by produceState<File?>(null, attachment.path) { value = actions.loadFile(attachment.path) }
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)).background(colors.userBubble).clickable { actions.openFile(attachment.path) },
            contentAlignment = Alignment.Center,
        ) {
            val loaded = file
            if (loaded != null) AsyncImage(loaded, attachment.name, contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp))
            else Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, tint = colors.faintText)
        }
    } else {
        FileChip(attachment.name) { if (onServer) actions.openFile(attachment.path) }
    }
}

@Composable
fun FileChip(name: String, onClick: () -> Unit) {
    val colors = ChatTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, colors.composerBorder, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(colors.contrast), contentAlignment = Alignment.Center) {
            Text(name.substringAfterLast('.', "").take(4).uppercase().ifEmpty { "FILE" }, color = colors.onContrast, fontSize = 9.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(name, color = colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
    }
}

@Composable
fun AssistantMessage(
    item: ChatItem.Assistant,
    last: Boolean,
    showReasoning: Boolean,
    status: String?,
    actions: MessageActions,
    connections: List<ConnectionOp> = emptyList(),
    connectionActions: ConnectionActions? = null,
) {
    val colors = ChatTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (item.parts.isEmpty() && item.streaming) {
            TypingDot(status)
        }
        val blocks = groupTools(item.parts)
        blocks.forEachIndexed { index, block ->
            when (block) {
                is Block.Single -> when (val part = block.part) {
                    is Part.Reasoning -> ReasoningBlock(part.text, streaming = item.streaming && index == blocks.lastIndex, open = showReasoning)
                    is Part.Tool -> ToolRow(part.run)
                    is Part.Text -> {
                        val text = stripMedia(part.text)
                        if (text.isNotBlank()) SelectionContainer { MarkdownText(keepLineBreaks(text), Modifier.fillMaxWidth().padding(vertical = 4.dp)) }
                    }
                }
                is Block.Tools -> ToolGroup(block, showReasoning)
            }
        }
        // The cards of the tools of this answer, outside a folded group of tools.
        if (connectionActions != null) connections.forEach { ConnectionCard(it, connectionActions) }
        if (item.streaming && item.parts.isNotEmpty() && item.parts.last() !is Part.Text) TypingDot(status)
        val media = item.media
        if (media.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
            media.forEach { path -> MediaCard(path, actions) }
        }
        item.error?.let { error ->
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(colors.danger.copy(alpha = 0.12f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = colors.danger)
                Spacer(Modifier.width(8.dp))
                Text(error.ifBlank { stringResource(R.string.error_model) }, color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (last) IconButton(onClick = actions.regenerate) { Icon(Icons.Outlined.Refresh, stringResource(R.string.chat_retry), tint = colors.text) }
            }
        }
        if (item.interrupted) Text(stringResource(R.string.chat_stopped), color = colors.faintText, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        if (!item.streaming && item.text.isNotBlank()) ActionBar(item, last, actions)
    }
}

@Composable
private fun ActionBar(item: ChatItem.Assistant, last: Boolean, actions: MessageActions) {
    val tint = ChatTheme.colors.secondaryText
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallAction(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, stringResource(R.string.common_copy), tint) {
            actions.copy(stripMedia(item.text))
            copied = true
        }
        SmallAction(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.chat_read_aloud), tint) { actions.speak(stripMedia(item.text)) }
        SmallAction(Icons.Outlined.Share, stringResource(R.string.common_share), tint) { actions.share(stripMedia(item.text)) }
        if (last) SmallAction(Icons.Outlined.Refresh, stringResource(R.string.chat_regenerate), tint, actions.regenerate)
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) { Icon(icon, label, tint = tint, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun ReasoningBlock(text: String, streaming: Boolean, open: Boolean) {
    val colors = ChatTheme.colors
    var expanded by remember { mutableStateOf(open) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lightbulb, null, tint = colors.faintText, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (streaming) R.string.chat_reasoning_live else R.string.chat_reasoning), color = colors.secondaryText, fontSize = 14.sp)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = colors.faintText, modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(expanded) {
            MarkdownText(keepLineBreaks(text.trim()), Modifier.padding(start = 22.dp, top = 4.dp), aside = true)
        }
    }
}

@Composable
private fun ToolGroup(group: Block.Tools, showReasoning: Boolean) {
    val colors = ChatTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val runs = group.runs
    val running = runs.any { !it.done }
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = colors.secondaryText)
            else Icon(Icons.Outlined.Build, null, tint = colors.faintText, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            val names = runs.map { toolTitle(it.copy(context = "")) }.distinct().take(3).joinToString(", ")
            Text(
                stringResource(R.string.chat_tools_group, runs.size, names),
                color = colors.secondaryText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = colors.faintText, modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 12.dp)) {
                group.steps.forEach { step ->
                    when (step) {
                        is Part.Tool -> ToolRow(step.run)
                        is Part.Reasoning -> ReasoningBlock(step.text, streaming = false, open = showReasoning)
                        is Part.Text -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(run: ToolRun) {
    val colors = ChatTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (run.done) {
                Icon(Icons.Outlined.Build, null, tint = colors.faintText, modifier = Modifier.size(15.dp))
            } else {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = colors.secondaryText)
            }
            Spacer(Modifier.width(8.dp))
            Text(toolTitle(run), color = colors.secondaryText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            run.durationSeconds?.let { Text("  " + stringResource(R.string.chat_seconds, it), color = colors.faintText, fontSize = 12.sp) }
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(colors.codeBackground).padding(10.dp)
                    .heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            ) {
                val args = run.args.takeIf { it.isNotBlank() && it != "{}" }
                val summary = run.summary?.takeIf { it.isNotBlank() }
                val output = run.output
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (args == null && summary == null && output == null) Printed(stringResource(R.string.chat_no_details))
                        args?.let { Printed(it) }
                        summary?.let { Text(it, color = colors.secondaryText, fontSize = 13.sp) }
                        // Prose a tool returned reads as Markdown; what a program printed keeps its characters.
                        output?.let { if (it.markdown) MarkdownText(it.text, aside = true) else Printed(it.text) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Printed(text: String) {
    Text(
        text,
        color = ChatTheme.colors.secondaryText, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    )
}

/** A short human title for a tool call: the context the server gave, or the tool name. */
@Composable
private fun toolTitle(run: ToolRun): String {
    val title = when (run.name) {
        "terminal" -> R.string.chat_tool_terminal
        "web_search" -> R.string.chat_tool_web_search
        "web_extract" -> R.string.chat_tool_web_extract
        "read_file" -> R.string.chat_tool_read_file
        "write_file" -> R.string.chat_tool_write_file
        "patch" -> R.string.chat_tool_patch
        "search_files" -> R.string.chat_tool_search_files
        "browser_navigate" -> R.string.chat_tool_browser
        "memory" -> R.string.chat_tool_memory
        "skill_view", "skills_list" -> R.string.chat_tool_skills
        "todo" -> R.string.chat_tool_todo
        "delegate_task" -> R.string.chat_tool_delegate
        "vision_analyze" -> R.string.chat_tool_vision
        else -> null
    }
    val name = if (title != null) stringResource(title) else run.name
    return if (run.context.isNotBlank()) "$name · ${run.context}" else name
}

@Composable
private fun MediaCard(path: String, actions: MessageActions) {
    val name = path.substringAfterLast('/')
    val image = name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    if (image) {
        val file by produceState<File?>(null, path) { value = actions.loadFile(path) }
        Box(
            Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(ChatTheme.colors.userBubble).clickable { actions.openFile(path) }
                .widthIn(max = 320.dp).heightIn(min = 120.dp, max = 400.dp),
            contentAlignment = Alignment.Center,
        ) {
            val loaded = file
            if (loaded != null) AsyncImage(loaded, name, contentScale = ContentScale.Fit) else CircularProgressIndicator(Modifier.size(24.dp))
        }
    } else {
        Box(Modifier.padding(vertical = 4.dp)) { FileChip(name) { actions.openFile(path) } }
    }
}

/** The answer that has not begun yet: the typing dot and what the app waits for. */
@Composable
fun PendingReply(status: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { TypingDot(status) }
}

@Composable
private fun TypingDot(status: String?) {
    val transition = rememberInfiniteTransition(label = "typing")
    val alpha by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "alpha")
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).alpha(alpha).clip(CircleShape).background(ChatTheme.colors.text))
        status?.let {
            Spacer(Modifier.width(10.dp))
            Text(it, color = ChatTheme.colors.faintText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
