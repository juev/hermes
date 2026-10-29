package org.evsyukov.hermes.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

class AmountsTest {
    private val locale = Locale.getDefault()

    @Before
    fun setUp() = Locale.setDefault(Locale.US)

    @After
    fun tearDown() = Locale.setDefault(locale)

    @Test
    fun `token counts shorten to thousands and millions`() {
        assertThat(tokenCount(950)).isEqualTo("950")
        assertThat(tokenCount(12_400)).isEqualTo("12.4 K")
        assertThat(tokenCount(1_250_000)).isEqualTo("1.3 M")
        assertThat(tokenCount(null)).isEqualTo("0")
    }

    @Test
    fun `dollars go to the cent, and less than a cent says so`() {
        assertThat(dollars(1.234)).isEqualTo("$1.23")
        assertThat(dollars(0.04)).isEqualTo("$0.04")
        assertThat(dollars(0.0031)).isEqualTo("<$0.01")
    }
}
