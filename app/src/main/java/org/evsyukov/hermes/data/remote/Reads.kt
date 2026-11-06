package org.evsyukov.hermes.data.remote

import kotlinx.serialization.json.JsonElement
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.server.ProfileLifetime
import org.evsyukov.hermes.data.server.WorkOwner
import java.io.IOException

/** The answer is not on the phone. */
class NotCachedException(request: String) : IOException("Not cached: $request")

/**
 * Where an API takes the answers of its GET requests. A [kept] answer is one worth showing before
 * the server answers, such as a chat or a list; a [fresh] one, such as secrets or logs, never
 * touches the phone's storage.
 */
interface Reads {
    suspend fun kept(path: String, query: Map<String, Any?> = emptyMap()): JsonElement

    suspend fun fresh(path: String, query: Map<String, Any?> = emptyMap()): JsonElement
}

/** Asks the server and leaves a copy of each kept answer in [cache] while [wanted] holds. */
class NetworkReads(
    private val http: HermesHttp,
    private val cache: ResponseCache?,
    private val server: String,
    private val lifetime: ProfileLifetime? = null,
    private val wanted: () -> Boolean = { true },
) : Reads {
    override suspend fun kept(path: String, query: Map<String, Any?>): JsonElement {
        // The generation is taken before the wait, so an answer that comes back after the server
        // left is not kept, while a session built afterwards uses the generation of that time.
        val at = lifetime?.current(server) ?: 0L
        val answer = http.getJson(path, query)
        val admitted = { wanted() && (lifetime?.holds(server, at) ?: true) }
        if (lifetime != null) cache?.keepOwned(WorkOwner(server, at), lifetime, cacheKey(path, query), answer.toString(), admitted)
        else cache?.keep(server, cacheKey(path, query), answer.toString(), admitted)
        return answer
    }

    override suspend fun fresh(path: String, query: Map<String, Any?>): JsonElement = http.getJson(path, query)

    /**
     * The same reads as work of [owner], captured before its requests start. A follow-up request
     * that runs after a newer authorization does not borrow that authorization's generation.
     */
    fun ownedBy(owner: WorkOwner): Reads = OwnedReads(http, cache, owner, lifetime, wanted)
}

/** [NetworkReads] for a chain that already captured the generation it began in. */
private class OwnedReads(
    private val http: HermesHttp,
    private val cache: ResponseCache?,
    private val owner: WorkOwner,
    private val lifetime: ProfileLifetime?,
    private val wanted: () -> Boolean,
) : Reads {
    override suspend fun kept(path: String, query: Map<String, Any?>): JsonElement {
        val answer = http.getJson(owner, path, query)
        val admitted = { wanted() && (lifetime?.holds(owner.profileId, owner.generation) ?: true) }
        if (lifetime != null) cache?.keepOwned(owner, lifetime, cacheKey(path, query), answer.toString(), admitted)
        else cache?.keep(owner.profileId, cacheKey(path, query), answer.toString(), admitted)
        return answer
    }

    override suspend fun fresh(path: String, query: Map<String, Any?>): JsonElement = http.getJson(owner, path, query)
}

/** Answers from [cache] alone, without the network; what is not there throws [NotCachedException]. */
class CachedReads(private val cache: ResponseCache, private val server: String, private val lifetime: ProfileLifetime? = null, private val owner: WorkOwner? = null) : Reads {
    fun ownedBy(owner: WorkOwner): CachedReads {
        require(owner.profileId == server) { "Cached reads belong to another profile" }
        return CachedReads(cache, server, lifetime, owner)
    }

    override suspend fun kept(path: String, query: Map<String, Any?>): JsonElement {
        val key = cacheKey(path, query)
        val origin = owner ?: lifetime?.owner(server)
        val text = (if (origin != null && lifetime != null) cache.readOwned(origin, lifetime, key) else cache.read(server, key)) ?: throw NotCachedException(key)
        return HermesJson.parseToJsonElement(text)
    }

    override suspend fun fresh(path: String, query: Map<String, Any?>): JsonElement = throw NotCachedException(cacheKey(path, query))
}

/** The request as the cache knows it: the path, then the parameters that are set, in a fixed order. */
fun cacheKey(path: String, query: Map<String, Any?>): String {
    val params = query.filterValues { it != null }.toSortedMap().entries.joinToString("&") { (k, v) -> "$k=$v" }
    val trimmed = path.trimStart('/')
    return if (params.isEmpty()) trimmed else "$trimmed?$params"
}
