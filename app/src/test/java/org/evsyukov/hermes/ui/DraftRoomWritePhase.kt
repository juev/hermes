package org.evsyukov.hermes.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.server.WorkOwner
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Shared fixture response-write phase, with a separate probe-only legacy safety stop. */
internal class DraftRoomWritePhase(private val cache: ResponseCache, private val probe: Boolean) {
    data class Write(val owner: WorkOwner, val key: String, val child: Job)
    enum class Boundary { HELD_PHASE, LEGACY_PROGRESS }
    data class Decision(val write: Write, val boundary: Boundary)
    data class LegacyEntry(val write: Write, val beforePhaseRelease: Boolean)

    // Opt in before installing callbacks or launching any fixture work.
    val producers = cache.producers
    val writes = ConcurrentHashMap<Job, Write>()
    val otherWrites = CopyOnWriteArrayList<Write>()
    val legacy = CopyOnWriteArrayList<LegacyEntry>()
    val decision = CompletableDeferred<Decision>()
    val phaseRelease = CompletableDeferred<Unit>()
    val safetyRelease = CompletableDeferred<Unit>()
    @Volatile private var retiringId: String? = null
    var selected: Write? = null

    init {
        check(cache.beforeOwnedWriteObservation == null && cache.beforeOwnedWrite == null)
        cache.beforeOwnedWriteObservation = { owner, key, child -> observe(owner, key, child) }
        cache.beforeOwnedWrite = {
            val child = checkNotNull(currentCoroutineContext()[Job])
            val write = checkNotNull(writes[child]) { "legacy progress has no consumed write provenance" }
            val retiring = retiringId
            if (retiring != null && write.owner.profileId != retiring) {
                legacy += LegacyEntry(write, !phaseRelease.isCompleted)
                if (isList(write)) decision.complete(Decision(write, Boundary.LEGACY_PROGRESS))
                // Independent negative-run safety. This is after the missing phase, before admission.
                if (probe) safetyRelease.await()
            }
        }
    }

    private fun isList(write: Write) = write.key.substringBefore('?') == "api/sessions" && '?' in write.key

    /** Holds other-profile response writers before admission until the shared fixture releases them. */
    private suspend fun observe(owner: WorkOwner, key: String, child: Job) {
        val write = Write(owner, key, child)
        check(writes.putIfAbsent(child, write) == null)
        val retiring = retiringId
        if (retiring != null && owner.profileId != retiring) {
            otherWrites += write
            if (isList(write)) decision.complete(Decision(write, Boundary.HELD_PHASE))
            phaseRelease.await()
        }
    }

    fun arm(retiringProfileId: String) { retiringId = retiringProfileId }

    fun releaseAll() {
        phaseRelease.complete(Unit)
        safetyRelease.complete(Unit)
    }

    /** Actual captured writer exits precede detaching callbacks; outer fixture also joins its roots. */
    fun close(pump: () -> Unit) {
        releaseAll()
        try {
            check(joinDraftJobs(producers.toList(), 10_000, pump)) { "write phase producers did not finish" }
            check(producers.all { it.isCompleted }) { "write phase producers remain unjoined" }
            if (probe) {
                println("ROOM_PHASE_CLEANUP independent releases complete; capturedRegisteredJobsJoined=${producers.size}; attributedOtherWrites=${otherWrites.size}; selectedCompleted=${selected?.child?.isCompleted}")
                otherWrites.forEach { write ->
                    println("ROOM_PHASE_ATTRIBUTED owner=${write.owner} key=${write.key} actualChild=${write.child} completed=${write.child.isCompleted} legacyEntries=${legacy.count { it.write.child === write.child }}")
                }
            }
        } finally {
            // Detach on both routes even if an owned writer cannot join; outer roots still guard closure.
            cache.beforeOwnedWriteObservation = null
            cache.beforeOwnedWrite = null
        }
    }
}
