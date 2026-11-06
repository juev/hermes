package org.evsyukov.hermes.chat

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.evsyukov.hermes.data.server.ProfileLifetime
import org.evsyukov.hermes.data.server.WorkOwner
import java.util.WeakHashMap

private val Context.observationStore by preferencesDataStore(name = "local_observations")

/** A root's storage-clear version, not an additional credential authority. */
internal data class ObservationTicket(val profile: String, val lineage: String, val session: String, val version: Long)

/** Durable local observations, independent of the evictable HTTP cache. No TTL or size eviction. */
class LocalObservationStore(context: Context, private val authority: ProfileLifetime, backing: DataStore<Preferences>? = null) {
    private val data = backing ?: context.observationStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private class Memory {
        var version = 0L
        var readFailed = false
        val clears = mutableMapOf<Triple<String, String?, String?>, Long>()
        val pending = linkedMapOf<String, LocalObservation>()
        val stagedVersion = mutableMapOf<String, Long>()
        val unsaved = mutableSetOf<String>()
        val failureTargets = mutableMapOf<String, Triple<String, String, String>>()
        val failures = mutableMapOf<String, Long>()
        val presentationFailures = mutableSetOf<String>()
        val durableRevision = mutableMapOf<String, Long>()
    }
    private val memory = synchronized(memories) { memories.getOrPut(data) { Memory() } }

    internal fun capture(profile: String, lineage: String, session: String): ObservationTicket = synchronized(memory) {
        ObservationTicket(profile, lineage, session, memory.version)
    }
    internal fun valid(ticket: ObservationTicket, target: String): Boolean = synchronized(memory) {
        memory.clears.none { (key, version) -> version > ticket.version && key.first == ticket.profile &&
            (key.second == null || key.second == ticket.lineage) && (key.third == null || key.third == ticket.session || key.third == target) }
    }
    internal fun stage(owner: WorkOwner, ticket: ObservationTicket, row: LocalObservation) {
        if (row.profile != owner.profileId) return
        authority.publish(owner) { synchronized(memory) {
            if (valid(ticket, row.session)) {
                val old = memory.pending[row.id]
                if ((memory.durableRevision[row.id] ?: -1) < row.revision && (old == null || old.revision < row.revision)) { memory.pending[row.id] = row; memory.stagedVersion[row.id] = ticket.version; memory.unsaved += row.id }
            }
        } }
    }
    internal fun pending(owner: WorkOwner, lineage: String, session: String): List<LocalObservation> =
        authority.publish(owner) { synchronized(memory) { memory.pending.values.filter { it.profile == owner.profileId && it.lineage == lineage && it.session == session } } } ?: emptyList()

    internal suspend fun put(owner: WorkOwner, ticket: ObservationTicket, observation: LocalObservation) {
        if (owner.profileId != observation.profile || ticket.profile != owner.profileId || ticket.lineage != observation.lineage) return
        authority.withOwner(owner) {
            if (!valid(ticket, observation.session)) return@withOwner
            synchronized(memory) {
                val prior = memory.pending[observation.id]
                if (prior == null || prior.revision < observation.revision) { memory.pending[observation.id] = observation; memory.stagedVersion[observation.id] = ticket.version; memory.unsaved += observation.id }
            }
            var committed: LocalObservation? = null
            try { data.edit { prefs ->
                if (!valid(ticket, observation.session)) return@edit
                val prior = prefs[key(observation.id)]?.let(::decode)
                val newest = if (prior != null && prior.revision > observation.revision) prior else observation
                val saved = if (prior == null) newest.copy(collapsed = prefs[reviewed(observation.id)]) else newest.copy(
                    keys = (prior.keys + observation.keys).distinct(),
                    files = (prior.files + observation.files).distinct(),
                    bindings = (prior.bindings + observation.bindings).distinct(),
                    proofs = (prior.proofs + observation.proofs).distinct(),
                    frames = (prior.frames + observation.frames).distinct(),
                    items = if (prior.completed && !observation.completed) prior.items else newest.items,
                    completed = prior.completed || observation.completed,
                    error = newest.error ?: prior.error,
                    collapsed = prefs[reviewed(observation.id)],
                )
                prefs[key(observation.id)] = json.encodeToString(saved)
                committed = saved
            } } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) { synchronized(memory) { memory.failureTargets[observation.id] = Triple(observation.profile, observation.lineage, observation.session); memory.failures[observation.id] = maxOf(memory.failures[observation.id] ?: -1, observation.revision) }; throw error }
            committed?.let { saved ->
                synchronized(memory) {
                    memory.durableRevision[observation.id] = maxOf(memory.durableRevision[observation.id] ?: -1, saved.revision)
                    if ((memory.pending[observation.id]?.revision ?: -1) <= saved.revision) { memory.pending.remove(observation.id); memory.unsaved.remove(observation.id) }
                    if ((memory.failures[observation.id] ?: -1) <= saved.revision) memory.failures.remove(observation.id)
                    if (observation.id !in memory.failures && observation.id !in memory.presentationFailures) memory.failureTargets.remove(observation.id)
                }
            }
        }
    }
    internal suspend fun load(owner: WorkOwner, lineage: String, session: String): List<LocalObservation> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        authority.withOwner(owner) {
            try {
                val prefs = data.data.first()
                val stored = prefs.asMap().filterKeys { it.name.startsWith(PREFIX) }.values.map { decode(it as String) }
                val merged = (stored + synchronized(memory) { memory.pending.values.toList() }).groupBy { it.id }.map { (_, versions) -> versions.maxBy { it.revision } }
                val result = merged.filter { it.profile == owner.profileId && it.lineage == lineage && it.session == session }
                    .map { it.copy(collapsed = prefs[reviewed(it.id)]) }.sortedWith(compareBy<LocalObservation> { it.created }.thenBy { it.id })
                synchronized(memory) { memory.readFailed = false }
                result
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) { synchronized(memory) { memory.readFailed = true }; throw error }
        } ?: emptyList()
    }

    internal fun hasUnsaved(profile: String, lineage: String, session: String): Boolean = synchronized(memory) {
        memory.unsaved.any { id -> memory.pending[id]?.let { it.profile == profile && it.lineage == lineage && it.session == session } == true }
    }
    internal suspend fun presentation(owner: WorkOwner, ticket: ObservationTicket, id: String, collapsed: Boolean) {
        authority.withOwner(owner) {
            var target: LocalObservation? = null
            try { data.edit { prefs ->
                val row = prefs[key(id)]?.let(::decode) ?: synchronized(memory) { memory.pending[id] } ?: return@edit
                if (row.profile != owner.profileId || row.lineage != ticket.lineage || !valid(ticket, row.session)) return@edit
                target = row
                prefs[reviewed(id)] = collapsed
            } } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) { synchronized(memory) { target?.let { memory.pending.putIfAbsent(id, it) }; memory.failureTargets[id] = Triple(owner.profileId, ticket.lineage, ticket.session); memory.presentationFailures += id }; throw error }
            target?.let { row -> synchronized(memory) { memory.presentationFailures.remove(id); if (id !in memory.failures) memory.failureTargets.remove(id); memory.pending[id]?.let { current -> memory.pending[id] = current.copy(collapsed = collapsed) } } }
        }
    }

    /** Caller already holds the existing auth->local-store admission gate. */
    internal suspend fun clearAdmitted(profile: String, lineage: String? = null, session: String? = null) {
        val clearVersion = synchronized(memory) { (++memory.version).also { memory.clears[Triple(profile, lineage, session)] = it } }
        fun matches(row: LocalObservation) = row.profile == profile && (lineage == null || row.lineage == lineage) && (session == null || row.session == session)
        var removed = emptyMap<String, Long>()
        data.edit { prefs ->
            val rows = prefs.asMap().filterKeys { it.name.startsWith(PREFIX) }.values.map { decode(it as String) }
            val pending = synchronized(memory) { memory.pending.values.filter { (memory.stagedVersion[it.id] ?: -1) < clearVersion } }
            removed = (rows + pending).filter(::matches).groupBy { it.id }.mapValues { (_, versions) -> versions.maxOf { it.revision } }
            removed.keys.forEach { prefs.remove(key(it)); prefs.remove(reviewed(it)) }
        }
        synchronized(memory) { removed.forEach { (id, revision) ->
            if ((memory.stagedVersion[id] ?: -1) < clearVersion && (memory.pending[id]?.revision ?: -1) <= revision) { memory.stagedVersion.remove(id); memory.pending.remove(id); memory.unsaved.remove(id); memory.failures.remove(id); memory.presentationFailures.remove(id); memory.failureTargets.remove(id); memory.durableRevision.remove(id) }
        } }
    }
    internal fun hasFailures(profile: String, lineage: String, session: String): Boolean = synchronized(memory) {
        memory.readFailed || (memory.failures.keys + memory.presentationFailures).any { id ->
            val target = memory.pending[id]?.let { Triple(it.profile, it.lineage, it.session) } ?: memory.failureTargets[id]
            target == Triple(profile, lineage, session)
        }
    }

    private fun decode(value: String): LocalObservation = try {
        json.decodeFromString<LocalObservation>(value).also { require(it.schema == 1) }
    } catch (_: Exception) { throw java.io.IOException("Local observation data could not be read safely") }
    private fun key(id: String) = stringPreferencesKey(PREFIX + id)
    private fun reviewed(id: String) = booleanPreferencesKey("reviewed:$id")
    companion object {
        private const val PREFIX = "observation:"
        private val memories = WeakHashMap<DataStore<Preferences>, Memory>()
    }
}
