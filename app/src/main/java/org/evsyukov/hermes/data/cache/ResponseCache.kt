package org.evsyukov.hermes.data.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Answers of the servers kept on the phone, so chats and screens show at once and without a
 * network. The total stays within [limit] bytes: whatever was read or written longest ago goes first.
 * While the limit is unknown (null), nothing is evicted.
 */
class ResponseCache(
    private val dao: ResponseDao,
    private val scope: CoroutineScope,
    private val limit: () -> Long?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Writes and evictions one at a time, so the total they count is the one on disk. */
    private val lock = Mutex()

    suspend fun read(server: String, request: String): String? = withContext(Dispatchers.IO) {
        val parts = dao.parts(server, request)
        if (parts.isEmpty()) return@withContext null
        dao.touch(server, request, now())
        parts.joinToString("")
    }

    /** Keeps [body] in the background; the answer it came with does not wait for the disk. */
    fun keep(server: String, request: String, body: String): Job = scope.launch(Dispatchers.IO) { write(server, request, body) }

    suspend fun write(server: String, request: String, body: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val size = body.toByteArray().size.toLong()
            dao.put(CachedResponse(server, request, size, now()), parts(body))
            evict()
        }
    }

    /** Drops the responses of [server] whose request starts with [prefix], e.g. those of a deleted chat. */
    suspend fun forget(server: String, prefix: String = "") = withContext(Dispatchers.IO) {
        lock.withLock { dao.deleteStarting(server, prefix) }
    }

    suspend fun drop(server: String, request: String) = withContext(Dispatchers.IO) {
        lock.withLock { dao.delete(server, request) }
    }

    /** Brings the total within a limit that was just lowered. */
    suspend fun trim() = withContext(Dispatchers.IO) { lock.withLock { evict() } }

    suspend fun size(): Long = withContext(Dispatchers.IO) { dao.total() }

    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock { dao.clear() } }

    private suspend fun evict() {
        val max = limit() ?: return
        var total = dao.total()
        if (total <= max) return
        for (old in dao.byUse()) {
            dao.delete(old.server, old.request)
            total -= old.size
            if (total <= max) break
        }
    }

    /** Cuts [body] into parts of at most [PART] characters, never between the halves of a surrogate pair. */
    private fun parts(body: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        while (start < body.length) {
            var end = minOf(start + PART, body.length)
            if (end < body.length && Character.isHighSurrogate(body[end - 1])) end--
            parts += body.substring(start, end)
            start = end
        }
        return parts
    }

    private companion object {
        /** Characters in a part: at most 4 bytes each, so a part stays well below the 2 MB window. */
        const val PART = 256 * 1024
    }
}
