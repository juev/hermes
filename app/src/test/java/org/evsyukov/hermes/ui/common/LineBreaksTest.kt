package org.evsyukov.hermes.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LineBreaksTest {
    @Test
    fun `lines of a poem stay apart`() {
        assertThat(keepLineBreaks("Mountains rise.\nSnow crowns them.")).isEqualTo("Mountains rise.  \nSnow crowns them.")
    }

    @Test
    fun `paragraphs, lists, tables and code keep their syntax`() {
        val text = "Intro\n\n- one\n- two\n\n| a | b |\n|---|---|\n\n```\nx = 1\ny = 2\n```"
        assertThat(keepLineBreaks(text)).isEqualTo(text)
    }

    @Test
    fun `a line before a list is not given a break`() {
        assertThat(keepLineBreaks("Steps:\n1. first")).isEqualTo("Steps:\n1. first")
    }
}
