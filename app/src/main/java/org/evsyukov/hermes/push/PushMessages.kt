package org.evsyukov.hermes.push

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.evsyukov.hermes.AppContainer

/** What hermes-push sends, decrypted: a finished turn of a chat, or a test. */
@Serializable
data class PushMessage(
    val type: String = "",
    @SerialName("session_id") val sessionId: String? = null,
    val platform: String = "",
    val job: String? = null,
    val preview: String = "",
    val channel: String = "",
    @SerialName("channel_owner") val channelOwner: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** Null for anything that is not a message of hermes-push. */
        fun parse(content: ByteArray): PushMessage? = try {
            json.decodeFromString(serializer(), content.decodeToString()).takeIf {
                it.type == "refresh" || it.type == "test" || (it.type == "turn" && !it.sessionId.isNullOrBlank())
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** Both new signals and legacy pushes wake the same local check. Push text is never displayed. */
class PushMessages(private val container: AppContainer) {
    suspend fun handle(content: ByteArray, instance: String? = null) {
        val message = PushMessage.parse(content) ?: return
        val settings = withTimeoutOrNull(10_000) { container.settings.activitySettings.filterNotNull().first() } ?: return
        if (!settings.notifyActivity || !settings.push) return
        withTimeoutOrNull(10_000) { container.connection.filterNotNull().first() } ?: return
        if (!container.push.accepts(message, instance)) return
        container.requestActivityCheck(instance)
    }
}
