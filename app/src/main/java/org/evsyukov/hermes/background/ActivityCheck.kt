package org.evsyukov.hermes.background

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.evsyukov.hermes.HermesApplication
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.stripMedia
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.data.server.ServerSession
import java.util.concurrent.TimeUnit

private val Context.seenStore by preferencesDataStore(name = "seen")

/**
 * Chats that may hold an answer the user has not seen: their activity is newer than the mark of the
 * last check, or than where the check of the chat stayed [behind] it. The first check only sets the
 * mark. A chat the phone started is reported like any other: the answer of one that is not open in
 * the app is not announced by anyone else when push is off.
 */
fun unseenChats(chats: List<ChatSummary>, mark: Double?, behind: Map<String, Double> = emptyMap()): List<ChatSummary> {
    if (mark == null) return emptyList()
    return chats.filter { it.lastActive > (behind[it.id] ?: mark) && it.messageCount > 0 && !it.archived }
}

/**
 * Asks the server what changed since the last look and notifies about the answers written since.
 * Runs from WorkManager every few minutes, and at once on `sessions.changed` while a permanent
 * connection is open.
 */
class ActivityCheck(private val context: Context) {
    private val lock = Mutex()

    /**
     * Moves the mark to now: what the user saw in the app needs no notification later. It waits for a
     * check under way and never moves the mark back.
     */
    suspend fun markSeen(session: ServerSession, now: Double = System.currentTimeMillis() / 1000.0) {
        lock.withLock {
            context.seenStore.edit {
                it[key(session)] = maxOf(now, it[key(session)] ?: now)
                it.remove(behindKey(session))
            }
        }
    }

    suspend fun run(
        session: ServerSession,
        announce: (chatId: String, title: String, text: String, own: Boolean) -> Unit,
        visibleChat: String?,
    ) = lock.withLock {
        val prefs = context.seenStore.data.first()
        val mark = prefs[key(session)]
        val behind = decode(prefs[behindKey(session)])
        val chats = session.chat.list(limit = 30).chats
        val fresh = unseenChats(chats, mark, behind).filter { it.id != visibleChat }
        // The mark moves past every chat; one whose history did not read, or whose answer is past the
        // limit of a check, stays behind it where its check stood, and the next check takes it again.
        // One that newer chats pushed out of this list keeps its place until a check meets it again.
        val listed = chats.mapTo(HashSet()) { it.id }
        val stays = behind.filterKeys { it !in listed }.toMutableMap()
        var done = 0
        var shown = 0
        try {
            for (chat in fresh) {
                val since = behind[chat.id] ?: mark ?: break
                if (shown == MAX_NOTIFICATIONS) {
                    stays[chat.id] = since
                } else {
                    // Activity is not an answer: reasoning, tool runs and the user's own words move it too.
                    val reply = try {
                        session.chat.lastReply(chat.id, after = since)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "History of ${chat.id} not read", e)
                        stays[chat.id] = since
                        null
                    }
                    if (reply != null) {
                        announce(
                            chat.id,
                            chat.label.ifBlank { context.getString(R.string.common_new_chat) },
                            stripMedia(reply.text).take(400).ifBlank { context.getString(R.string.notify_new_message) },
                            chat.source == ANDROID,
                        )
                        shown++
                    }
                }
                done++
            }
        } finally {
            // A check cut short leaves the chats it did not get to as they stood, and keeps what it announced.
            for (chat in fresh.drop(done)) stays[chat.id] = behind[chat.id] ?: mark ?: continue
            val newest = chats.maxOfOrNull { it.lastActive } ?: mark ?: System.currentTimeMillis() / 1000.0
            withContext(NonCancellable) {
                context.seenStore.edit {
                    it[key(session)] = maxOf(newest, it[key(session)] ?: 0.0)
                    if (stays.isEmpty()) it.remove(behindKey(session)) else it[behindKey(session)] = json.encodeToString(stays.toMap())
                }
            }
        }
    }

    private fun key(session: ServerSession) = doublePreferencesKey("mark:${session.profile.id}")

    /** The chats whose check stays behind the mark, and where it stands for each. */
    private fun behindKey(session: ServerSession) = stringPreferencesKey("behind:${session.profile.id}")

    private fun decode(raw: String?): Map<String, Double> =
        if (raw == null) emptyMap() else runCatching { json.decodeFromString<Map<String, Double>>(raw) }.getOrDefault(emptyMap())

    companion object {
        private const val WORK = "activity-check"
        private const val MAX_NOTIFICATIONS = 5
        private const val TAG = "HermesActivity"
        private val json = Json { ignoreUnknownKeys = true }

        /** The source the gateway records for a chat this app started. */
        private const val ANDROID = "android"

        fun schedule(context: Context, minutes: Int) {
            val request = PeriodicWorkRequestBuilder<ActivityWorker>(minutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }
}

class ActivityWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as HermesApplication).container
        // With push working the server announces new answers itself.
        if (container.turns.inForeground || container.push.active) return Result.success()
        val session = container.connection.value?.session ?: return Result.success()
        return try {
            container.activity.run(session, { id, title, text, own -> container.turns.announce(id, title, text, own = own) }, null)
            Result.success()
        } catch (e: Exception) {
            Log.w("HermesActivity", "Check failed", e)
            Result.retry()
        }
    }
}
