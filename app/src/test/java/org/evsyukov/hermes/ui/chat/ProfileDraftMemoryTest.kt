package org.evsyukov.hermes.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Drafts live with a profile id, not with the connection built for it. */
class ProfileDraftMemoryTest {
    @Test
    fun `two profiles with the same chat id keep separate drafts`() {
        val memory = ProfileDraftMemory()
        val first = checkNotNull(memory.of("one").obtain(DraftKey.Chat("s1"))).apply { text = "one" }
        val second = checkNotNull(memory.of("two").obtain(DraftKey.Chat("s1"))).apply { text = "two" }

        assertThat(memory.of("one").obtain(DraftKey.Chat("s1"))).isSameInstanceAs(first)
        assertThat(memory.of("two").obtain(DraftKey.Chat("s1"))).isSameInstanceAs(second)
        assertThat(first.text).isEqualTo("one")
        assertThat(second.text).isEqualTo("two")
    }

    @Test
    fun `forgetting a profile leaves the other profile's drafts`() {
        val memory = ProfileDraftMemory()
        checkNotNull(memory.of("one").obtain(DraftKey.NewChat)).text = "one"
        val kept = checkNotNull(memory.of("two").obtain(DraftKey.Chat("s1"))).apply { text = "two" }

        memory.forget("one")

        assertThat(memory.retainedProfiles()).containsExactly("two")
        assertThat(memory.of("two").obtain(DraftKey.Chat("s1"))).isSameInstanceAs(kept)
        assertThat(checkNotNull(memory.of("one").obtain(DraftKey.NewChat)).text).isEmpty()
    }

    @Test
    fun `retaining only existing profiles drops the stores of removed ones`() {
        val memory = ProfileDraftMemory()
        checkNotNull(memory.of("one").obtain(DraftKey.Chat("s1"))).text = "gone"
        checkNotNull(memory.of("two").obtain(DraftKey.Chat("s1"))).text = "kept"

        memory.retain(setOf("two"))

        assertThat(memory.retainedProfiles()).containsExactly("two")
        assertThat(checkNotNull(memory.of("one").obtain(DraftKey.Chat("s1"))).text).isEmpty()
        assertThat(checkNotNull(memory.of("two").obtain(DraftKey.Chat("s1"))).text).isEqualTo("kept")
    }

    @Test
    fun `forgetting one chat does not create a store for a removed profile`() {
        val memory = ProfileDraftMemory()

        memory.forget("gone", "s1")

        assertThat(memory.retainedProfiles()).isEmpty()
    }
}
