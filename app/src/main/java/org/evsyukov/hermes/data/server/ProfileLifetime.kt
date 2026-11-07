package org.evsyukov.hermes.data.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.evsyukov.hermes.data.auth.Tokens
import java.util.concurrent.ConcurrentHashMap

/** The profile and the generation of it that a piece of work started in. */
data class WorkOwner(val profileId: String, val generation: Long)

/** The outcome of an auth transition, before any follow-up work starts. */
sealed interface NativeResult {
    /** The change is on disk and [owner] is the authority it created. */
    data class Applied(val owner: WorkOwner, val cleanupError: Exception? = null, val cleanupSuperseded: Boolean = false) : NativeResult

    /** A newer authorization or a retirement won; nothing was written or published. */
    data object Rejected : NativeResult

    /** The current attempt failed; the previous credentials and owner stay. */
    data class Failed(val error: Exception) : NativeResult
}

/**
 * The one authority of a real backing DataStore: generations, the credential bytes bound to each
 * generation, and the admission gate that orders auth transitions with local persistence.
 *
 * There is one instance per backing store in the process, obtained with [forStore]; a second
 * wrapper over the same store shares it instead of adopting an independently sampled epoch.
 * Bootstrap reads the persisted profiles once, before sessions work with them, and is the only
 * place that adopts credentials that no live relation produced.
 *
 * A generation that is not in [epochs] is not admissible. There is no default generation: an
 * absent profile, a removed one and a profile that was not bootstrapped all fail [holds], so no
 * caller can mint authority by asking for generation zero.
 */
class ProfileLifetime private constructor(private val backing: Any) {
    private data class Bound(val generation: Long, val tokens: Tokens?)

    /** Serializes auth transitions and actual local persistence, never held across network/browser waits. */
    private val gate = Mutex()

    /** Observes arrival before the existing authority admission, without changing its decision. */
    @Volatile
    internal var beforeCleanupAdmission: (suspend (WorkOwner, Boolean) -> Unit)? = null
    @Volatile
    internal var beforeNativeAdmission: (() -> Unit)? = null
    @Volatile
    internal var beforeProfileAdmission: (() -> Unit)? = null

    /** Fixture boundary after durable profile persistence, before real missing-owner minting. */
    @Volatile internal var beforeProfileMint: (suspend (ServerState) -> Unit)? = null

    /** Guards only short, non-suspending epoch/binding publication and synchronous application. */
    private val guard = Any()

    private var next = 0L
    private val epochs = HashMap<String, Long>()
    private val bindings = HashMap<String, Bound>()

    /** The removal marker of a profile that is absent; a later profile with the same id has none. */
    private val removals = HashMap<String, Long>()
    private var bootstrapped = false

    private val _owners = MutableStateFlow<Map<String, WorkOwner>>(emptyMap())
    private val _removalOwners = MutableStateFlow<Map<String, WorkOwner>>(emptyMap())

    /** Immutable removal origins; repeated same-ID removals publish distinct generations. */
    internal val removalOwners: StateFlow<Map<String, WorkOwner>> = _removalOwners.asStateFlow()

    /** The observable owner of every profile, keyed by profile; one profile cannot overwrite another. */
    val owners: StateFlow<Map<String, WorkOwner>> = _owners.asStateFlow()

    private val _incarnations = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Last durable inclusion of each profile; auth renewal never changes its session root. */
    internal val incarnations: StateFlow<Map<String, Long>> = _incarnations.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /** True once the persisted profiles and tokens were adopted; sessions are exposed after this. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** The generation work starting now belongs to; 0 when the profile has none. */
    fun current(profileId: String): Long = synchronized(guard) { epochs[profileId] ?: 0L }

    /** Whether the profile is on the phone and work of [generation] may still write. */
    fun holds(profileId: String, generation: Long): Boolean = synchronized(guard) {
        epochs[profileId] == generation
    }

    /** Whether the profile is still on the phone; a removed profile is absent even if work remains. */
    fun present(profileId: String): Boolean = synchronized(guard) { epochs.containsKey(profileId) }

    /** The single credential snapshot of [generation], or null when it is not the current one. */
    fun credentials(profileId: String, generation: Long): Tokens? = synchronized(guard) {
        if (epochs[profileId] != generation) return null
        bindings[profileId]?.takeIf { it.generation == generation }?.tokens
    }

    /** The credentials a request starting now would send. */
    fun currentCredentials(profileId: String): Tokens? = credentials(profileId, current(profileId))

    fun owner(profileId: String): WorkOwner = WorkOwner(profileId, current(profileId))

    fun isBootstrapped(): Boolean = synchronized(guard) { bootstrapped }

    /**
     * Runs the local cleanup of [generation] only while that exact retirement still stands. The
     * gate orders the check and the cleanup with every auth transition, so a fresh authorization
     * and a fresh cache write cannot land between them and be erased by the older cleanup.
     */
    suspend fun <T> withRetirement(profileId: String, generation: Long, block: suspend () -> T): T? = run {
        beforeCleanupAdmission?.invoke(WorkOwner(profileId, generation), false)
        gate.withLock {
            if (!synchronized(guard) { epochs[profileId] == generation }) return@withLock null
            block()
        }
    }

    /**
     * Runs the local cleanup of a removal only while the profile is still absent and the removal
     * marker is still the one this attempt published. Re-adding the same id clears the marker, so
     * an older removal cannot delete the new profile's responses or read state.
     */
    suspend fun <T> withRemoval(profileId: String, generation: Long, block: suspend () -> T): T? = run {
        beforeCleanupAdmission?.invoke(WorkOwner(profileId, generation), true)
        gate.withLock {
            val stillRemoved = synchronized(guard) {
                !epochs.containsKey(profileId) && removals[profileId] == generation
            }
            if (!stillRemoved) return@withLock null
            block()
        }
    }

    /** Auth gate -> local store lock, including completion of the actual serialized mutation. */
    suspend fun <T> withOwner(owner: WorkOwner, block: suspend () -> T): T? = gate.withLock {
        if (!holds(owner.profileId, owner.generation)) null else block()
    }

    fun <T> publishRemoval(owner: WorkOwner, block: () -> T): T? = synchronized(guard) {
        if (epochs.containsKey(owner.profileId) || removals[owner.profileId] != owner.generation) null else block()
    }

    /** Short synchronous publication, ordered with generation replacement; never suspends. */
    fun <T> publish(owner: WorkOwner, block: () -> T): T? = synchronized(guard) {
        if (epochs[owner.profileId] != owner.generation) null else block()
    }

    /** Reads persisted profiles and tokens once, before any concurrent auth transition. */
    suspend fun bootstrap(store: ServerStore) = gate.withLock { bootstrapLocked(store) }

    private suspend fun bootstrapLocked(store: ServerStore) {
        require(store.backing === backing) { "A store of another DataStore cannot use this authority" }
        if (synchronized(guard) { bootstrapped }) return
        val state = store.load()
        synchronized(guard) {
            if (!bootstrapped) {
                state.servers.forEach { profile ->
                    val generation = ++next
                    epochs[profile.id] = generation
                    bindings[profile.id] = Bound(generation, state.tokens[profile.id])
                    _incarnations.value = _incarnations.value + (profile.id to generation)
                    removals.remove(profile.id)
                    _removalOwners.value = _removalOwners.value - profile.id
                }
                bootstrapped = true
                _owners.value = epochs.mapValues { (id, generation) -> WorkOwner(id, generation) }
                _ready.value = true
            }
        }
    }

    /**
     * The compatibility writer for profile/proxy edits and fixtures. The change runs under the
     * gate; only a profile the authority has not seen gets a generation and the credentials the
     * same write persisted. An existing generation is never rebound here: credentials change only
     * through the auth transitions below.
     */
    suspend fun updateProfiles(store: ServerStore, change: suspend () -> Unit) = run {
        beforeProfileAdmission?.invoke()
        gate.withLock {
        bootstrapLocked(store)
        change()
        val state = store.load()
        beforeProfileMint?.invoke(state)
        synchronized(guard) {
            state.servers.forEach { profile ->
                if (epochs.containsKey(profile.id)) return@forEach
                val generation = ++next
                epochs[profile.id] = generation
                bindings[profile.id] = Bound(generation, state.tokens[profile.id])
                _incarnations.value = _incarnations.value + (profile.id to generation)
                removals.remove(profile.id)
                _removalOwners.value = _removalOwners.value - profile.id
                _owners.value = _owners.value + (profile.id to WorkOwner(profile.id, generation))
            }
        }
    }
    }

    /**
     * Commits a native sign-in that captured [expected] before the browser work started. The
     * storage write and the epoch/binding publication form one admitted, non-cancellable region:
     * a commit admitted here cannot be left on disk without its authority, and cancellation
     * before admission writes nothing.
     */
    suspend fun commitNative(store: ServerStore, profileId: String, expected: Long, tokens: Tokens): NativeResult = run {
        beforeNativeAdmission?.invoke()
        gate.withLock {
            bootstrapLocked(store)
            if (!holds(profileId, expected)) return@withLock NativeResult.Rejected
            withContext(NonCancellable) {
                try {
                    store.writeCredentialsNow(profileId, tokens)
                    NativeResult.Applied(publishFresh(profileId, tokens))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The write may have reached the disk before it reported an error. The disk,
                    // not the exception, decides: adopting bytes the store really holds is not a
                    // guess, and a stale owner is not published over a newer authorization.
                    if (diskMatches(store, profileId, tokens) == true && holds(profileId, expected)) {
                        NativeResult.Applied(publishFresh(profileId, tokens))
                    } else {
                        NativeResult.Failed(e)
                    }
                }
            }
        }
    }

    /**
     * Saves tokens produced by a refresh of [at]. The owner does not change; a stale refresh
     * writes nothing, and a storage failure is reported instead of being taken for a rejection.
     */
    suspend fun commitRefresh(store: ServerStore, profileId: String, at: Long, tokens: Tokens?): NativeResult =
        gate.withLock {
            bootstrapLocked(store)
            if (!holds(profileId, at)) return@withLock NativeResult.Rejected
            withContext(NonCancellable) {
                try {
                    store.writeCredentialsNow(profileId, tokens)
                    synchronized(guard) {
                        if (epochs[profileId] == at) bindings[profileId] = Bound(at, tokens)
                    }
                    NativeResult.Applied(WorkOwner(profileId, at))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (diskMatches(store, profileId, tokens) == true && holds(profileId, at)) {
                        synchronized(guard) {
                            if (epochs[profileId] == at) bindings[profileId] = Bound(at, tokens)
                        }
                        NativeResult.Applied(WorkOwner(profileId, at))
                    } else {
                        NativeResult.Failed(e)
                    }
                }
            }
        }

    /**
     * The compatibility entry point for an authorization that did not come through a native
     * session. It is still a real authorization: the bytes get a fresh generation, so an API that
     * captured the previous owner cannot send them. The profile must exist; a removed profile is
     * rejected, not resurrected.
     */
    suspend fun authorizeExternal(store: ServerStore, profileId: String, tokens: Tokens): NativeResult =
        gate.withLock {
            bootstrapLocked(store)
            val state = store.load()
            if (state.servers.none { it.id == profileId }) return@withLock NativeResult.Rejected
            withContext(NonCancellable) {
                try {
                    store.writeCredentialsNow(profileId, tokens)
                    NativeResult.Applied(publishFresh(profileId, tokens))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (diskMatches(store, profileId, tokens) == true) {
                        NativeResult.Applied(publishFresh(profileId, tokens))
                    } else {
                        NativeResult.Failed(e)
                    }
                }
            }
        }

    /**
     * Signs out under the same gate as every other auth transition: the durable clear and the
     * retirement are one admitted region. A native commit that was already admitted finishes
     * first and is then superseded; an attempt admitted after the retirement finds its expected
     * generation gone and is [NativeResult.Rejected].
     *
     * A clear that fails before it reached the disk changes nothing and is reported as
     * [NativeResult.Failed]. If the disk shows the clear anyway, the retirement is published.
     * Cleanup that follows belongs to the returned generation and must check it before running.
     */
    suspend fun retireAndClear(store: ServerStore, profileId: String, expected: WorkOwner = owner(profileId)): NativeResult =
        gate.withLock {
            bootstrapLocked(store)
            if (expected.profileId != profileId || !holds(profileId, expected.generation)) return@withLock NativeResult.Rejected
            val generation = synchronized(guard) { ++next }
            withContext(NonCancellable) {
                try {
                    store.writeCredentialsNow(profileId, null)
                    NativeResult.Applied(publishRetired(profileId, generation))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    when (diskMatches(store, profileId, null)) {
                        true -> NativeResult.Applied(publishRetired(profileId, generation))
                        // The old bytes are still on disk: the clear did not happen, so the owner
                        // and its credentials stay and the failure is reported.
                        false -> NativeResult.Failed(e)
                        // The store cannot be read. Keep the previous authority rather than
                        // restoring or inventing credentials from an exception.
                        null -> NativeResult.Failed(e)
                    }
                }
            }
        }

    /**
     * Removes the profile and its credentials under the gate. A failure before the disk changed
     * leaves the profile and its owner; a removal the disk shows is published even if the edit
     * reported an error. Once removed, the profile has no generation, so a retained session
     * cannot cache, refresh or sign in against it.
     */
    suspend fun removeProfile(store: ServerStore, profileId: String, expected: WorkOwner = owner(profileId)): NativeResult =
        gate.withLock {
            bootstrapLocked(store)
            if (expected.profileId != profileId || !holds(profileId, expected.generation)) return@withLock NativeResult.Rejected
            withContext(NonCancellable) {
                try {
                    store.deleteProfileNow(profileId)
                    NativeResult.Applied(removePublished(profileId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (diskLacksProfile(store, profileId) == true) {
                        NativeResult.Applied(removePublished(profileId))
                    } else {
                        NativeResult.Failed(e)
                    }
                }
            }
        }

    private fun publishFresh(profileId: String, tokens: Tokens): WorkOwner = synchronized(guard) {
        val generation = ++next
        val owner = WorkOwner(profileId, generation)
        epochs[profileId] = generation
        bindings[profileId] = Bound(generation, tokens)
        _owners.value = _owners.value + (profileId to owner)
        owner
    }

    private fun publishRetired(profileId: String, generation: Long): WorkOwner = synchronized(guard) {
        val owner = WorkOwner(profileId, generation)
        epochs[profileId] = generation
        bindings[profileId] = Bound(generation, null)
        _owners.value = _owners.value + (profileId to owner)
        owner
    }

    private fun removePublished(profileId: String): WorkOwner = synchronized(guard) {
        val marker = ++next
        epochs.remove(profileId)
        bindings.remove(profileId)
        removals[profileId] = marker
        _removalOwners.value = _removalOwners.value + (profileId to WorkOwner(profileId, marker))
        _owners.value = _owners.value - profileId
        WorkOwner(profileId, marker)
    }

    /**
     * The store's own answer after an exception. True means the bytes the attempt wanted are on
     * disk, false means they are not, null means the store could not be read.
     */
    private suspend fun diskMatches(store: ServerStore, profileId: String, tokens: Tokens?): Boolean? =
        when (val persisted = store.loadDurable()) {
            is Persisted.Known -> persisted.state.tokens[profileId] == tokens
            // Unreadable is not absence: a clear that cannot be verified is not a committed clear.
            Persisted.Unreadable -> null
        }

    private suspend fun diskLacksProfile(store: ServerStore, profileId: String): Boolean? =
        when (val persisted = store.loadDurable()) {
            is Persisted.Known -> persisted.state.servers.none { it.id == profileId }
            Persisted.Unreadable -> null
        }

    companion object {
        private val authorities = ConcurrentHashMap<Any, ProfileLifetime>()

        /** The one authority of [backing], the actual DataStore instance every wrapper shares. */
        internal fun forStore(backing: Any): ProfileLifetime =
            authorities.computeIfAbsent(backing) { ProfileLifetime(it) }
    }
}
