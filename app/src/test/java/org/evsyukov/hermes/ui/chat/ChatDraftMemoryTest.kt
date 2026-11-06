package org.evsyukov.hermes.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The memory that outlives a chat controller: one draft per chat, a fixed bound, explicit cleanup. */
class ChatDraftMemoryTest {
    @Test
    fun `a chat keeps one draft, and another chat has its own`() {
        val memory = ChatDraftMemory()
        val first = checkNotNull(memory.obtain(DraftKey.Chat("s1")))
        first.text = "for s1"

        assertThat(memory.obtain(DraftKey.Chat("s1"))).isSameInstanceAs(first)
        val second = checkNotNull(memory.obtain(DraftKey.Chat("s2")))
        assertThat(second).isNotSameInstanceAs(first)
        assertThat(second.text).isEmpty()
        assertThat(checkNotNull(memory.obtain(DraftKey.Chat("s1"))).text).isEqualTo("for s1")
    }

    @Test
    fun `deleting a chat drops its draft and leaves the others`() {
        val memory = ChatDraftMemory()
        val deleted = checkNotNull(memory.obtain(DraftKey.Chat("s1"))).apply { text = "gone" }
        val kept = checkNotNull(memory.obtain(DraftKey.Chat("s2"))).apply { text = "kept" }
        val fresh = checkNotNull(memory.obtain(DraftKey.NewChat)).apply { text = "new" }

        memory.forget("s1")

        assertThat(memory.obtain(DraftKey.Chat("s2"))).isSameInstanceAs(kept)
        assertThat(memory.obtain(DraftKey.NewChat)).isSameInstanceAs(fresh)
        val again = checkNotNull(memory.obtain(DraftKey.Chat("s1")))
        assertThat(again).isNotSameInstanceAs(deleted)
        assertThat(again.text).isEmpty()
    }

    @Test
    fun `clearing a profile drops every draft`() {
        val memory = ChatDraftMemory()
        checkNotNull(memory.obtain(DraftKey.Chat("s1"))).text = "one"
        checkNotNull(memory.obtain(DraftKey.NewChat)).text = "two"

        memory.clear()

        assertThat(checkNotNull(memory.obtain(DraftKey.Chat("s1"))).text).isEmpty()
        assertThat(checkNotNull(memory.obtain(DraftKey.NewChat)).text).isEmpty()
    }

    @Test
    fun `a new chat that gets its id keeps the same draft under that id`() {
        val memory = ChatDraftMemory()
        val fresh = checkNotNull(memory.obtain(DraftKey.NewChat)).apply { text = "still here" }

        memory.migrate(fresh, DraftKey.Chat("s1"))

        assertThat(memory.obtain(DraftKey.Chat("s1"))).isSameInstanceAs(fresh)
        assertThat(checkNotNull(memory.obtain(DraftKey.NewChat)).text).isEmpty()
    }

    @Test
    fun `an empty draft makes room and a draft with words stays`() {
        val memory = ChatDraftMemory(capacity = 2)
        val kept = checkNotNull(memory.obtain(DraftKey.Chat("kept"))).apply { text = "keep me" }
        checkNotNull(memory.obtain(DraftKey.Chat("empty-one")))

        checkNotNull(memory.obtain(DraftKey.Chat("empty-two")))

        assertThat(memory.obtain(DraftKey.Chat("kept"))).isSameInstanceAs(kept)
        assertThat(memory.obtain(DraftKey.Chat("empty-one"))).isNotSameInstanceAs(kept)
    }

    @Test
    fun `when every slot holds content a new draft is refused and nothing is removed`() {
        val memory = ChatDraftMemory(capacity = 2)
        val first = checkNotNull(memory.obtain(DraftKey.Chat("one"))).apply { text = "one" }
        val second = checkNotNull(memory.obtain(DraftKey.Chat("two"))).apply { text = "two" }

        assertThat(memory.obtain(DraftKey.Chat("three"))).isNull()

        assertThat(memory.obtain(DraftKey.Chat("one"))).isSameInstanceAs(first)
        assertThat(memory.obtain(DraftKey.Chat("two"))).isSameInstanceAs(second)
        assertThat(checkNotNull(memory.obtain(DraftKey.Chat("one"))).text).isEqualTo("one")
    }

    @Test
    fun `migrating to the stored id takes no second slot`() {
        val memory = ChatDraftMemory(capacity = 2)
        val fresh = checkNotNull(memory.obtain(DraftKey.NewChat)).apply { text = "sent" }
        val other = checkNotNull(memory.obtain(DraftKey.Chat("s2"))).apply { text = "other" }

        memory.migrate(fresh, DraftKey.Chat("s1"))

        assertThat(memory.obtain(DraftKey.Chat("s1"))).isSameInstanceAs(fresh)
        assertThat(memory.obtain(DraftKey.Chat("s2"))).isSameInstanceAs(other)
        assertThat(memory.obtain(DraftKey.Chat("s3"))).isNull()
    }

    @Test
    fun `a new chat stays sending until every message waiting for its name ends`() {
        val memory = ChatDraftMemory()
        val fresh = checkNotNull(memory.obtain(DraftKey.NewChat))
        memory.markSending(fresh)
        memory.markSending(fresh)

        memory.clearSending(fresh)

        assertThat(memory.isSendingNewChat()).isTrue()
        memory.clearSending(fresh)
        assertThat(memory.isSendingNewChat()).isFalse()
    }

    @Test
    fun `a new chat that got its name frees the next new chat even with a send still waiting`() {
        val memory = ChatDraftMemory()
        val fresh = checkNotNull(memory.obtain(DraftKey.NewChat))
        memory.markSending(fresh)
        memory.markSending(fresh)

        memory.migrate(fresh, DraftKey.Chat("s1"))

        assertThat(memory.isSendingNewChat()).isFalse()
        assertThat(checkNotNull(memory.obtain(DraftKey.NewChat))).isNotSameInstanceAs(fresh)
    }
}
