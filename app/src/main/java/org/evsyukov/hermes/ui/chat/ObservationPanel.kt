package org.evsyukov.hermes.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.ChatItem
import org.evsyukov.hermes.chat.LocalObservation
import org.evsyukov.hermes.chat.LocalAttachment
import java.util.Base64
import org.evsyukov.hermes.ui.theme.ChatTheme

@Composable
internal fun ObservationDisclosure(unsaved: Boolean, refresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("local-observation-disclosure")) {
        Text(stringResource(R.string.chat_local_overlap), color = ChatTheme.colors.text, fontSize = 14.sp)
        if (unsaved) Text(stringResource(R.string.chat_local_unsaved), color = ChatTheme.colors.text, fontSize = 14.sp)
        TextButton(onClick = refresh) { Text(stringResource(R.string.chat_local_refresh)) }
    }
}

/** Archived observations have no live request cards or implicit resubmit action. */
@Composable
internal fun ObservationPanel(row: LocalObservation, actions: MessageActions, refresh: () -> Unit, collapse: (Boolean) -> Unit, openLocal: (LocalAttachment) -> Unit = {}, active: Boolean = false) {
    val collapsed = row.collapsed ?: row.reconciled
    val lastUser = row.items.filterIsInstance<ChatItem.User>().lastOrNull()?.key
    val lastAnswer = row.items.filterIsInstance<ChatItem.Assistant>().lastOrNull()?.key
    Column(Modifier.fillMaxWidth().testTag("observation:${row.id}")) {
        Text(stringResource(if (row.reconciled) R.string.chat_local_matched else R.string.chat_local_copy), color = ChatTheme.colors.secondaryText, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
        if (!row.reconciled) ObservationDisclosure(false, refresh)
        TextButton(onClick = { collapse(!collapsed) }) { Text(stringResource(if (collapsed) R.string.chat_local_reopen else R.string.chat_local_collapse)) }
        if (!collapsed) {
            row.items.forEach { item -> when (item) {
                is ChatItem.User -> UserBubble(if (item.delivery == org.evsyukov.hermes.chat.Delivery.SENDING) item.copy(delivery = org.evsyukov.hermes.chat.Delivery.UNKNOWN) else item, last = active && item.key == lastUser, actions = actions)
                is ChatItem.Assistant -> AssistantMessage(item.copy(streaming = false), last = active && item.key == lastAnswer, showReasoning = true, status = null, actions = actions)
                is ChatItem.CronReport -> CronReportMessage(item)
            } }
            ObservationFiles(row, openLocal)
            row.error?.let { Text(it, color = ChatTheme.colors.text, modifier = Modifier.padding(16.dp)) }
        }
    }
}

/** The encoded bytes are retained once per selected file, not reconstructed from a server path. */
@Composable
internal fun ObservationFiles(row: LocalObservation, openLocal: (LocalAttachment) -> Unit) {
    var failed by remember(row.id) { mutableStateOf(false) }
    row.files.forEach { file ->
        TextButton(onClick = {
            val bytes = try { Base64.getDecoder().decode(file.base64) } catch (_: IllegalArgumentException) { failed = true; null }
            if (bytes != null) openLocal(LocalAttachment(file.name, file.mime, bytes))
        }) { Text(stringResource(R.string.chat_local_file, file.name)) }
    }
    if (failed) Text(stringResource(R.string.chat_local_file_failed), color = ChatTheme.colors.text)
}
