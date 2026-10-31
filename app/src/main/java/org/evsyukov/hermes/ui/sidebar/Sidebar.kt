package org.evsyukov.hermes.ui.sidebar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.evsyukov.hermes.R
import java.time.LocalDate
import java.time.ZoneId
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.ui.ListMode
import org.evsyukov.hermes.ui.SidebarState
import org.evsyukov.hermes.ui.theme.ChatTheme

/** What the sidebar can ask for; the main screen closes the drawer after most of them. */
class SidebarActions(
    val onSearch: () -> Unit,
    val onNewChat: () -> Unit,
    val onOpen: (String) -> Unit,
    val onRename: (String, String) -> Unit,
    val onPin: (String, Boolean) -> Unit,
    val onArchive: (String, Boolean) -> Unit,
    val onDelete: (String) -> Unit,
    val onLoadMore: () -> Unit,
    val onRefresh: () -> Unit,
    val onShowList: (ListMode) -> Unit,
    val onAdmin: () -> Unit,
    val onSettings: () -> Unit,
    val onSwitchServer: (String) -> Unit,
    val onEditServer: (String?) -> Unit,
)

@Composable
fun Sidebar(
    state: SidebarState,
    currentChatId: String?,
    servers: List<ServerProfile>,
    activeServer: ServerProfile?,
    actions: SidebarActions,
) {
    val colors = ChatTheme.colors
    val zone = remember { ZoneId.systemDefault() }
    val groups = remember(state.chats) { groupChats(state.chats, LocalDate.now(zone), zone) }
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= listState.layoutInfo.totalItemsCount - 5 } }
    LaunchedEffect(nearEnd, state.chats.size) { if (nearEnd) actions.onLoadMore() }

    Column(Modifier.fillMaxHeight().width(320.dp).background(colors.sidebar).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(colors.selected).clickable(onClick = actions.onSearch).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = colors.secondaryText, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sidebar_search), color = colors.secondaryText, fontSize = 15.sp)
            }
            IconButton(onClick = actions.onNewChat) { Icon(Icons.Outlined.EditNote, stringResource(R.string.common_new_chat), tint = colors.text) }
        }
        if (state.loading && state.chats.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

        LazyColumn(Modifier.weight(1f), state = listState) {
            item { NavRow(Icons.Outlined.EditNote, stringResource(R.string.common_new_chat), onClick = actions.onNewChat) }
            item { NavRow(Icons.Outlined.Dns, stringResource(R.string.sidebar_server), onClick = actions.onAdmin) }
            if (state.mode == ListMode.CHATS) {
                item { NavRow(Icons.Outlined.Archive, stringResource(R.string.sidebar_archive), onClick = { actions.onShowList(ListMode.ARCHIVE) }) }
            } else {
                item { NavRow(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.sidebar_back), onClick = { actions.onShowList(ListMode.CHATS) }) }
                item {
                    Text(
                        stringResource(R.string.sidebar_archive),
                        color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                    )
                }
            }
            state.error?.let { error ->
                item {
                    Text(
                        stringResource(R.string.sidebar_load_failed, error),
                        color = colors.danger, fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp).clickable(onClick = actions.onRefresh),
                    )
                }
            }
            if (!state.loading && state.chats.isEmpty() && state.error == null) {
                item {
                    Text(
                        when (state.mode) {
                            ListMode.CHATS -> stringResource(R.string.sidebar_empty_chats)
                            ListMode.ARCHIVE -> stringResource(R.string.sidebar_empty_archive)
                        },
                        color = colors.faintText, fontSize = 14.sp, modifier = Modifier.padding(16.dp),
                    )
                }
            }
            groups.forEach { group ->
                item(key = "h:${group.key}") {
                    Text(
                        group.key.title(),
                        color = colors.faintText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
                    )
                }
                items(group.chats, key = { it.id }) { chat ->
                    ChatRow(chat, chat.id == currentChatId, actions)
                }
            }
            if (state.loading && state.chats.isNotEmpty()) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp).height(2.dp)) }
            }
        }

        HorizontalDivider(color = colors.divider)
        ServerFooter(servers, activeServer, actions)
    }
}

@Composable
private fun GroupKey.title(): String = when (this) {
    GroupKey.Pinned -> stringResource(R.string.sidebar_group_pinned)
    GroupKey.Today -> stringResource(R.string.sidebar_group_today)
    GroupKey.Yesterday -> stringResource(R.string.sidebar_group_yesterday)
    GroupKey.Previous7 -> stringResource(R.string.sidebar_group_previous_7)
    GroupKey.Previous30 -> stringResource(R.string.sidebar_group_previous_30)
    is GroupKey.Month -> {
        val locale = LocalConfiguration.current.locales[0]
        java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
    }
    is GroupKey.Year -> year.toString()
}

@Composable
private fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ChatTheme.colors.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = ChatTheme.colors.text, fontSize = 16.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatRow(chat: ChatSummary, selected: Boolean, actions: SidebarActions) {
    val colors = ChatTheme.colors
    val haptics = LocalHapticFeedback.current
    val unreadMark = stringResource(R.string.sidebar_unread)
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val label = chat.label.ifBlank { stringResource(R.string.common_new_chat) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) colors.selected else colors.sidebar)
                .combinedClickable(
                    onClick = { actions.onOpen(chat.id) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (chat.active) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(colors.success))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                label,
                color = colors.text, fontSize = 15.sp,
                fontWeight = if (chat.unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (chat.unread) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(colors.contrast).semantics { contentDescription = unreadMark })
            }
            if (chat.source.isNotEmpty() && chat.source !in LOCAL_SOURCES) {
                Spacer(Modifier.width(6.dp))
                Text(chat.source, color = colors.faintText, fontSize = 11.sp)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.sidebar_rename)) }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) }, onClick = { menu = false; renaming = true })
            DropdownMenuItem(
                text = { Text(stringResource(if (chat.pinned) R.string.sidebar_unpin else R.string.sidebar_pin)) },
                leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                onClick = { menu = false; actions.onPin(chat.id, !chat.pinned) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (chat.archived) R.string.sidebar_unarchive else R.string.sidebar_move_to_archive)) },
                leadingIcon = { Icon(if (chat.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) },
                onClick = { menu = false; actions.onArchive(chat.id, !chat.archived) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_delete), color = colors.danger) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = colors.danger) },
                onClick = { menu = false; deleting = true },
            )
        }
    }
    if (renaming) RenameDialog(label, onDismiss = { renaming = false }) { actions.onRename(chat.id, it); renaming = false }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.sidebar_delete_title)) },
            text = { Text(stringResource(R.string.sidebar_delete_text, label)) },
            confirmButton = { TextButton(onClick = { deleting = false; actions.onDelete(chat.id) }) { Text(stringResource(R.string.common_delete), color = colors.danger) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sidebar_rename_title)) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ServerFooter(servers: List<ServerProfile>, active: ServerProfile?, actions: SidebarActions) {
    val colors = ChatTheme.colors
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { menu = true }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(colors.contrast), contentAlignment = Alignment.Center) {
                    Text(active?.name?.take(1)?.uppercase() ?: "H", color = colors.onContrast, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(active?.name ?: stringResource(R.string.sidebar_no_server), color = colors.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(active?.url?.host.orEmpty(), color = colors.faintText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.UnfoldMore, null, tint = colors.faintText, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                servers.forEach { server ->
                    DropdownMenuItem(
                        text = { Text(server.name + if (server.id == active?.id) "  ✓" else "") },
                        onClick = { menu = false; actions.onSwitchServer(server.id) },
                    )
                }
                HorizontalDivider()
                active?.let { DropdownMenuItem(text = { Text(stringResource(R.string.sidebar_configure, it.name)) }, onClick = { menu = false; actions.onEditServer(it.id) }) }
                DropdownMenuItem(text = { Text(stringResource(R.string.sidebar_add_server)) }, onClick = { menu = false; actions.onEditServer(null) })
            }
        }
        IconButton(onClick = actions.onSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.common_settings), tint = colors.secondaryText) }
    }
}

/** Chats started in the app and the dashboard need no source badge; Telegram, cron and the rest get one. */
private val LOCAL_SOURCES = setOf("android", "dashboard", "desktop", "tui", "cli", "web")
