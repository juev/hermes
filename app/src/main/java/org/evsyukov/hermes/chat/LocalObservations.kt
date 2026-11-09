package org.evsyukov.hermes.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.evsyukov.hermes.data.remote.StoredMessage
import java.util.UUID

@Serializable
data class ReceiptOrigin(val profile: String, val generation: Long, val live: String, val transport: Long, val remoteProfile: String?)
@Serializable
data class ObservationFile(val name: String, val mime: String?, val base64: String, val userKey: String = "")
@Serializable
data class ObservedFrame(val origin: ReceiptOrigin, val type: String, val payload: JsonObject)
@Serializable
data class SubmitBinding(val key: String, val origin: ReceiptOrigin, val sent: String, val userRow: Long?, val admissible: Boolean, val admissionId: String? = null)
@Serializable
data class CompletionProof(val origin: ReceiptOrigin, val userRow: Long, val finalRow: Long?, val rows: List<Long>, val text: String?, val valid: Boolean, val admissionId: String? = null)
@Serializable
data class LocalObservation(
    val id: String = UUID.randomUUID().toString(),
    val schema: Int = 1,
    val revision: Long = 0,
    val created: Long = System.currentTimeMillis(),
    val profile: String,
    val lineage: String,
    val session: String,
    val generation: Long,
    val keys: List<String> = emptyList(),
    val anchor: String? = null,
    val items: List<ChatItem> = emptyList(),
    val files: List<ObservationFile> = emptyList(),
    val frames: List<ObservedFrame> = emptyList(),
    val bindings: List<SubmitBinding> = emptyList(),
    val proofs: List<CompletionProof> = emptyList(),
    val completed: Boolean = false,
    val error: String? = null,
    val reconciled: Boolean = false,
    val collapsed: Boolean? = null,
)

/** Optional wire evidence. No turn is selected by text, time, UI key or receipt arrival order. */
internal class ObservationBook {
    private val groups = mutableListOf<LocalObservation>()
    private val pending = mutableListOf<CompletionProof>()
    var active: String? = null
        private set

    @Synchronized fun restore(saved: List<LocalObservation>, keep: Set<String> = emptySet()) {
        val ids = saved.mapTo(HashSet()) { it.id }
        groups.removeAll { it.id !in ids && it.id !in keep }
        if (groups.none { it.id == active }) active = null
        val incoming = saved.filter { old -> groups.none { it.id == old.id } }
        groups.addAll(0, incoming)
        saved.forEach { old -> val index = groups.indexOfFirst { it.id == old.id }; if (old.id != active && index >= 0 && groups[index].revision <= old.revision) groups[index] = old }
    }
    @Synchronized fun snapshot(): List<LocalObservation> = groups.toList()
    @Synchronized fun begin(profile: String, lineage: String, session: String, generation: Long, user: ChatItem.User, files: List<ObservationFile>): String {
        var group = groups.firstOrNull { it.id == active }
        if (group == null || group.completed || group.generation != generation || group.session != session) {
            group = LocalObservation(profile = profile, lineage = lineage, session = session, generation = generation)
            groups += group
            active = group.id
        }
        replace(group.copy(keys = group.keys + user.key, items = group.items + user, files = group.files + files))
        return group.id
    }
    /** A newly observed continuation has no inferred submit identity. It stays a separate copy. */
    @Synchronized fun continuation(profile: String, lineage: String, session: String, generation: Long, tail: ChatItem.Assistant): String {
        val group = LocalObservation(profile = profile, lineage = lineage, session = session, generation = generation, anchor = tail.key, items = listOf(tail))
        groups += group
        active = group.id
        return group.id
    }
    @Synchronized fun bind(id: String, key: String, origin: ReceiptOrigin, sent: String, result: JsonElement) {
        val group = groups.firstOrNull { it.id == id } ?: return
        val root = result as? JsonObject
        val row = positive(root?.get("user_row_id"))
        val status = (root?.get("status") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val native = (root?.get("admission_id") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?.takeIf { status in setOf("queued", "started", "terminal") &&
                (root["ref"] as? JsonObject)?.get("session_id") == JsonPrimitive(origin.live) && positive(root["authority_epoch"]) != null }
        val binding = SubmitBinding(key, origin, sent, row, row != null && status == "streaming" || native != null, native)
        replace(group.copy(bindings = group.bindings + binding, proofs = (group.proofs + pending).distinct()))
    }
    @Synchronized fun semantic(origin: ReceiptOrigin, type: String, payload: JsonObject) {
        val group = groups.firstOrNull { it.id == active && !it.completed && it.profile == origin.profile && it.generation == origin.generation } ?: return
        replace(group.copy(frames = group.frames + ObservedFrame(origin, type, payload)))
    }
    @Synchronized fun delivery(key: String, delivery: Delivery) {
        groups.toList().filter { key in it.keys }.forEach { group -> replace(group.copy(items = group.items.map { item -> if (item is ChatItem.User && item.key == key) item.copy(delivery = delivery) else item })) }
    }
    @Synchronized fun failed(id: String, working: List<ChatItem>, error: String) {
        val group = groups.firstOrNull { it.id == id } ?: return
        val start = working.indexOfFirst { it.key == (group.anchor ?: group.keys.firstOrNull()) }
        replace(group.copy(items = if (start >= 0) working.drop(start) else group.items, completed = true, error = error))
    }
    @Synchronized fun sealActive() { groups.firstOrNull { it.id == active }?.let { replace(it.copy(completed = true)) }; active = null }
    @Synchronized fun terminal(origin: ReceiptOrigin, payload: JsonObject, working: List<ChatItem>, error: String?, admissionId: String? = null) {
        val receipt = parseProof(origin, payload)?.copy(admissionId = admissionId?.takeIf { it.isNotBlank() })
        if (receipt != null) pending += receipt
        groups.toList().forEach { group ->
            if (group.profile != origin.profile || group.generation != origin.generation) return@forEach
            val start = working.indexOfFirst { it.key == (group.anchor ?: group.keys.firstOrNull()) }
            // This is an observation of the actual local transcript, not attribution of a foreign
            // completion to a send. Only exact ACK/receipt provenance can later retire this copy.
            val items = if (start >= 0 && (group.id == active || !group.completed)) working.drop(start) else group.items
            replace(group.copy(items = items, completed = true, error = if (group.id == active || !group.completed) error else group.error, proofs = (group.proofs + pending).distinct()))
        }
    }
    @Synchronized fun refreshItems(working: List<ChatItem>) {
        groups.toList().filter { !it.completed }.forEach { group ->
            val start = working.indexOfFirst { it.key == (group.anchor ?: group.keys.firstOrNull()) }
            if (start >= 0) replace(group.copy(items = working.drop(start)))
        }
    }
    @Synchronized fun assignStoredSession(session: String, generation: Long) {
        groups.toList().filter { it.session.isEmpty() && it.generation == generation }.forEach { replace(it.copy(session = session)) }
    }
    @Synchronized fun moveSession(id: String, session: String) {
        groups.firstOrNull { it.id == id }?.let { replace(it.copy(session = session)) }
    }
    @Synchronized fun collapse(id: String, collapsed: Boolean) { groups.firstOrNull { it.id == id }?.let { replace(it.copy(collapsed = collapsed)) } }
    @Synchronized fun reconcile(rows: List<StoredMessage>, session: String?, remoteProfile: String?, generation: Long): List<LocalObservation> {
        groups.toList().forEach { group -> replace(group.copy(reconciled = group.generation == generation && proven(group, rows, session, remoteProfile))) }
        return groups.toList()
    }
    private fun replace(group: LocalObservation) {
        val index = groups.indexOfFirst { it.id == group.id }
        if (index >= 0 && group != groups[index]) groups[index] = group.copy(revision = groups[index].revision + 1)
    }

    private fun proven(group: LocalObservation, rows: List<StoredMessage>, session: String?, remoteProfile: String?): Boolean {
        if (!group.completed || group.error != null || group.keys.isEmpty() || session != group.session || remoteProfile.isNullOrBlank()) return false
        if (rows.any { it.rowId <= 0 } || rows.map { it.rowId }.distinct().size != rows.size) return false
        val byId = rows.associateBy { it.rowId }
        val addressed = mutableSetOf<Long>()
        val bound = mutableSetOf<Long>()
        if (group.items.filterIsInstance<ChatItem.User>().any { it.key !in group.keys }) return false
        for (key in group.keys) {
            val bindings = group.bindings.filter { it.key == key }.distinct()
            if (bindings.size != 1) return false
            val binding = bindings.single()
            if (!binding.admissible || binding.origin.profile != group.profile || binding.origin.generation != group.generation || binding.origin.remoteProfile != remoteProfile) return false
            val candidates = group.proofs.filter { proof -> proof.origin == binding.origin &&
                if (binding.admissionId != null) proof.admissionId == binding.admissionId && (binding.userRow == null || binding.userRow == proof.userRow)
                else proof.userRow == binding.userRow }.distinct()
            if (candidates.size != 1) return false
            val proof = candidates.single()
            val user = binding.userRow ?: proof.userRow
            if (!bound.add(user)) return false
            if (!proof.valid || proof.rows.any { it !in byId || !addressed.add(it) }) return false
            val raw = proof.rows.map { byId.getValue(it) }
            if (raw.any { it.hidden || it.role !in setOf("user", "assistant", "tool") }) return false
            if (raw.filter { it.role == "user" }.map { it.rowId } != listOf(user)) return false
            if (byId[user]?.text != binding.sent || byId[proof.finalRow]?.role != "assistant" || byId[proof.finalRow]?.text != proof.text) return false
            val calls = raw.flatMap { it.toolCalls }.map { it.id }.toSet()
            if (raw.filter { it.role == "tool" }.any { it.toolCallId !in calls }) return false
        }
        // Complete raw membership precedes folding. All observed tools/reasoning must also be
        // accountable in those addressed rows; optional metadata never supplies identity.
        val raw = addressed.map { byId.getValue(it) }
        for (part in group.items.filterIsInstance<ChatItem.Assistant>().flatMap { it.parts }) when (part) {
            is Part.Tool -> if (raw.none { row -> row.toolCalls.any { it.id == part.run.id } } || (part.run.done && raw.none { it.role == "tool" && it.toolCallId == part.run.id })) return false
            is Part.Reasoning -> if (raw.none { it.reasoning?.contains(part.text) == true }) return false
            is Part.Text -> Unit // Row identity and final body, not text similarity, authorize retirement.
        }
        return true
    }

    companion object {
        private fun positive(value: JsonElement?): Long? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }
        fun parseProof(origin: ReceiptOrigin, payload: JsonObject): CompletionProof? {
            val obj = payload["persisted_turn"] as? JsonObject ?: return null
            val user = positive(obj["user_row_id"]) ?: return null
            val final = positive(obj["final_assistant_row_id"])
            val array = obj["row_ids"] as? JsonArray
            val rows = array?.mapNotNull(::positive).orEmpty()
            val users = obj["user_row_ids"] as? JsonArray
            val valid = (obj["complete"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true &&
                array != null && rows.size == array.size && rows.isNotEmpty() && rows.distinct().size == rows.size && rows.zipWithNext().all { it.first < it.second } &&
                final != null && rows.first() == user && rows.last() == final && (!obj.containsKey("user_row_ids") || users != null && users.mapNotNull(::positive) == listOf(user) && users.size == 1) &&
                (!payload.containsKey("partial") || (payload["partial"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == false) && (payload["status"] as? JsonPrimitive)?.contentOrNull == "complete"
            return CompletionProof(origin, user, final, rows, (payload["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content, valid)
        }
    }
}
