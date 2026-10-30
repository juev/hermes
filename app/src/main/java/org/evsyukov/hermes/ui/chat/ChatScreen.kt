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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.evsyukov.hermes.R
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.evsyukov.hermes.chat.ChatController
import org.evsyukov.hermes.chat.ChatItem
import org.evsyukov.hermes.chat.LocalAttachment
import org.evsyukov.hermes.chat.Part
import org.evsyukov.hermes.data.remote.ModelChoices
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
    transcribe: suspend (ByteArray, String) -> String,
    openLocal: (LocalAttachment) -> Unit,
    shared: Shared? = null,
    onSharedTaken: () -> Unit = {},
) {
    val state by controller.state.collectAsState()
    val colors = ChatTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(controller) { mutableStateOf("") }
    var attachments by remember(controller) { mutableStateOf<List<LocalAttachment>>(emptyList()) }
    var models by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ChatItem.User?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var fromShare by remember(controller) { mutableStateOf(false) }

    // A chat opens to read it, as in ChatGPT: the keyboard waits for a tap on the field.
    val focus = LocalFocusManager.current
    LaunchedEffect(controller) { focus.clearFocus() }

    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) { onDispose { tts.value?.shutdown() } }

    // The list is laid out from the bottom: a chat opens at its end, and a growing answer or the
    // keyboard keeps the last message in view without scrolling by hand.
    val listState = remember(controller) { LazyListState() }
    val atBottom by remember { derivedStateOf { !listState.canScrollBackward } }
    val reversed = remember(state.items) { state.items.asReversed() }
    // A new message joins the reader who is at the end; one who scrolled up stays put.
    LaunchedEffect(state.items.size) {
        if (atBottom || state.items.lastOrNull() is ChatItem.User) listState.scrollToItem(0)
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

    fun send(text: String = draft.trim()) {
        if (text.isEmpty() && attachments.isEmpty()) return
        if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        controller.send(text, attachments)
        draft = ""
        attachments = emptyList()
        fromShare = false
    }

    // A share fills the field of the empty new chat and offers quick actions until a message goes.
    LaunchedEffect(controller, shared) {
        val current = controller.state.value
        if (shared == null || current.storedId != null || current.items.isNotEmpty()) return@LaunchedEffect
        draft = listOf(draft.trim(), shared.text).filter { it.isNotEmpty() }.joinToString("\n")
        attachments = attachments + shared.attachments
        fromShare = true
        onSharedTaken()
    }
    val quickActions = fromShare && state.items.isEmpty() && (draft.isNotBlank() || attachments.isNotEmpty())

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
        // The bar of the ChatGPT app: menu, the model in the middle, a new chat on the right.
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) { Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.chat_chats), tint = colors.text) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { models = true }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.title.ifBlank { serverName },
                            color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = state.storedId != null) { renaming = true },
                        )
                        // The model and what the chat has spent sit on one line, or the spent line moves under it.
                        ChatSubtitle(state.model, state.usage)
                    }
                    Icon(Icons.Outlined.ExpandMore, null, tint = colors.faintText, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onNewChat) { Icon(Icons.Outlined.EditNote, stringResource(R.string.common_new_chat), tint = colors.text) }
        }
        if (!connected) {
            Text(
                stringResource(R.string.chat_reconnecting),
                color = colors.onContrast, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(colors.warning).padding(4.dp),
            )
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.items.isEmpty() && !state.loading) {
                // A suggestion would replace what was shared, so the quick actions stand in for them.
                EmptyChat(serverName, suggestions = !quickActions) { draft = it }
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize().nestedScroll(pull).offset { IntOffset(0, -pull.distance.roundToInt()) },
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
                    itemsIndexed(reversed, key = { _, item -> item.key }) { index, item ->
                        when (item) {
                            is ChatItem.User -> UserBubble(item, last = reversed.indexOfFirst { it is ChatItem.User } == index, actions = actions)
                            is ChatItem.CronReport -> CronReportMessage(item)
                            is ChatItem.Assistant -> AssistantMessage(
                                item, last = index == 0, showReasoning = settings.showReasoning, status = state.status, actions = actions,
                                connections = cardsOf[item.key].orEmpty(), connectionActions = connectionActions,
                            )
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
            androidx.compose.animation.AnimatedVisibility(
                visible = !atBottom && state.items.isNotEmpty() && pull.distance == 0f,
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
                QuickActions(onAction = { send(quickMessage(resources.getString(it.prompt), draft)) }, modifier = Modifier.padding(top = 8.dp))
            }
            Composer(
                text = draft,
                onText = { draft = it },
                attachments = attachments,
                onAttachments = { attachments = it },
                running = state.running,
                enterSends = settings.enterSends,
                onSend = ::send,
                onStop = controller::stop,
                transcribe = transcribe,
                onError = { localError = it },
                onOpen = openLocal,
            )
        }
    }

    state.prompt?.let { prompt ->
        PromptSheet(
            prompt,
            onApproval = controller::answerApproval,
            onClarify = controller::answerClarify,
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
