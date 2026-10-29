package org.evsyukov.hermes.data.remote

import kotlinx.serialization.json.JsonElement
import org.evsyukov.hermes.data.cache.ResponseCache
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

/** Asks the server and leaves a copy of each kept answer in [cache]. */
class NetworkReads(private val http: HermesHttp, private val cache: ResponseCache?, private val server: String) : Reads {
    override suspend fun kept(path: String, query: Map<String, Any?>): JsonElement {
        val answer = http.getJson(path, query)
        cache?.keep(server, cacheKey(path, query), answer.toString())
        return answer
    }

    override suspend fun fresh(path: String, query: Map<String, Any?>): JsonElement = http.getJson(path, query)
}

/** Answers from [cache] alone, without the network; what is not there throws [NotCachedException]. */
class CachedReads(private val cache: ResponseCache, private val server: String) : Reads {
    override suspend fun kept(path: String, query: Map<String, Any?>): JsonElement {
        val key = cacheKey(path, query)
        val text = cache.read(server, key) ?: throw NotCachedException(key)
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
