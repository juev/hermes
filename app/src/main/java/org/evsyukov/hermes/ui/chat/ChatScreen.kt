package org.evsyukov.hermes.ui.chat

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.evsyukov.hermes.R
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.evsyukov.hermes.chat.ChatController
import org.evsyukov.hermes.chat.ChatItem
import org.evsyukov.hermes.chat.LocalAttachment
import org.evsyukov.hermes.chat.Part
import org.evsyukov.hermes.chat.QuestionState
import org.evsyukov.hermes.chat.TranscriptRow
import org.evsyukov.hermes.chat.transcriptRows
import org.evsyukov.hermes.data.remote.ModelChoices
import org.evsyukov.hermes.data.remote.SessionUsage
import org.evsyukov.hermes.data.settings.Settings
import org.evsyukov.hermes.ui.sidebar.RenameDialog
import org.evsyukov.hermes.ui.theme.ChatTheme

private val SUGGESTIONS = listOf(
    R.string.chat_suggestion_load,
    R.string.chat_suggestion_cron,
    R.string.chat_suggestion_news,
    R.string.chat_suggestion_plan,
)

@Composable
fun ChatScreen(
    controller: ChatController,
    settings: Settings,
    serverName: String,
    connected: Boolean,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    openFile: (String) -> Unit,
    loadFile: suspend (String) -> File?,
    loadModels: suspend () -> ModelChoices,
    openLocal: (LocalAttachment) -> Unit,
    /** The screen of [controller] came up in an app in front (true) or went away (false). */
    onShown: (ChatController, Boolean) -> Unit,
    /** The words, files and place of [controller], kept by the app while the chat is open. */
    draft: ChatDraft,
    shared: Shared? = null,
    onSharedTaken: () -> Unit = {},
) {
    // Below STARTED — the app in the background — the screen takes no new state; it takes the
    // latest when it comes back, not every step in between.
    val state by controller.state.collectAsStateWithLifecycle()
    // The chat works on its transcript only while this screen is up in an app in front; a screen
    // over it or another chat disposes this one, and the chat stops as well.
    LifecycleStartEffect(controller) {
        onShown(controller, true)
        onStopOrDispose { onShown(controller, false) }
    }
    val colors = ChatTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ChatItem.User?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    // A chat opens to read it, as in ChatGPT: the keyboard waits for a tap on the field.
    val focus = LocalFocusManager.current
    LaunchedEffect(controller) { focus.clearFocus() }

    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) { onDispose { tts.value?.shutdown() } }

    // The list is laid out from the bottom: a chat opens at its end, and a growing answer or the
    // keyboard keeps the last message in view without scrolling by hand.
    // The place in the chat comes from its draft, so a screen over the chat brings the reader back to it.
    val listState = remember(controller) { LazyListState(draft.scrollIndex, draft.scrollOffset) }
    DisposableEffect(controller) {
        onDispose {
            draft.scrollIndex = listState.firstVisibleItemIndex
            draft.scrollOffset = listState.firstVisibleItemScrollOffset
        }
    }
    val atBottom by remember { derivedStateOf { !listState.canScrollBackward } }
    // The rows are reversed with their day separators, so a separator stays above the messages of its day.
    val reversed = remember(state.items, state.questions) { transcriptRows(state.items, questions = state.questions).asReversed() }
    // Only the last message of the chat can be answered again, and only the last of the user edited.
    val lastAnswer = (state.items.lastOrNull() as? ChatItem.Assistant)?.key
    val lastUser = state.items.lastOrNull { it is ChatItem.User }?.key
    // A new message or question joins the reader who is at the end; one who scrolled up stays put, and
    // a screen that comes back to the messages it showed keeps its place. The place is read from the
    // list's position rather than [atBottom], which knows nothing before the first layout.
    val shownRows = state.items.size + state.questions.size
    LaunchedEffect(controller, shownRows) {
        val seen = draft.shownItems
        draft.shownItems = shownRows
        if (seen == shownRows) return@LaunchedEffect
        val atEnd = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        if (atEnd || state.items.lastOrNull() is ChatItem.User) listState.scrollToItem(0)
    }

    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val pull = remember(controller) {
        PullForNewChat(
            threshold,
            onTrigger = onNewChat,
            onArmed = { if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
        )
    }
    pull.enabled = settings.pullForNewChat && state.items.isNotEmpty()

    fun send(text: String = draft.text.trim()) {
        if (text.isEmpty() && draft.attachments.isEmpty()) return
        if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        controller.send(text, draft.attachments)
        draft.text = ""
        draft.attachments = emptyList()
        draft.fromShare = false
    }

    // A share fills the field of the empty new chat and offers quick actions until a message goes.
    LaunchedEffect(controller, shared) {
        val current = controller.state.value
        if (shared == null || current.storedId != null || current.items.isNotEmpty()) return@LaunchedEffect
        draft.text = listOf(draft.text.trim(), shared.text).filter { it.isNotEmpty() }.joinToString("\n")
        draft.attachments = draft.attachments + shared.attachments
        draft.fromShare = true
        onSharedTaken()
    }
    val quickActions = draft.fromShare && state.items.isEmpty() && (draft.text.isNotBlank() || draft.attachments.isNotEmpty())

    // The server gives the phone no link back from the browser; coming back to the app is the sign.
    LifecycleResumeEffect(controller) {
        controller.browserReturned()
        onPauseOrDispose {}
    }
    fun openUrl(url: String) = CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
    val connectionActions = ConnectionActions(
        answer = controller::answerConnection,
        proceed = controller::continueConnection,
        retry = controller::retryConnection,
        reinstall = controller::reinstallPlugin,
        openLink = { opId, name, url ->
            controller.linkOpened(opId, name)
            openUrl(url)
        },
        directory = controller::connectorDirectory,
        connectApp = controller::connectApp,
        openUrl = ::openUrl,
    )
    // A card stands under the answer whose tool opened it; one whose answer is not in the chat stands at the end.
    val cardsOf = remember(state.items, state.connections) {
        val owners = state.items.filterIsInstance<ChatItem.Assistant>().flatMap { item ->
            item.parts.filterIsInstance<Part.Tool>().map { it.run.id to item.key }
        }.toMap()
        state.connections.groupBy { owners[it.toolCallId] }
    }

    // A question that waits off the screen is announced over the chat rather than scrolled to: the
    // reader of older messages stays where they are until they ask for it. A sliver at the edge does
    // not count as seen.
    val waitingKeys = remember(state.questions) { state.questions.filter { it.state == QuestionState.PENDING }.map { "question:${it.id}" }.toSet() }
    val seenPart = with(LocalDensity.current) { 48.dp.toPx() }
    val questionAway by remember(waitingKeys) {
        derivedStateOf {
            val layout = listState.layoutInfo
            waitingKeys.isNotEmpty() && layout.visibleItemsInfo.none { item ->
                val shown = minOf(item.offset + item.size, layout.viewportEndOffset) - maxOf(item.offset, layout.viewportStartOffset)
                item.key in waitingKeys && shown >= minOf(item.size.toFloat(), seenPart)
            }
        }
    }
    // The rows before the transcript in the list: the waiting reply and the cards no answer holds.
    val leading = (if (state.awaitingAnswer) 1 else 0) + cardsOf[null].orEmpty().size
    val waitingAt = reversed.indexOfFirst { it is TranscriptRow.Ask && it.card.state == QuestionState.PENDING }.let { if (it < 0) -1 else leading + it }
    // A card settled by its own button gets shorter: its end, with how it ended, stays in view rather
    // than slipping under the edge of the list.
    fun keepEndInView(id: String) {
        val at = reversed.indexOfFirst { it is TranscriptRow.Ask && it.card.id == id }.takeIf { it >= 0 }?.let { leading + it } ?: return
        if (at == listState.firstVisibleItemIndex && listState.firstVisibleItemScrollOffset > 0) scope.launch { listState.scrollToItem(at) }
    }
    val questionActions = QuestionActions(
        answer = { id, answers ->
            controller.answerQuestion(id, answers)
            keepEndInView(id)
        },
        cancel = { id ->
            controller.cancelQuestion(id)
            keepEndInView(id)
        },
    )

    val actions = MessageActions(
        copy = { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Hermes", text))) } },
        share = { text -> context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)) },
        speak = { text ->
            val engine = tts.value
            if (engine != null) {
                if (engine.isSpeaking) engine.stop() else engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes")
            } else {
                tts.value = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts.value?.language = Locale.getDefault()
                        tts.value?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes")
                    }
                }
            }
        },
        regenerate = controller::regenerate,
        edit = { editing = it },
        openFile = openFile,
        loadFile = loadFile,
    )

    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().imePadding()) {
        ChatHeader(
            title = state.title.ifBlank { serverName },
            model = state.model,
            usage = state.usage,
            modelPending = state.modelPending,
            canRename = state.storedId != null,
            onMenu = onMenu,
            onNewChat = onNewChat,
            onModels = { models = true },
            onRename = { renaming = true },
        )
        if (!connected) {
            Text(
                stringResource(R.string.chat_reconnecting),
                color = colors.onWarning, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(colors.warning).padding(4.dp),
            )
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.items.isEmpty() && state.questions.isEmpty() && !state.loading) {
                // A suggestion would replace what was shared, so the quick actions stand in for them.
                EmptyChat(serverName, suggestions = !quickActions) { draft.text = it }
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize().nestedScroll(pull).offset { IntOffset(0, -pull.distance.roundToInt()) }.testTag("transcript"),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    // The turn runs without an open answer, e.g. while the socket connects or the agent starts.
                    if (state.awaitingAnswer) {
                        item(key = "pending") { PendingReply(if (state.connecting) stringResource(R.string.chat_connecting) else state.status) }
                    }
                    cardsOf[null]?.asReversed()?.forEach { connection ->
                        item(key = "connection:${connection.opId}") {
                            Column(Modifier.padding(horizontal = 16.dp)) { ConnectionCard(connection, connectionActions) }
                        }
                    }
                    items(reversed, key = { row -> rowKey(row) }) { row ->
                        when (row) {
                            is TranscriptRow.Day -> DaySeparator(row.date)
                            is TranscriptRow.Ask -> Column(Modifier.padding(horizontal = 16.dp)) {
                                AgentQuestion(row.card, controller.answersOf(row.card), connected, questionActions)
                            }
                            is TranscriptRow.Message -> when (val item = row.item) {
                                is ChatItem.User -> UserBubble(item, last = item.key == lastUser, actions = actions)
                                is ChatItem.CronReport -> CronReportMessage(item)
                                is ChatItem.Assistant -> AssistantMessage(
                                    item, last = item.key == lastAnswer, showReasoning = settings.showReasoning, status = state.status, actions = actions,
                                    connections = cardsOf[item.key].orEmpty(), connectionActions = connectionActions,
                                )
                            }
                        }
                    }
                }
            }
            // The pull hint grows from under the last message.
            if (pull.distance > 0f) {
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).alpha(pull.progress.coerceAtMost(1f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(if (pull.progress >= 1f) colors.contrast else colors.userBubble),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (pull.progress >= 1f) Icons.Outlined.EditNote else Icons.Outlined.KeyboardArrowUp, null,
                            tint = if (pull.progress >= 1f) colors.onContrast else colors.text,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(if (pull.progress >= 1f) R.string.chat_release_for_new else R.string.chat_pull_for_new), color = colors.secondaryText, fontSize = 13.sp)
                }
            }
            if (questionAway && waitingAt >= 0 && pull.distance == 0f) {
                // Lower rows are nearer the end of the chat: the arrow points to where the question is.
                val below = waitingAt < listState.firstVisibleItemIndex
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp, start = 16.dp, end = 16.dp).clip(RoundedCornerShape(20.dp)).background(colors.contrast)
                        .clickable(onClickLabel = stringResource(R.string.chat_question_show)) { scope.launch { listState.animateScrollToItem(waitingAt) } }
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(horizontal = 14.dp, vertical = 8.dp).testTag("question-waiting"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (below) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward, null, tint = colors.onContrast, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.chat_question_waiting), color = colors.onContrast, fontSize = 14.sp)
                }
            }
            // The arrow to the end would cover the buttons of a question on the screen; one off the screen has its own button.
            androidx.compose.animation.AnimatedVisibility(
                visible = !atBottom && waitingKeys.isEmpty() && state.items.isNotEmpty() && pull.distance == 0f,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(colors.background).border(1.dp, colors.composerBorder, CircleShape)
                        .clickable { scope.launch { listState.animateScrollToItem(0) } },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.chat_scroll_down), tint = colors.text, modifier = Modifier.size(20.dp)) }
            }
        }

        (state.error ?: localError)?.let { error ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(colors.danger.copy(alpha = 0.12f))
                    .clickable { controller.dismissError(); localError = null }.padding(10.dp),
            ) { Text(error, color = colors.text, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        Column(Modifier.navigationBarsPadding()) {
            if (quickActions) {
                val resources = LocalResources.current
                QuickActions(onAction = { send(quickMessage(resources.getString(it.prompt), draft.text)) }, modifier = Modifier.padding(top = 8.dp))
            }
            Composer(
                text = draft.text,
                onText = { draft.text = it },
                attachments = draft.attachments,
                onAttachments = { draft.attachments = it },
                running = state.running,
                enterSends = settings.enterSends,
                onSend = ::send,
                onStop = controller::stop,
                onError = { localError = it },
                onOpen = openLocal,
            )
        }
    }

    state.prompt?.let { prompt ->
        PromptSheet(
            prompt,
            onApproval = controller::answerApproval,
            onSecret = controller::answerSecret,
            onCancel = controller::cancelPrompt,
        )
    }
    if (models) {
        ModelSheet(state.model, loadModels, onPick = { provider, model -> models = false; controller.chooseModel(provider, model) }, onDismiss = { models = false })
    }
    state.modelConfirm?.let { (provider, model, message) ->
        AlertDialog(
            onDismissRequest = controller::dismissModelConfirm,
            title = { Text(stringResource(R.string.chat_switch_title, model)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { controller.chooseModel(provider, model, confirmed = true) }) { Text(stringResource(R.string.chat_switch)) } },
            dismissButton = { TextButton(onClick = controller::dismissModelConfirm) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (renaming) RenameDialog(state.title, onDismiss = { renaming = false }) { controller.rename(it); renaming = false }
    editing?.let { item ->
        var text by remember(item) { mutableStateOf(item.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.chat_edit_title)) },
            text = { androidx.compose.material3.OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { editing = null; controller.editLast(text.trim()) }) { Text(stringResource(R.string.common_send)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/**
 * One bar: the menu and the new-chat button flank a central column where the plain title sits over
 * its own model target. The title names the chat and does nothing else; the model target opens the
 * picker alone, and rename is a labelled item of the chat menu, so no target hides another action.
 *
 * The totals stay in the centre while they fit it beside or under the model. When the measured
 * width says they do not, they take a plain full-width line under the bar: information, not a
 * second row of controls. The model target keeps at least 48 dp, or the platform grows it and it
 * takes taps from the plain title above.
 */
@Composable
internal fun ChatHeader(
    title: String,
    model: String?,
    usage: SessionUsage?,
    modelPending: Boolean,
    canRename: Boolean,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    onModels: () -> Unit,
    onRename: () -> Unit,
) {
    val colors = ChatTheme.colors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = colors.faintText, fontSize = 12.sp)
    val usageLine = usage?.let { usageText(it) }
    val modelLabel = stringResource(R.string.chat_choose_model)
    var chatMenu by remember { mutableStateOf(false) }
    // The target is full width inside the centre, so the width it was given is the room the
    // subtitle has, less its own horizontal padding and the chevron. The first frame has no width
    // yet; it keeps the totals inside, and the measured frame moves them out only if they overflow.
    var centerPx by remember { mutableIntStateOf(0) }
    val roomPx = (centerPx - with(density) { 34.dp.toPx() }).coerceAtLeast(0f)
    val usageBelow = centerPx > 0 && usageLine != null &&
        measurer.measure(usageLine, style, maxLines = 1).size.width > roomPx
    val room = with(density) { roomPx.toDp() }
    Column(Modifier.fillMaxWidth().testTag("header")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMenu, modifier = Modifier.testTag("chat-menu")) {
                Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.chat_chats), tint = colors.text)
            }
            Box(Modifier.weight(1f).onSizeChanged { centerPx = it.width }) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        title,
                        color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().testTag("chat-title"),
                    )
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClickLabel = modelLabel, onClick = onModels)
                            .semantics(mergeDescendants = true) { contentDescription = modelLabel }
                            .padding(horizontal = 8.dp)
                            .heightIn(min = 48.dp)
                            .testTag("model-picker"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatSubtitle(
                            model,
                            if (usageBelow) null else usage,
                            pending = modelPending,
                            modifier = Modifier.widthIn(max = room),
                        )
                        Icon(Icons.Outlined.ExpandMore, null, tint = colors.faintText, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (canRename) {
                Box {
                    IconButton(onClick = { chatMenu = true }, modifier = Modifier.testTag("chat-actions")) {
                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.chat_actions), tint = colors.text)
                    }
                    DropdownMenu(expanded = chatMenu, onDismissRequest = { chatMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_rename)) },
                            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                            onClick = { chatMenu = false; onRename() },
                            modifier = Modifier.testTag("chat-rename").semantics { role = Role.Button },
                        )
                    }
                }
            }
            IconButton(onClick = onNewChat, modifier = Modifier.testTag("chat-new")) {
                Icon(Icons.Outlined.EditNote, stringResource(R.string.common_new_chat), tint = colors.text)
            }
        }
        if (usageBelow) {
            Text(
                usageLine.orEmpty(),
                style = style,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("header-usage"),
            )
        }
    }
}

@Composable
private fun EmptyChat(serverName: String, suggestions: Boolean, onSuggestion: (String) -> Unit) {
    val colors = ChatTheme.colors
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.chat_empty_title), color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(serverName, color = colors.faintText, fontSize = 14.sp)
        if (!suggestions) return@Column
        Spacer(Modifier.height(28.dp))
        SUGGESTIONS.forEach { id ->
            val suggestion = stringResource(id)
            Text(
                suggestion,
                color = colors.secondaryText, fontSize = 15.sp,
                modifier = Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, colors.composerBorder, RoundedCornerShape(16.dp))
                    .clickable { onSuggestion(suggestion) }.padding(horizontal = 14.dp, vertical = 10.dp).width(280.dp),
            )
        }
    }
}

private fun rowKey(row: TranscriptRow): String = when (row) {
    is TranscriptRow.Day -> "day:${row.date}"
    is TranscriptRow.Message -> row.item.key
    is TranscriptRow.Ask -> "question:${row.card.id}"
}
