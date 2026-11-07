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
    @Volatile private var observedproducers: java.util.concurrent.CopyOnWriteArrayList<Job>? = null
    internal val producers: java.util.concurrent.CopyOnWriteArrayList<Job>
        get() = synchronized(this) {
            observedproducers ?: java.util.concurrent.CopyOnWriteArrayList<Job>().also { observedproducers = it }
        }

    suspend fun read(server: String, request: String): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            val parts = dao.parts(server, request)
            if (parts.isEmpty()) null else {
                dao.touch(server, request, now())
                parts.joinToString("")
            }
        }
    }

    suspend fun readOwned(owner: org.evsyukov.hermes.data.server.WorkOwner, lifetime: org.evsyukov.hermes.data.server.ProfileLifetime, request: String): String? =
        lifetime.withOwner(owner) { read(owner.profileId, request) }

    /** Keeps [body] in the background while [wanted] holds; the answer it came with does not wait for the disk. */
    fun keep(server: String, request: String, body: String, wanted: () -> Boolean = { true }): Job =
        scope.launch(Dispatchers.IO) { write(server, request, body, wanted) }.also { observedproducers?.add(it) }

    internal var beforeOwnedWrite: (suspend () -> Unit)? = null

    internal var beforeOwnedWriteObservation: (suspend (org.evsyukov.hermes.data.server.WorkOwner, String, Job) -> Unit)? = null

    fun keepOwned(owner: org.evsyukov.hermes.data.server.WorkOwner, lifetime: org.evsyukov.hermes.data.server.ProfileLifetime, request: String, body: String, wanted: () -> Boolean): Job =
        scope.launch(Dispatchers.IO) {
            beforeOwnedWriteObservation?.invoke(owner, request, coroutineContext[Job]!!)
            beforeOwnedWrite?.invoke()
            lifetime.withOwner(owner) { write(owner.profileId, request, body, wanted) }
        }.also { observedproducers?.add(it) }

    suspend fun write(server: String, request: String, body: String, wanted: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        lock.withLock {
            // A session retired before the disk was reached writes nothing, and one retired while it
            // holds the lock is wiped by the forget that waits for it.
            if (!wanted()) return@withLock
            val size = body.toByteArray().size.toLong()
            dao.put(CachedResponse(server, request, size, now()), parts(body))
            evict()
        }
    }

    /** Drops the responses of [server] whose request starts with [prefix], e.g. those of a deleted chat. */
    @Volatile internal var onForgetStarting: ((String) -> Unit)? = null

    suspend fun forget(server: String, prefix: String = "", wanted: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        lock.withLock { if (wanted()) { onForgetStarting?.invoke(server); dao.deleteStarting(server, prefix) } }
    }

    suspend fun drop(server: String, request: String, wanted: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        lock.withLock { if (wanted()) dao.delete(server, request) }
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
