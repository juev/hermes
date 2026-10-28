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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.data.server.ServerProfile
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
    val onToggleArchived: () -> Unit,
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
                Text("Поиск", color = colors.secondaryText, fontSize = 15.sp)
            }
            IconButton(onClick = actions.onNewChat) { Icon(Icons.Outlined.EditNote, "Новый чат", tint = colors.text) }
        }
        if (state.loading && state.chats.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

        LazyColumn(Modifier.weight(1f), state = listState) {
            item { NavRow(Icons.Outlined.EditNote, "Новый чат", onClick = actions.onNewChat) }
            item { NavRow(Icons.Outlined.Dns, "Управление сервером", onClick = actions.onAdmin) }
            item {
                NavRow(
                    if (state.showArchived) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Archive,
                    if (state.showArchived) "Назад к чатам" else "Архив",
                    onClick = actions.onToggleArchived,
                )
            }
            state.error?.let { error ->
                item {
                    Text(
                        "Список не загрузился: $error",
                        color = colors.danger, fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp).clickable(onClick = actions.onRefresh),
                    )
                }
            }
            if (!state.loading && state.chats.isEmpty() && state.error == null) {
                item {
                    Text(
                        if (state.showArchived) "В архиве пусто" else "Чатов пока нет",
                        color = colors.faintText, fontSize = 14.sp, modifier = Modifier.padding(16.dp),
                    )
                }
            }
            groups.forEach { group ->
                item(key = "h:${group.label}") {
                    Text(
                        group.label,
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
private fun ChatRow(chat: ChatSummary, selected: Boolean, actions: SidebarActions) {
    val colors = ChatTheme.colors
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
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
            Text(chat.label, color = colors.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (chat.source.isNotEmpty() && chat.source !in LOCAL_SOURCES) {
                Spacer(Modifier.width(6.dp))
                Text(chat.source, color = colors.faintText, fontSize = 11.sp)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Переименовать") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) }, onClick = { menu = false; renaming = true })
            DropdownMenuItem(
                text = { Text(if (chat.pinned) "Открепить" else "Закрепить") },
                leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                onClick = { menu = false; actions.onPin(chat.id, !chat.pinned) },
            )
            DropdownMenuItem(
                text = { Text(if (chat.archived) "Вернуть из архива" else "В архив") },
                leadingIcon = { Icon(if (chat.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) },
                onClick = { menu = false; actions.onArchive(chat.id, !chat.archived) },
            )
            DropdownMenuItem(
                text = { Text("Удалить", color = colors.danger) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = colors.danger) },
                onClick = { menu = false; deleting = true },
            )
        }
    }
    if (renaming) RenameDialog(chat.label, onDismiss = { renaming = false }) { actions.onRename(chat.id, it); renaming = false }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Удалить чат?") },
            text = { Text("«${chat.label}» удалится с сервера вместе с историей.") },
            confirmButton = { TextButton(onClick = { deleting = false; actions.onDelete(chat.id) }) { Text("Удалить", color = colors.danger) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Название чата") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
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
                    Text(active?.name ?: "Нет сервера", color = colors.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                active?.let { DropdownMenuItem(text = { Text("Настроить «${it.name}»") }, onClick = { menu = false; actions.onEditServer(it.id) }) }
                DropdownMenuItem(text = { Text("Добавить сервер") }, onClick = { menu = false; actions.onEditServer(null) })
            }
        }
        IconButton(onClick = actions.onSettings) { Icon(Icons.Outlined.Settings, "Настройки", tint = colors.secondaryText) }
    }
}

/** Chats started in the app and the dashboard need no source badge; Telegram, cron and the rest get one. */
private val LOCAL_SOURCES = setOf("android", "dashboard", "desktop", "tui", "cli", "web")
