package org.evsyukov.hermes.push

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.evsyukov.hermes.data.remote.HermesException
import org.evsyukov.hermes.data.remote.HermesHttp
import org.evsyukov.hermes.data.remote.long
import org.evsyukov.hermes.data.remote.string
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.WorkOwner
import java.io.IOException

private val Context.pushStore by preferencesDataStore(name = "push")

enum class PushState {
    /** Push is switched off in the settings. */
    OFF,

    /** No UnifiedPush distributor, such as ntfy, is installed. */
    NO_DISTRIBUTOR,

    /** Several distributors are installed and the user has to pick one. */
    CHOOSE,

    /** Waiting for the endpoint of the distributor or for the server to take it. */
    CONNECTING,

    /** The server has the endpoint and pushes to it. */
    ON,

    /** The server has no hermes-push plugin. */
    UNSUPPORTED,

    /** The key, the distributor or the server failed; the next start tries again. */
    FAILED,
}

data class PushStatus(val state: PushState = PushState.OFF, val distributors: List<Distributor> = emptyList(), val distributor: String? = null)

/** The routes of the hermes-push plugin of the server. */
class PushApi(private val http: HermesHttp, private val channel: Boolean = http.androidChannel, private val owner: WorkOwner? = null) {
    var channelOwner: String? = null
        private set
    private val root: String get() = if (channel) "api/plugins/hermes-android/push" else "api/plugins/hermes-push"
    private suspend fun get(path: String) = if (owner == null) http.getJson(path) else http.getJson(owner, path)
    private suspend fun post(path: String, body: JsonObject = JsonObject(emptyMap())) =
        if (owner == null) http.postJson(path, body) else http.postJson(owner, path, body)
    /** The VAPID key of the server, or null when the server has no hermes-push plugin. */
    suspend fun key(): String? = try {
        val answer = get("$root/key").jsonObject
        channelOwner = answer.string("channel_owner")
        answer.string("key")
    } catch (e: HermesException) {
        if (e.status == 404) null else throw e
    }

    /** [device] stays the same across reinstalls, so the server drops the endpoints this phone has lost. */
    suspend fun subscribe(endpoint: String, p256dh: String, auth: String, name: String, device: String) {
        post(
            "$root/subscriptions",
            buildJsonObject {
                put("endpoint", endpoint)
                put("p256dh", p256dh)
                put("auth", auth)
                put("name", name)
                put("device", device)
            },
        )
    }

    suspend fun unsubscribe(endpoint: String) {
        val body = buildJsonObject { put("endpoint", endpoint) }
        if (owner == null) http.delete("$root/subscriptions", body) else http.delete(owner, "$root/subscriptions", body)
    }

    /** Asks the server for a test push; returns how many phones it reached. */
    suspend fun test(): Int = post("$root/test").jsonObject.long("sent")?.toInt() ?: 0
}

/**
 * Push through UnifiedPush for the active server, after Brook: the server is an instance of the
 * distributor, its endpoint and keys go to the hermes-push plugin, which then pushes, encrypted,
 * when a turn ends. Without a distributor or the plugin the app stays on its periodic check.
 */
class PushManager(
    private val context: Context,
    private val distributor: PushDistributor,
    private val session: () -> ServerSession?,
    private val enabled: () -> Boolean,
    backing: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>? = null,
) {
    private val data = backing ?: context.pushStore
    private val lock = Mutex()
    private val _status = MutableStateFlow(PushStatus())
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    val active: Boolean get() = _status.value.state == PushState.ON

    suspend fun accepts(message: PushMessage, instance: String?): Boolean {
        if (!enabled()) return false
        val current = session() ?: return false
        val profile = current.profile
        if (instance != null && instance != profile.id) return false
        if (!profile.androidChannel) return message.channel.isBlank()
        if (instance != profile.id || message.channel != "android-minimal-v1") return false
        val origin = current.owner()
        val kept = data.data.first()
        return current.holds(origin) && session()?.profile == profile &&
            kept[channelGenerationKey(profile.id)] == origin.generation &&
            message.channelOwner.isNotBlank() && kept[channelOwnerKey(profile.id)] == message.channelOwner
    }

    /**
     * Brings the registration in line with the switch and the active server; run at start, after a
     * sign-in and whenever either changes. Registering again repairs a registration the
     * distributor lost.
     */
    suspend fun reconcile() = lock.withLock {
        val current = session()
        val stored = data.data.first().asMap().keys.map { it.name.substringAfter(':') }.toSet()
        // Instances of servers that are no longer active stop getting pushes.
        for (id in stored - setOfNotNull(current?.profile?.id)) takeBack(id)
        if (!enabled() || current == null) {
            current?.profile?.id?.let { takeBack(it) }
            _status.value = PushStatus(PushState.OFF)
            return@withLock
        }
        val installed = distributor.installed()
        val chosen = distributor.saved() ?: installed.singleOrNull()?.packageName?.also(distributor::save)
        if (chosen == null) {
            _status.value = PushStatus(if (installed.isEmpty()) PushState.NO_DISTRIBUTOR else PushState.CHOOSE, installed)
            return@withLock
        }
        val origin = current.owner()
        val api = PushApi(current.http, owner = origin)
        val mode = if (current.profile.androidChannel) "android-minimal-v1" else "dashboard"
        val kept = data.data.first()
        val oldEndpoint = kept[endpointKey(current.profile.id)]
        val oldMode = kept[modeKey(current.profile.id)] ?: "dashboard"
        if (oldEndpoint != null && oldMode != mode) {
            try {
                PushApi(current.http, channel = oldMode == "android-minimal-v1", owner = origin).unsubscribe(oldEndpoint)
            } catch (e: IOException) {
                if (e !is HermesException || e.status != 404) {
                    _status.value = PushStatus(PushState.FAILED, installed, chosen)
                    return@withLock
                }
            }
            if (!current.holds(origin) || session()?.profile != current.profile) return@withLock
            data.edit {
                if (current.holds(origin) && session()?.profile == current.profile) {
                    it.remove(endpointKey(current.profile.id))
                    it.remove(modeKey(current.profile.id))
                    it.remove(channelOwnerKey(current.profile.id))
                    it.remove(channelGenerationKey(current.profile.id))
                }
            }
        }
        val key = try {
            api.key()
        } catch (e: IOException) {
            Log.w(TAG, "Push key failed", e)
            _status.value = PushStatus(PushState.FAILED, installed, chosen)
            return@withLock
        }
        if (key == null) {
            _status.value = PushStatus(PushState.UNSUPPORTED, installed, chosen)
            return@withLock
        }
        if (!current.holds(origin) || session()?.profile != current.profile) return@withLock
        if (current.profile.androidChannel) {
            val channelOwner = api.channelOwner
            if (channelOwner == null || !channelOwner.matches(Regex("[a-f0-9]{64}"))) {
                _status.value = PushStatus(PushState.FAILED, installed, chosen)
                return@withLock
            }
            data.edit {
                if (current.holds(origin) && session()?.profile == current.profile) {
                    it[channelOwnerKey(current.profile.id)] = channelOwner
                    it[channelGenerationKey(current.profile.id)] = origin.generation
                }
            }
        }
        val asked = distributor.register(current.profile.id, "Hermes · ${current.profile.url?.host.orEmpty()}", key)
        val endpoint = data.data.first()[endpointKey(current.profile.id)]
        _status.value = PushStatus(
            when {
                !asked -> PushState.FAILED
                endpoint != null -> PushState.ON
                else -> PushState.CONNECTING
            },
            installed,
            chosen,
        )
    }

    /** The user picked [packageName] among several distributors. */
    suspend fun choose(packageName: String) {
        distributor.save(packageName)
        reconcile()
    }

    /** The distributor gave an endpoint for [instance]; the server gets it unless it has it already. */
    suspend fun onNewEndpoint(instance: String, endpoint: String, p256dh: String, auth: String) = lock.withLock {
        val current = session()
        if (!enabled() || current == null || current.profile.id != instance) {
            distributor.unregister(instance)
            return@withLock
        }
        val previous = data.data.first()[endpointKey(instance)]
        val origin = current.owner()
        val api = PushApi(current.http, owner = origin)
        val state = try {
            api.subscribe(endpoint, p256dh, auth, android.os.Build.MODEL, deviceId())
            if (!current.holds(origin) || session()?.profile != current.profile) return@withLock
            if (previous != null && previous != endpoint) runCatching { api.unsubscribe(previous) }
            data.edit {
                if (current.holds(origin) && session()?.profile == current.profile) {
                    it[endpointKey(instance)] = endpoint
                    it[modeKey(instance)] = if (current.profile.androidChannel) "android-minimal-v1" else "dashboard"
                }
            }
            PushState.ON
        } catch (e: IOException) {
            Log.w(TAG, "Push subscription failed", e)
            PushState.FAILED
        }
        _status.value = _status.value.copy(state = state)
    }

    suspend fun onFailed(instance: String) = lock.withLock {
        if (session()?.profile?.id == instance) _status.value = _status.value.copy(state = PushState.FAILED)
    }

    /** The distributor dropped the instance; the server stops pushing to it. */
    suspend fun onUnregistered(instance: String) = lock.withLock {
        forgetEndpoint(instance)
        if (session()?.profile?.id == instance) _status.value = _status.value.copy(state = PushState.FAILED)
    }

    suspend fun test(): Int = PushApi(requireNotNull(session()) { "No server" }.http).test()

    /** Before a server is removed or push is switched off: the server forgets the endpoint, the distributor the instance. */
    private suspend fun takeBack(instance: String) {
        forgetEndpoint(instance)
        distributor.unregister(instance)
    }

    private suspend fun forgetEndpoint(instance: String) {
        val endpoint = data.data.first()[endpointKey(instance)] ?: return
        session()?.takeIf { it.profile.id == instance }?.let { current ->
            try {
                PushApi(current.http).unsubscribe(endpoint)
            } catch (e: IOException) {
                Log.w(TAG, "Push unsubscription failed", e)
            }
        }
        data.edit { it.remove(endpointKey(instance)) }
    }

    private fun endpointKey(instance: String) = stringPreferencesKey("endpoint:$instance")
    private fun modeKey(instance: String) = stringPreferencesKey("mode:$instance")
    private fun channelOwnerKey(instance: String) = stringPreferencesKey("channel_owner:$instance")
    private fun channelGenerationKey(instance: String) = longPreferencesKey("channel_generation:$instance")

    /** ANDROID_ID: one per signing key and user of the phone, kept across reinstalls and cleared data. */
    @SuppressLint("HardwareIds")
    private fun deviceId(): String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    private companion object {
        const val TAG = "HermesPush"
    }
}
