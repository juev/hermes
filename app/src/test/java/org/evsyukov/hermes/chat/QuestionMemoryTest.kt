package org.evsyukov.hermes.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The memory of one connection keeps each chat's questions apart, and another connection starts empty. */
class QuestionMemoryTest {
    private val card = QuestionCard("r1", listOf(Question("q0", "Which file?", emptyList())), state = QuestionState.SUBMITTED, answers = mapOf("q0" to "notes.md"))

    @Test
    fun `a chat keeps its cards, ended requests, connections and written answers, and no other chat sees them`() {
        val memory = QuestionMemory()
        memory.keep("s1", listOf(card))
        memory.end("s1", "r1", QuestionState.EXPIRED)
        memory.heard("s1", "r1", 2)
        memory.answers("s1", "r1")["q0"] = "draft"

        assertThat(memory.cards("s1")).containsExactly(card)
        assertThat(memory.ended("s1", "r1")).isEqualTo(QuestionState.EXPIRED)
        assertThat(memory.heardOn("s1", "r1")).isEqualTo(2)
        assertThat(memory.answers("s1", "r1")).containsExactly("q0", "draft")

        assertThat(memory.cards("s2")).isEmpty()
        assertThat(memory.ended("s2", "r1")).isNull()
        assertThat(memory.heardOn("s2", "r1")).isNull()
        assertThat(memory.answers("s2", "r1")).isEmpty()
        assertThat(QuestionMemory().cards("s1")).isEmpty()
    }

    @Test
    fun `answers already written are not replaced by what the server prefills later`() {
        val memory = QuestionMemory()
        memory.answers("s1", "r1")["q0"] = "mine"

        assertThat(memory.answers("s1", "r1", prefilled = mapOf("q0" to "locked"))).containsExactly("q0", "mine")
    }

    @Test
    fun `a deleted chat takes everything of its questions along`() {
        val memory = QuestionMemory()
        memory.keep("s1", listOf(card))
        memory.end("s1", "r1", QuestionState.CLOSED)
        memory.heard("s1", "r1", 1)
        memory.answers("s1", "r1")["q0"] = "draft"
        memory.keep("s2", listOf(card))

        memory.forget("s1")

        assertThat(memory.cards("s1")).isEmpty()
        assertThat(memory.ended("s1", "r1")).isNull()
        assertThat(memory.heardOn("s1", "r1")).isNull()
        assertThat(memory.answers("s1", "r1")).isEmpty()
        assertThat(memory.cards("s2")).containsExactly(card)
    }
}
