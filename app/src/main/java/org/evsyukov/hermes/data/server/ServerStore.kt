package org.evsyukov.hermes.data.server

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.auth.Tokens

private val Context.serverStore by preferencesDataStore(name = "servers")

/** Everything the app keeps about its servers; sealed as a whole, since it holds tokens and passwords. */
@Serializable
data class ServerState(
    val servers: List<ServerProfile> = emptyList(),
    val activeId: String? = null,
    val tokens: Map<String, Tokens> = emptyMap(),
) {
    val active: ServerProfile? get() = servers.firstOrNull { it.id == activeId } ?: servers.firstOrNull()
}

class ServerStore(private val context: Context, private val cipher: TokenCipher, scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _loaded = MutableStateFlow(false)

    /** False until the state was read from disk once; the first screen waits for it. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val state: StateFlow<ServerState> = context.serverStore.data
        .map { prefs -> prefs[KEY]?.let(::open) ?: ServerState() }
        .onEach { _loaded.value = true }
        .stateIn(scope, SharingStarted.Eagerly, ServerState())

    /** The state as it is on disk, for callers that run before [state] has loaded. */
    suspend fun load(): ServerState = context.serverStore.data.first()[KEY]?.let(::open) ?: ServerState()

    suspend fun update(change: (ServerState) -> ServerState) {
        context.serverStore.edit { prefs ->
            val current = prefs[KEY]?.let(::open) ?: ServerState()
            prefs[KEY] = cipher.encrypt(json.encodeToString(ServerState.serializer(), change(current)))
        }
    }

    suspend fun save(profile: ServerProfile, makeActive: Boolean = true) = update { state ->
        val others = state.servers.filterNot { it.id == profile.id }
        state.copy(servers = others + profile, activeId = if (makeActive) profile.id else state.activeId)
    }

    suspend fun remove(id: String) = update { state ->
        val rest = state.servers.filterNot { it.id == id }
        state.copy(servers = rest, tokens = state.tokens - id, activeId = if (state.activeId == id) rest.firstOrNull()?.id else state.activeId)
    }

    suspend fun activate(id: String) = update { it.copy(activeId = id) }

    suspend fun setTokens(serverId: String, tokens: Tokens?) = update { state ->
        state.copy(tokens = if (tokens == null) state.tokens - serverId else state.tokens + (serverId to tokens))
    }

    private fun open(sealed: String): ServerState? = try {
        json.decodeFromString(ServerState.serializer(), cipher.decrypt(sealed))
    } catch (e: Exception) {
        // A key lost with a restore to a new phone: the servers are entered again.
        null
    }

    private companion object {
        val KEY = stringPreferencesKey("state")
    }
}
