package org.evsyukov.hermes.chat

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.evsyukov.hermes.data.remote.StoredMessage
import org.evsyukov.hermes.data.remote.ToolCallInfo
import org.junit.Test

/** Receipt decisions use raw row identities, before transcript folding. */
class ObservationEvidenceTest {
    private val origin = ReceiptOrigin("p", 2, "live", 3, "remote")
    private val user = ChatItem.User("local", "same", emptyList())
    private val answer = ChatItem.Assistant("live-answer", listOf(Part.Text("done")), false)
    private fun row(id: Long, role: String, text: String, calls: List<ToolCallInfo> = emptyList(), tool: String? = null) = StoredMessage(id, role, text, null, calls, null, tool, 1.0, false, emptyList())
    private val rows get() = listOf(row(10, "user", "same"), row(11, "assistant", "done"))
    private fun ack(id: Long = 10, status: String = "streaming") = buildJsonObject { put("user_row_id", id); put("status", status) }
    private fun completion(ids: List<Long> = listOf(10, 11), user: Long = 10, final: Long = 11, complete: Boolean = true) = buildJsonObject {
        put("text", "done"); put("status", "complete")
        putJsonObject("persisted_turn") { put("complete", complete); put("user_row_id", user); put("final_assistant_row_id", final); putJsonArray("row_ids") { ids.forEach { add(it) } } }
    }
    private fun book(binding: JsonElement? = ack(), receipt: JsonObject = completion(), eventOrigin: ReceiptOrigin = origin, first: Boolean = false, extra: JsonObject? = null): ObservationBook {
        val b = ObservationBook()
        val id = b.begin("p", "https://synthetic/", "session", 2, user, emptyList())
        if (first) b.terminal(eventOrigin, receipt, listOf(user, answer), null)
        if (binding != null) b.bind(id, user.key, origin, "same", binding)
        if (!first) b.terminal(eventOrigin, receipt, listOf(user, answer), null)
        if (extra != null) b.terminal(eventOrigin, extra, listOf(user, answer), null)
        return b
    }
    private fun matched(b: ObservationBook, raw: List<StoredMessage> = rows, session: String = "session", profile: String = "remote") = b.reconcile(raw, session, profile, 2).single().reconciled
    @Test fun `a canonical queued admission retires only through its exact completion receipt and raw rows`() {
        fun admitted(admission: String): ObservationBook {
            val b = ObservationBook()
            val id = b.begin("p", "https://synthetic/", "session", 2, user, emptyList())
            b.bind(id, user.key, origin, "same", buildJsonObject {
                put("status", "queued"); put("admission_id", "native-a1"); put("authority_epoch", 1)
                putJsonObject("ref") { put("session_id", "live") }
            })
            b.terminal(origin, completion(), listOf(user, answer), null, admissionId = admission)
            return b
        }
        assertThat(matched(admitted("native-a1"))).isTrue()
        assertThat(matched(admitted("native-a2"))).isFalse()
        assertThat(matched(admitted("native-a1"), rows.take(1))).isFalse()
        assertThat(matched(book(binding = buildJsonObject { put("status", "queued"); put("admission_id", "native-a1") }))).isFalse()
    }

    @Test fun `complete ACK first proof matches only its addressed rows`() { assertThat(matched(book())).isTrue() }
    @Test fun `completion before ACK uses exact binding rather than event order`() { assertThat(matched(book(first = true))).isTrue() }
    @Test fun `a predating page cannot retire a completed local answer`() { assertThat(matched(book(), rows.take(1))).isFalse() }
    @Test fun `missing middle raw tool row is not rescued by folded answer`() {
        val b = book(receipt = completion(listOf(10, 12, 13, 14), final = 14))
        val raw = listOf(row(10, "user", "same"), row(12, "assistant", "", listOf(ToolCallInfo("t", "tool", "{}"))), row(13, "tool", "result", tool = "t"), row(14, "assistant", "done"))
        assertThat(matched(b, raw.filterNot { it.rowId == 13L })).isFalse()
        assertThat(matched(b, raw)).isTrue()
    }
    @Test fun `absent ACK leaves full local content unmatched`() { val b = book(binding = null); assertThat(matched(b)).isFalse(); assertThat(b.snapshot().single().items).containsExactly(user, answer).inOrder() }
    @Test fun `absent receipt cannot identify an answer by equal text`() { assertThat(matched(book(receipt = buildJsonObject { put("text", "done") }))).isFalse() }
    @Test fun `partial receipt is not retirement authority`() { assertThat(matched(book(receipt = completion(complete = false)))).isFalse() }
    @Test fun `wrong owner receipt is rejected while current proof works`() { assertThat(matched(book(eventOrigin = origin.copy(profile = "other")))).isFalse(); assertThat(matched(book())).isTrue() }
    @Test fun `wrong generation receipt cannot correlate renewed authorization`() { assertThat(matched(book(eventOrigin = origin.copy(generation = 4)))).isFalse() }
    @Test fun `wrong session page cannot correlate identical row IDs`() { assertThat(matched(book(), session = "other")).isFalse() }
    @Test fun `wrong remote profile page cannot correlate identical row IDs`() { assertThat(matched(book(), profile = "other")).isFalse() }
    @Test fun `wrong user ID receipt does not bind by nearest event`() { assertThat(matched(book(receipt = completion(user = 9)))).isFalse() }
    @Test fun `conflicting receipts prevent retirement`() { assertThat(matched(book(extra = completion(listOf(10, 11, 12), final = 12)))).isFalse() }
    @Test fun `malformed duplicate receipt rows prevent retirement`() { assertThat(matched(book(receipt = completion(listOf(10, 10, 11))))).isFalse() }
    @Test fun `queued ACK does not authorize row correlation`() { assertThat(matched(book(binding = ack(status = "queued")))).isFalse() }
    @Test fun `redirected ACK does not authorize row correlation`() { assertThat(matched(book(binding = ack(status = "redirected")))).isFalse() }
    @Test fun `string row IDs are not positive numeric ACKs`() { assertThat(matched(book(binding = buildJsonObject { put("user_row_id", "10") }))).isFalse() }
    @Test fun `inconsistent role cannot stand in for addressed user row`() { assertThat(matched(book(), listOf(row(10, "assistant", "same"), rows.last()))).isFalse() }
    @Test fun `foreign tool lineage prevents retirement`() {
        val raw = listOf(rows.first(), row(12, "tool", "result", tool = "foreign"), row(13, "assistant", "done"))
        assertThat(matched(book(receipt = completion(listOf(10, 12, 13), final = 13)), raw)).isFalse()
    }
    @Test fun `distinct identical text turns require distinct ACK identities`() {
        val b = book()
        val second = user.copy(key = "local-second")
        val id = b.begin("p", "https://synthetic/", "session", 2, second, emptyList())
        b.bind(id, second.key, origin, "same", ack(20))
        b.terminal(origin, completion(listOf(20, 21), user = 20, final = 21), listOf(second, answer.copy(key = "live-second")), null)
        val states = b.reconcile(rows + listOf(row(20, "user", "same"), row(21, "assistant", "done")), "session", "remote", 2)
        assertThat(states).hasSize(2)
        assertThat(states.all { it.reconciled }).isTrue()
        assertThat(states.flatMap { it.items }.filterIsInstance<ChatItem.Assistant>()).hasSize(2)
    }
    @Test fun `renewed authorization cannot retire an old generation observation from reused row IDs`() {
        val b = book()
        assertThat(b.reconcile(rows, "session", "remote", 5).single().reconciled).isFalse()
        assertThat(b.reconcile(rows, "session", "remote", 2).single().reconciled).isTrue()
    }
    @Test fun `missing addressed intermediate assistant row cannot be inferred from final text`() {
        val b = book(receipt = completion(listOf(10, 12, 13), final = 13))
        val raw = listOf(rows.first(), row(13, "assistant", "done"))
        assertThat(matched(b, raw)).isFalse()
        assertThat(matched(b, raw + row(12, "assistant", "prelude"))).isTrue()
    }
    @Test fun `hidden raw rows cannot retire a visible local reply`() { assertThat(matched(book(), rows.map { it.copy(hidden = true) })).isFalse() }
    @Test fun `duplicate raw row identities are indeterminate`() { assertThat(matched(book(), rows + rows.first())).isFalse() }
    @Test fun `final body inconsistency keeps local content instead of guessing`() { assertThat(matched(book(), listOf(rows.first(), rows.last().copy(text = "different")))).isFalse() }
    @Test fun `conflicting ACKs for one local send prevent retirement`() {
        val b = book(); val group = b.snapshot().single()
        b.bind(group.id, user.key, origin, "same", ack(20))
        assertThat(matched(b)).isFalse()
    }
    @Test fun `wrong live session and transport do not correlate an otherwise equal receipt`() {
        assertThat(matched(book(eventOrigin = origin.copy(live = "other")))).isFalse()
        assertThat(matched(book(eventOrigin = origin.copy(transport = 6)))).isFalse()
        assertThat(matched(book())).isTrue()
    }
    @Test fun `malformed optional user boundaries are not equivalent to an absent field`() {
        val original = completion()
        val malformed = JsonObject(original + ("persisted_turn" to JsonObject(original.getValue("persisted_turn").jsonObject + ("user_row_ids" to JsonPrimitive("10")))))
        assertThat(matched(book(receipt = malformed))).isFalse()
    }
    @Test fun `malformed partial flag cannot authorize dropping a local observation`() {
        val malformed = JsonObject(completion() + ("partial" to buildJsonObject { put("invalid", true) }))
        assertThat(matched(book(receipt = malformed))).isFalse()
    }
    @Test fun `an error submit result cannot bind a successful local user row`() { assertThat(matched(book(binding = ack(status = "error")))).isFalse() }
    @Test fun `a numeric submit status is malformed rather than a streaming ACK`() {
        assertThat(matched(book(binding = buildJsonObject { put("user_row_id", 10); put("status", 1) }))).isFalse()
    }
    @Test fun `collapse and reopen retain every attachment tool frame and outcome`() {
        val b = ObservationBook()
        val file = ObservationFile("note.txt", "text/plain", "AQ==", "local")
        val id = b.begin("p", "url", "session", 2, user.copy(text = "", attachments = listOf(Attachment("ref", "note.txt", false))), listOf(file))
        val payload = buildJsonObject { put("result", "full result ".repeat(300)) }
        b.semantic(origin, "tool.complete", payload)
        val tool = Part.Tool(ToolRun("t", "tool", "", "{}", true))
        b.terminal(origin, buildJsonObject { put("text", "done") }, listOf(user.copy(text = ""), answer.copy(parts = listOf(Part.Reasoning("reason"), tool, Part.Text("done")))), "failure")
        val before = b.snapshot().single()
        b.collapse(id, true); b.collapse(id, false)
        val after = b.snapshot().single()
        assertThat(after.items).isEqualTo(before.items)
        assertThat(after.frames.single().payload).isEqualTo(payload)
        assertThat(after.files.single()).isEqualTo(file)
        assertThat(after.error).isEqualTo("failure")
        assertThat(after.reconciled).isFalse()
    }
}
