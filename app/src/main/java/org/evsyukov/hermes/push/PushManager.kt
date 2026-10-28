package org.evsyukov.hermes.push

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.evsyukov.hermes.data.remote.HermesException
import org.evsyukov.hermes.data.remote.HermesHttp
import org.evsyukov.hermes.data.remote.long
import org.evsyukov.hermes.data.remote.string
import org.evsyukov.hermes.data.server.ServerSession
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
class PushApi(private val http: HermesHttp) {
    /** The VAPID key of the server, or null when the server has no hermes-push plugin. */
    suspend fun key(): String? = try {
        http.getJson("api/plugins/hermes-push/key").jsonObject.string("key")
    } catch (e: HermesException) {
        if (e.status == 404) null else throw e
    }

    suspend fun subscribe(endpoint: String, p256dh: String, auth: String, name: String) {
        http.postJson(
            "api/plugins/hermes-push/subscriptions",
            buildJsonObject {
                put("endpoint", endpoint)
                put("p256dh", p256dh)
                put("auth", auth)
                put("name", name)
            },
        )
    }

    suspend fun unsubscribe(endpoint: String) {
        http.delete("api/plugins/hermes-push/subscriptions", buildJsonObject { put("endpoint", endpoint) })
    }

    /** Asks the server for a test push; returns how many phones it reached. */
    suspend fun test(): Int = http.postJson("api/plugins/hermes-push/test").jsonObject.long("sent")?.toInt() ?: 0
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
) {
    private val lock = Mutex()
    private val _status = MutableStateFlow(PushStatus())
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    val active: Boolean get() = _status.value.state == PushState.ON

    /**
     * Brings the registration in line with the switch and the active server; run at start, after a
     * sign-in and whenever either changes. Registering again repairs a registration the
     * distributor lost.
     */
    suspend fun reconcile() = lock.withLock {
        val current = session()
        val stored = context.pushStore.data.first().asMap().keys.map { it.name.substringAfter(':') }.toSet()
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
        val key = try {
            PushApi(current.http).key()
        } catch (e: IOException) {
            Log.w(TAG, "Push key failed", e)
            _status.value = PushStatus(PushState.FAILED, installed, chosen)
            return@withLock
        }
        if (key == null) {
            _status.value = PushStatus(PushState.UNSUPPORTED, installed, chosen)
            return@withLock
        }
        val asked = distributor.register(current.profile.id, "Hermes · ${current.profile.url?.host.orEmpty()}", key)
        val endpoint = context.pushStore.data.first()[endpointKey(current.profile.id)]
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
        val previous = context.pushStore.data.first()[endpointKey(instance)]
        val state = try {
            PushApi(current.http).subscribe(endpoint, p256dh, auth, android.os.Build.MODEL)
            if (previous != null && previous != endpoint) runCatching { PushApi(current.http).unsubscribe(previous) }
            context.pushStore.edit { it[endpointKey(instance)] = endpoint }
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
        val endpoint = context.pushStore.data.first()[endpointKey(instance)] ?: return
        session()?.takeIf { it.profile.id == instance }?.let { current ->
            try {
                PushApi(current.http).unsubscribe(endpoint)
            } catch (e: IOException) {
                Log.w(TAG, "Push unsubscription failed", e)
            }
        }
        context.pushStore.edit { it.remove(endpointKey(instance)) }
    }

    private fun endpointKey(instance: String) = stringPreferencesKey("endpoint:$instance")

    private companion object {
        const val TAG = "HermesPush"
    }
}
