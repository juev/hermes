package org.evsyukov.hermes.ui.viewer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ReadTextTest {
    @Test
    fun `a short file is read whole`() {
        assertThat(readText(ByteArrayInputStream("hello".toByteArray()), 10, "cut")).isEqualTo("hello")
    }

    @Test
    fun `a file of exactly the limit is not marked as cut`() {
        assertThat(readText(ByteArrayInputStream("12345".toByteArray()), 5, "cut")).isEqualTo("12345")
    }

    @Test
    fun `a longer file is cut at the limit and marked`() {
        assertThat(readText(ByteArrayInputStream("123456789".toByteArray()), 5, "cut")).isEqualTo("12345\n\ncut")
    }

    @Test
    fun `a stream that returns a few bytes per read is still read to the limit`() {
        val trickle = object : InputStream() {
            private val data = "abcdefgh".toByteArray()
            private var at = 0
            override fun read(): Int = if (at < data.size) data[at++].toInt() else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= data.size) return -1
                b[off] = data[at++]
                return 1
            }
        }
        assertThat(readText(trickle, 6, "cut")).isEqualTo("abcdef\n\ncut")
    }
}
