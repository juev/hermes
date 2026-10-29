package org.evsyukov.hermes.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.evsyukov.hermes.MainActivity
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.plainText

/** The notifications of the app, one channel per kind so each can be muted on its own. */
class Notifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannels(
            listOf(
                NotificationChannel(ANSWERS, context.getString(R.string.notify_channel_answers), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.notify_channel_answers_desc)
                },
                NotificationChannel(REQUESTS, context.getString(R.string.notify_channel_requests), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.notify_channel_requests_desc)
                },
                NotificationChannel(ACTIVITY, context.getString(R.string.notify_channel_activity), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.notify_channel_activity_desc)
                },
                NotificationChannel(WORKING, context.getString(R.string.notify_channel_working), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.notify_channel_working_desc)
                    setShowBadge(false)
                },
            ),
        )
    }

    /** Posts unless the user has not allowed notifications; the check sits here for lint to see it. */
    private fun post(tag: String, id: Int, notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.notify(tag, id, notification)
    }

    private fun openChat(chatId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (chatId != null) putExtra(MainActivity.EXTRA_CHAT, chatId) }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun answer(chatId: String, title: String, reply: String, error: Boolean) {
        val text = plainText(reply)
        val notification = NotificationCompat.Builder(context, ANSWERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "Hermes" })
            .setContentText(if (error) context.getString(R.string.notify_error, text) else text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1500)))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openChat(chatId, chatId.hashCode()))
            .build()
        post(TAG_CHAT, chatId.hashCode(), notification)
    }

    /** An approval waits: the buttons answer it from the shade while the socket is alive. */
    fun request(chatId: String?, requestId: String, title: String, text: String, choices: List<String>) {
        val builder = NotificationCompat.Builder(context, REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1500)))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openChat(chatId, requestId.hashCode()))
        choices.take(2).forEach { choice -> builder.addAction(0, choiceLabel(choice), answerIntent(requestId, choice)) }
        if ("deny" !in choices.take(2)) builder.addAction(0, choiceLabel("deny"), answerIntent(requestId, "deny"))
        post(TAG_REQUEST, requestId.hashCode(), builder.build())
    }

    /** The agent waits for a service to be connected; only the card in the chat can do it. */
    fun connection(chatId: String?, opId: String, services: String) {
        val text = context.getString(R.string.notify_connection_text, services)
        val builder = NotificationCompat.Builder(context, REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_connection_title))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openChat(chatId, opId.hashCode()))
        post(TAG_REQUEST, opId.hashCode(), builder.build())
    }

    private fun choiceLabel(choice: String): String = when (choice) {
        "once" -> context.getString(R.string.notify_choice_once)
        "session" -> context.getString(R.string.notify_choice_session)
        "always" -> context.getString(R.string.notify_choice_always)
        "deny" -> context.getString(R.string.notify_choice_deny)
        else -> choice
    }

    fun cancelRequest(requestId: String) = manager.cancel(TAG_REQUEST, requestId.hashCode())

    private fun answerIntent(requestId: String, choice: String): PendingIntent {
        val intent = Intent(context, ApprovalReceiver::class.java)
            .putExtra(ApprovalReceiver.EXTRA_REQUEST, requestId)
            .putExtra(ApprovalReceiver.EXTRA_CHOICE, choice)
        return PendingIntent.getBroadcast(context, (requestId + choice).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun activity(chatId: String, title: String, reply: String) {
        val text = plainText(reply)
        val notification = NotificationCompat.Builder(context, ACTIVITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "Hermes" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1500)))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_ACTIVITY)
            .setAutoCancel(true)
            .setContentIntent(openChat(chatId, chatId.hashCode()))
            .build()
        post(TAG_CHAT, chatId.hashCode(), notification)
    }

    fun cancelChat(chatId: String) = manager.cancel(TAG_CHAT, chatId.hashCode())

    /** The quiet notification a foreground service must show. */
    fun working(text: String): Notification = NotificationCompat.Builder(context, WORKING)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Hermes")
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(openChat(null, 0))
        .build()

    companion object {
        const val ANSWERS = "answers"
        const val REQUESTS = "requests"
        const val ACTIVITY = "activity"
        const val WORKING = "working"
        const val WORKING_ID = 1
        private const val TAG_CHAT = "chat"
        private const val TAG_REQUEST = "request"
        private const val GROUP_ACTIVITY = "activity"
    }
}
