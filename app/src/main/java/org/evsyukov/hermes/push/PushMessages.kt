package org.evsyukov.hermes.push

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.evsyukov.hermes.AppContainer
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.stripMedia

/** What hermes-push sends, decrypted: a finished turn of a chat, or a test. */
@Serializable
data class PushMessage(
    val type: String = "",
    @SerialName("session_id") val sessionId: String? = null,
    val platform: String = "",
    val job: String? = null,
    val preview: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** Null for anything that is not a message of hermes-push. */
        fun parse(content: ByteArray): PushMessage? = try {
            json.decodeFromString(serializer(), content.decodeToString()).takeIf { it.type.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Turns a push into a notification, unless the app shows the chat itself: in front the list and
 * the chat update by their own, and an answer the app followed was announced by the watcher.
 */
class PushMessages(private val container: AppContainer) {
    suspend fun handle(content: ByteArray) {
        val message = PushMessage.parse(content) ?: return
        val notifications = container.notifications
        if (message.type == "test") {
            notifications.activity("push-test", container.strings.get(R.string.push_test_title), container.strings.get(R.string.push_test_text))
            return
        }
        val chatId = message.sessionId ?: return
        if (container.turns.inForeground || container.turns.notifiedRecently(chatId)) return
        val session = container.connection.value?.session
        val title = withTimeoutOrNull(5_000) { runCatching { session?.chat?.get(chatId)?.title }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: message.job
            ?: container.strings.get(R.string.common_new_chat)
        notifications.activity(chatId, title, stripMedia(message.preview).ifBlank { container.strings.get(R.string.notify_new_message) })
    }
}
