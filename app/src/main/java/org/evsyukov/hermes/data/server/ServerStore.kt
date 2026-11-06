package org.evsyukov.hermes.data.server

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

/** What the durable state says: real content, or that the sealed value cannot be read at all. */
internal sealed interface Persisted {
    data class Known(val state: ServerState) : Persisted
    data object Unreadable : Persisted
}

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

    /** The actual backing store; the authority registry is keyed by this identity, not by a tag. */
    internal val backing: DataStore<Preferences> = context.serverStore

    /** The one generation and credential authority of [backing], shared by every wrapper. */
    val authority: ProfileLifetime = ProfileLifetime.forStore(backing)

    private val _loaded = MutableStateFlow(false)

    /** False until the state was read from disk once; the first screen waits for it. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val state: StateFlow<ServerState> = context.serverStore.data
        .map { prefs -> prefs[KEY]?.let(::open) ?: ServerState() }
        .onEach { _loaded.value = true }
        .stateIn(scope, SharingStarted.Eagerly, ServerState())

    /** The state as it is on disk, for callers that run before [state] has loaded. */
    suspend fun load(): ServerState = context.serverStore.data.first()[KEY]?.let(::open) ?: ServerState()

    /**
     * The durable state for recovery after a failed write. Unlike [load], an undecryptable or
     * malformed value is not reported as an empty state: absent, present and unreadable are three
     * different answers, and only a readable value can decide whether a write reached the disk.
     */
    internal suspend fun loadDurable(): Persisted = try {
        val raw = context.serverStore.data.first()[KEY]
        if (raw == null) {
            Persisted.Known(ServerState())
        } else {
            Persisted.Known(json.decodeFromString(ServerState.serializer(), cipher.decrypt(raw)))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Persisted.Unreadable
    }

    /**
     * The writer of profile and proxy metadata. It runs under the authority gate, and the
     * authority never rebinds an existing generation here: credentials change only through an
     * auth transition that publishes a new owner.
     */
    private suspend fun update(change: (ServerState) -> ServerState) = authority.updateProfiles(this) {
        updateNow(change)
    }

    private suspend fun updateNow(change: (ServerState) -> ServerState) {
        context.serverStore.edit { prefs ->
            val current = prefs[KEY]?.let(::open) ?: ServerState()
            prefs[KEY] = cipher.encrypt(json.encodeToString(ServerState.serializer(), change(current)))
        }
    }

    suspend fun save(profile: ServerProfile, makeActive: Boolean = true) = update { state ->
        val others = state.servers.filterNot { it.id == profile.id }
        state.copy(servers = others + profile, activeId = if (makeActive) profile.id else state.activeId)
    }

    suspend fun remove(id: String, expected: WorkOwner = authority.owner(id)): NativeResult = authority.removeProfile(this, id, expected)

    suspend fun activate(id: String) = update { it.copy(activeId = id) }

    /**
     * An authorization that did not come through a native session. It is not a raw setter: the
     * authority gives the bytes a fresh generation, so an API that captured the previous owner
     * cannot send them, and a removed profile is rejected.
     */
    suspend fun setTokens(serverId: String, tokens: Tokens): NativeResult =
        authority.authorizeExternal(this, serverId, tokens)

    /**
     * Writes the credential field of the persisted state, re-reading the state it joins, so a
     * profile or proxy edit made before this write is preserved. The one raw credential writer;
     * backend callers must hold the authority gate.
     */
    @Volatile
    internal var afterCredentialWrite: (suspend () -> Unit)? = null

    internal suspend fun writeCredentialsNow(serverId: String, tokens: Tokens?) {
        context.serverStore.edit { prefs ->
            val current = prefs[KEY]?.let(::open) ?: ServerState()
            val updated = current.copy(
                tokens = if (tokens == null) current.tokens - serverId else current.tokens + (serverId to tokens),
            )
            prefs[KEY] = cipher.encrypt(json.encodeToString(ServerState.serializer(), updated))
        }
        afterCredentialWrite?.invoke()
    }

    /** Removes a profile, its credentials and its active selection in one serialized edit. */
    internal suspend fun deleteProfileNow(id: String) = updateNow { state ->
        val rest = state.servers.filterNot { it.id == id }
        state.copy(servers = rest, tokens = state.tokens - id, activeId = if (state.activeId == id) rest.firstOrNull()?.id else state.activeId)
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
