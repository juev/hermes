package org.evsyukov.hermes.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.evsyukov.hermes.chat.LocalAttachment

/**
 * What the user has prepared in one chat and where they were in it. The app keeps it while the chat is
 * open, not the screen: a screen over the chat, the app in the background or a recreated activity
 * takes the chat screen away, and coming back finds the words, the files and the place as they were.
 */
class ChatDraft {
    var text by mutableStateOf("")
    var attachments by mutableStateOf<List<LocalAttachment>>(emptyList())

    /** The field was filled by a share, so the quick actions show until a message goes. */
    var fromShare by mutableStateOf(false)

    /** Where the list stood when the screen went: the item at the bottom edge and its offset. */
    var scrollIndex = 0
    var scrollOffset = 0

    /** How many messages and questions the screen last showed; a screen that comes back to the same count stays put. */
    var shownItems = -1
}
