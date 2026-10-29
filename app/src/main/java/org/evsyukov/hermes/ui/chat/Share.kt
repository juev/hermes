package org.evsyukov.hermes.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.LocalAttachment
import org.evsyukov.hermes.ui.theme.ChatTheme

/** What another app shared with Hermes: a link or text for the field, files for the attachments. */
class Shared(val text: String, val attachments: List<LocalAttachment>, val skipped: Int = 0)

/** Reads a share from another app; null for an intent that shares nothing. Files are read whole, so not on the main thread. */
fun readShared(context: Context, intent: Intent): Shared? {
    val uris = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> return null
    }
    // A browser puts the link in the text and the page title in the subject; the subject stands in only when there is no text.
    val text = (intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT))?.toString()?.trim().orEmpty()
    // A file the sharing app no longer lets us read is left out like one too big.
    val files = uris.mapNotNull { runCatching { readAttachment(context, it) }.getOrNull() }
    if (text.isEmpty() && uris.isEmpty()) return null
    return Shared(text, files, skipped = uris.size - files.size)
}

/** What can be done with a share in one tap; the agent picks the integration the result goes to. */
enum class QuickAction(@StringRes val label: Int, @StringRes val prompt: Int) {
    SUMMARIZE(R.string.share_summarize, R.string.share_summarize_prompt),
    RELATED(R.string.share_related, R.string.share_related_prompt),
    TASK(R.string.share_task, R.string.share_task_prompt),
}

/** The message a quick action sends: what to do first, as the server titles the chat by it, then what was shared. */
fun quickMessage(prompt: String, text: String): String = if (text.isBlank()) prompt else "$prompt\n\n${text.trim()}"

@Composable
fun QuickActions(onAction: (QuickAction) -> Unit, modifier: Modifier = Modifier) {
    val colors = ChatTheme.colors
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickAction.entries.forEach { action ->
            Text(
                stringResource(action.label),
                color = colors.text, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, colors.composerBorder, RoundedCornerShape(16.dp))
                    .clickable { onAction(action) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
