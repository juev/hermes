package org.evsyukov.hermes.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.evsyukov.hermes.ui.theme.ThemeMode

private val Context.settingsStore by preferencesDataStore(name = "settings")

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Enter sends the message; off, it starts a new line and only the button sends. */
    val enterSends: Boolean = false,
    val haptics: Boolean = true,
    /** Reasoning of the model shows open under the answer instead of folded. */
    val showReasoning: Boolean = false,
    /** Pulling the chat up past its end starts a new chat. */
    val pullForNewChat: Boolean = true,
    /** Notifies about chats that changed while the app was closed: cron results, Telegram, other devices. */
    val notifyActivity: Boolean = true,
    /** How often the background check asks the server; Android runs it at most every 15 minutes. */
    val checkMinutes: Int = 15,
    /** Keeps the chat socket open in a foreground service, so events arrive at once. */
    val keepConnected: Boolean = false,
    /** Push through UnifiedPush when the server has the hermes-push plugin and a distributor is installed. */
    val push: Boolean = true,
    /** How much the cache of chats and screens may take on the phone, in megabytes. */
    val cacheLimitMb: Int = DEFAULT_CACHE_LIMIT_MB,
) {
    companion object {
        const val DEFAULT_CACHE_LIMIT_MB = 500
    }
}

/** The cache limit typed by the user: a whole number of megabytes, zero or more; null when it is not one. */
fun cacheLimitOf(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it >= 0 }

class AppSettings(private val context: Context, scope: CoroutineScope) {
    private val stored: Flow<Settings> = context.settingsStore.data.map { prefs ->
        Settings(
            theme = prefs[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            enterSends = prefs[ENTER_SENDS] ?: false,
            haptics = prefs[HAPTICS] ?: true,
            showReasoning = prefs[SHOW_REASONING] ?: false,
            pullForNewChat = prefs[PULL_NEW_CHAT] ?: true,
            notifyActivity = prefs[NOTIFY_ACTIVITY] ?: true,
            checkMinutes = prefs[CHECK_MINUTES] ?: 15,
            keepConnected = prefs[KEEP_CONNECTED] ?: false,
            push = prefs[PUSH] ?: true,
            cacheLimitMb = prefs[CACHE_LIMIT_MB] ?: Settings.DEFAULT_CACHE_LIMIT_MB,
        )
    }

    val current: StateFlow<Settings> = stored.stateIn(scope, SharingStarted.Eagerly, Settings())

    /**
     * The permanent-connection switch as it is on disk; null until it is read. Unlike [current] it
     * does not start from the defaults, so a process the system started for the service does not
     * take them for the user's choice. The service, its stickiness and the sockets kept open all
     * follow this one value, so none of them acts on a value another collector has not caught up with.
     */
    val keepConnected: StateFlow<Boolean?> = stored.map { it.keepConnected }.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun update(change: (Settings) -> Settings) {
        context.settingsStore.edit { prefs ->
            val next = change(current.value)
            prefs[THEME] = next.theme.name
            prefs[ENTER_SENDS] = next.enterSends
            prefs[HAPTICS] = next.haptics
            prefs[SHOW_REASONING] = next.showReasoning
            prefs[PULL_NEW_CHAT] = next.pullForNewChat
            prefs[NOTIFY_ACTIVITY] = next.notifyActivity
            prefs[CHECK_MINUTES] = next.checkMinutes
            prefs[KEEP_CONNECTED] = next.keepConnected
            prefs[PUSH] = next.push
            prefs[CACHE_LIMIT_MB] = next.cacheLimitMb
        }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val ENTER_SENDS = booleanPreferencesKey("enter_sends")
        val HAPTICS = booleanPreferencesKey("haptics")
        val SHOW_REASONING = booleanPreferencesKey("show_reasoning")
        val PULL_NEW_CHAT = booleanPreferencesKey("pull_new_chat")
        val NOTIFY_ACTIVITY = booleanPreferencesKey("notify_activity")
        val CHECK_MINUTES = intPreferencesKey("check_minutes")
        val KEEP_CONNECTED = booleanPreferencesKey("keep_connected")
        val PUSH = booleanPreferencesKey("push")
        val CACHE_LIMIT_MB = intPreferencesKey("cache_limit_mb")
    }
}
