package org.evsyukov.hermes.testing

import kotlinx.coroutines.CancellationException
import org.evsyukov.hermes.data.server.NativeResult
import org.evsyukov.hermes.data.server.Persisted
import org.evsyukov.hermes.data.server.ServerStore

/** An absent synthetic profile is idempotent only when both disk and authority establish absence. */
internal suspend fun requireFixtureRemoval(store: ServerStore, id: String, result: NativeResult) {
    when (result) {
        is NativeResult.Applied -> Unit
        is NativeResult.Failed -> throw result.error
        NativeResult.Rejected -> when (val disk = store.loadDurable()) {
            Persisted.Unreadable -> error("Fixture reset rejected with unreadable durable state: $id")
            is Persisted.Known -> check(disk.state.servers.none { it.id == id } && !store.authority.present(id)) {
                "Fixture reset rejected while profile remains: $id"
            }
        }
    }
}

/** Each profile is an independent cleanup obligation; a failed result does not finish it. */
internal suspend fun resetFixtureProfiles(store: ServerStore, ids: Iterable<String>, observe: (NativeResult) -> Unit = {}) {
    var first: Throwable? = null
    ids.forEach { id ->
        try {
            val result = store.remove(id)
            observe(result)
            requireFixtureRemoval(store, id, result)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val primary = first
            if (primary == null) first = error else if (primary !== error) primary.addSuppressed(error)
        }
    }
    first?.let { throw it }
}
