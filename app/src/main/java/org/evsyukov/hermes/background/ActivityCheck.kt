package org.evsyukov.hermes.background

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.evsyukov.hermes.HermesApplication
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.stripMedia
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.data.server.ServerSession
import java.util.concurrent.TimeUnit

private val Context.seenStore by preferencesDataStore(name = "seen")

/**
 * Chats with activity the user has not seen: newer than the mark of the last check and not started
 * from this phone, whose answers [TurnWatcher] reports itself. The first check only sets the mark.
 */
fun unseenChats(chats: List<ChatSummary>, mark: Double?, ownSource: String = "android"): List<ChatSummary> {
    if (mark == null) return emptyList()
    return chats.filter { it.lastActive > mark && it.messageCount > 0 && it.source != ownSource && !it.archived }
}

/**
 * Asks the server what changed since the last look and notifies about it. Runs from WorkManager
 * every few minutes, and at once on `sessions.changed` while a permanent connection is open.
 */
class ActivityCheck(private val context: Context) {
    private val lock = Mutex()

    /** Moves the mark to now: what the user saw in the app needs no notification later. */
    suspend fun markSeen(session: ServerSession, now: Double = System.currentTimeMillis() / 1000.0) {
        context.seenStore.edit { it[key(session)] = now }
    }

    suspend fun run(session: ServerSession, notifications: Notifications, visibleChat: String?) = lock.withLock {
        val prefs = context.seenStore.data.first()
        val mark = prefs[key(session)]
        val chats = session.chat.list(limit = 30).chats
        val fresh = unseenChats(chats, mark).filter { it.id != visibleChat }
        for (chat in fresh.take(MAX_NOTIFICATIONS)) {
            val reply = try {
                session.chat.lastReply(chat.id)
            } catch (e: Exception) {
                null
            }
            notifications.activity(
                chat.id,
                chat.label.ifBlank { context.getString(R.string.common_new_chat) },
                stripMedia(reply ?: chat.preview).take(400).ifBlank { context.getString(R.string.notify_new_message) },
            )
        }
        val newest = chats.maxOfOrNull { it.lastActive } ?: mark ?: System.currentTimeMillis() / 1000.0
        context.seenStore.edit { it[key(session)] = maxOf(newest, mark ?: 0.0) }
    }

    private fun key(session: ServerSession) = doublePreferencesKey("mark:${session.profile.id}")

    companion object {
        private const val WORK = "activity-check"
        private const val MAX_NOTIFICATIONS = 5

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
        if (container.turns.inForeground) return Result.success()
        val session = container.connection.value?.session ?: return Result.success()
        return try {
            container.activity.run(session, container.notifications, null)
            Result.success()
        } catch (e: Exception) {
            Log.w("HermesActivity", "Check failed", e)
            Result.retry()
        }
    }
}
