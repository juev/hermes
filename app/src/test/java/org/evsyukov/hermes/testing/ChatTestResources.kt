package org.evsyukov.hermes.testing

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerStore

/** Explicit resources of a synthetic chat fixture, never process-wide services. */
internal class ChatTestResources {
    private val profiles = linkedMapOf<String, ServerStore>()
    private val sockets = mutableListOf<GatewaySocket>()
    private val cleanup = FixtureCleanup()

    fun store(context: Context, cipher: TokenCipher, scope: CoroutineScope, profile: ServerProfile, observationData: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>? = null): ServerStore {
        profiles[profile.id]?.let { return it }
        val store = ServerStore(context, cipher, scope, observationData)
        profiles[profile.id] = store
        runBlocking { store.save(profile) }
        return store
    }

    fun socket(socket: GatewaySocket): GatewaySocket = socket.also { sockets += it }

    fun finish(job: Job, closures: List<Pair<String, () -> Unit>>) {
        cleanup.finish(
            listOf(job),
            sockets.mapIndexed { i, socket -> "stop socket $i" to { socket.stop() } } +
                profiles.map { (id, store) -> "remove profile $id" to { runBlocking<Unit> {
                    when (val result = store.remove(id)) {
                        is org.evsyukov.hermes.data.server.NativeResult.Applied -> { result.cleanupError?.let { throw it }; check(!result.cleanupSuperseded) { "Fixture cleanup superseded: $id" } }
                        is org.evsyukov.hermes.data.server.NativeResult.Failed -> throw result.error
                        org.evsyukov.hermes.data.server.NativeResult.Rejected -> error("Fixture profile removal was rejected: $id")
                    }
                } } },
            closures,
        )
    }
}
