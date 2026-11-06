package org.evsyukov.hermes.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.evsyukov.hermes.Connection
import org.evsyukov.hermes.data.remote.SearchHit

data class SearchSnapshot<C : Any>(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
    /** The request the hits belong to; a result of another server or attempt is not actionable. */
    val request: SearchIdentity<C>? = null,
)

/** The server and the attempt a search result belongs to; two attempts never share an identity. */
data class SearchIdentity<C : Any>(val connection: C, val generation: Long)

typealias SearchState = SearchSnapshot<Connection>
typealias SearchRequestIdentity = SearchIdentity<Connection>

/** The production search owner. Connections are opaque identities; only search I/O knows their type. */
internal class SearchOwner<C : Any>(
    private val connections: StateFlow<C?>,
    private val scope: CoroutineScope,
    private val searchChats: suspend (C, String) -> List<SearchHit>,
) {
    private val _search = MutableStateFlow(SearchSnapshot<C>())
    val search: StateFlow<SearchSnapshot<C>> = _search.asStateFlow()
    private var searchGeneration = 0L
    private var searchConnection: C? = null
    private var searchJob: Job? = null

    init {
        scope.launch {
            connections.collect { connection ->
                if (connection === searchConnection) return@collect
                searchConnection = connection
                startSearch(_search.value.query, connection)
            }
        }
    }

    fun setQuery(query: String) {
        val connection = connections.value
        if (query == _search.value.query && connection === searchConnection) return
        searchConnection = connection
        startSearch(query, connection)
    }

    fun admits(hit: SearchHit, identity: SearchIdentity<C>?): Boolean {
        if (identity == null || !isCurrent(identity)) return false
        val state = _search.value
        return state.request == identity && hit in state.hits
    }

    /**
     * Ends the request under way, drops what it showed, and starts the next one: [query] on
     * [connection], after the debounce for a nonblank query that has a server to run on. Everything
     * visible is replaced at once, so no hit of another server or attempt stays actionable.
     */
    private fun startSearch(query: String, connection: C?) {
        searchJob?.cancel()
        searchJob = null
        searchGeneration += 1
        val identity = if (query.isNotBlank() && connection != null) SearchIdentity(connection, searchGeneration) else null
        _search.value = SearchSnapshot(query = query, searching = identity != null, request = identity)
        if (identity == null) return
        searchJob = scope.launch {
            try {
                delay(SEARCH_DEBOUNCE)
                if (!isCurrent(identity)) return@launch
                val hits = searchChats(identity.connection, query)
                if (!isCurrent(identity)) return@launch
                _search.value = SearchSnapshot(query = query, hits = hits, request = identity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrent(identity)) return@launch
                _search.value = SearchSnapshot(query = query, error = e.message, request = identity)
            }
        }
    }

    /** Whether [identity] is still the request of the connection shown; the answer of an older one is dropped. */
    private fun isCurrent(identity: SearchIdentity<C>): Boolean =
        identity.generation == searchGeneration && connections.value === identity.connection

    private companion object {
        /** How long typing settles before the query goes to the server. */
        const val SEARCH_DEBOUNCE = 300L
    }
}
