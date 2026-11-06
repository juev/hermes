package org.evsyukov.hermes.ui

import kotlinx.coroutines.withTimeout
import org.evsyukov.hermes.data.server.ServerStore
import org.evsyukov.hermes.data.server.ServerState
import org.evsyukov.hermes.data.server.Persisted
import org.evsyukov.hermes.data.server.NativeResult
import org.evsyukov.hermes.testing.resetFixtureProfiles

/** Synthetic fixtures use real admitted profile/credential APIs, never a raw authority bypass. */
internal suspend fun ServerStore.clearSyntheticServers(timeoutMillis: Long = 10_000) {
    withTimeout(timeoutMillis) {
        val durable = loadDurable()
        check(durable is Persisted.Known) { "Synthetic profile reset cannot establish readable state" }
        resetFixtureProfiles(this@clearSyntheticServers, durable.state.servers.map { it.id })
    }
}

internal suspend fun ServerStore.restoreSyntheticState(state: ServerState) {
    clearSyntheticServers()
    state.servers.forEach { save(it, makeActive = false) }
    state.tokens.forEach { (id, tokens) ->
        when (val result = setTokens(id, tokens)) {
            is NativeResult.Applied -> Unit
            is NativeResult.Failed -> throw result.error
            NativeResult.Rejected -> error("Synthetic credentials rejected: $id")
        }
    }
    state.activeId?.let { activate(it) }
}
