package org.evsyukov.hermes.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ColumnWidthsTest {
    @Test
    fun `columns that fit on one line stretch alike to the full width`() {
        val widths = columnWidths(intArrayOf(40, 50), intArrayOf(100, 300), 800)

        assertThat(widths.sum()).isEqualTo(800)
        assertThat(widths.toList()).containsExactly(200, 600).inOrder()
    }

    @Test
    fun `each column gets its longest word and the rest goes to the text that wraps`() {
        // The first column is one word; the second has 600 pixels of text to wrap.
        val widths = columnWidths(intArrayOf(60, 100), intArrayOf(60, 700), 400)

        assertThat(widths.toList()).containsExactly(60, 340).inOrder()
    }

    @Test
    fun `no column is narrower than its longest word`() {
        val least = intArrayOf(80, 120, 90)
        val widths = columnWidths(least, intArrayOf(300, 900, 500), 400)

        widths.forEachIndexed { i, w -> assertThat(w).isAtLeast(least[i]) }
        assertThat(widths.sum()).isEqualTo(400)
    }

    @Test
    fun `words wider than the screen together make the table scroll`() {
        val widths = columnWidths(intArrayOf(150, 150, 150), intArrayOf(300, 300, 300), 400)

        assertThat(widths.toList()).containsExactly(150, 150, 150).inOrder()
        assertThat(widths.sum()).isGreaterThan(400)
    }

    @Test
    fun `one long link may break rather than push the table off the screen`() {
        val widths = columnWidths(intArrayOf(40, 900), intArrayOf(40, 900), 400)

        assertThat(widths.sum()).isEqualTo(400)
        assertThat(widths[0]).isAtLeast(40)
    }
}
