package org.evsyukov.hermes.background

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import org.evsyukov.hermes.HermesApplication
import org.evsyukov.hermes.R

/**
 * Keeps the process, and with it the chat socket, alive while the app is in the background: during
 * a turn, or all the time when the user asked for a permanent connection. The work itself happens in
 * [TurnWatcher] and the socket; the service only holds the process in the foreground.
 */
class HermesService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as HermesApplication).container
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: getString(R.string.notify_connection_open)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        try {
            ServiceCompat.startForeground(this, Notifications.WORKING_ID, container.notifications.working(text), type)
        } catch (e: Exception) {
            Log.w(TAG, "Foreground start refused", e)
            stopSelf()
            return START_NOT_STICKY
        }
        container.connection.value?.socket?.start()
        return if (container.settings.current.value.keepConnected) START_STICKY else START_NOT_STICKY
    }

    companion object {
        private const val TAG = "HermesService"
        private const val EXTRA_TEXT = "text"

        /**
         * Android lets an app start a foreground service only while it is in front (or for a few
         * exceptions), so the app starts it when a turn begins, not when the user leaves.
         */
        fun start(context: Context, text: String) {
            try {
                context.startForegroundService(Intent(context, HermesService::class.java).putExtra(EXTRA_TEXT, text))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Service not started from the background", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Service not allowed", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HermesService::class.java))
        }
    }
}

/** The buttons of an approval notification. */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = intent.getStringExtra(EXTRA_REQUEST) ?: return
        val choice = intent.getStringExtra(EXTRA_CHOICE) ?: return
        (context.applicationContext as HermesApplication).container.turns.answer(request, choice)
    }

    companion object {
        const val EXTRA_REQUEST = "request"
        const val EXTRA_CHOICE = "choice"
    }
}
