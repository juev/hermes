package org.evsyukov.hermes.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CheckServerTest {
    @Test
    fun `with tokens on the phone the socket connects while the server is checked`() = runTest {
        var opened = 0
        var openedDuringCheck = 0

        val access = checkServer(hasTokens = true, authRequired = { openedDuringCheck = opened; true }, signedIn = { true }, openSocket = { opened++ })

        assertThat(access).isEqualTo(Access.OK)
        assertThat(openedDuringCheck).isAtLeast(1)
    }

    @Test
    fun `a server that needs a sign-in the phone has no tokens for gets no socket`() = runTest {
        var opened = 0

        val access = checkServer(hasTokens = false, authRequired = { true }, signedIn = { true }, openSocket = { opened++ })

        assertThat(access).isEqualTo(Access.SIGN_IN)
        assertThat(opened).isEqualTo(0)
    }

    @Test
    fun `a server without sign-in gets its socket once it answered`() = runTest {
        var opened = 0
        var openedDuringCheck = -1

        val access = checkServer(hasTokens = false, authRequired = { openedDuringCheck = opened; false }, signedIn = { false }, openSocket = { opened++ })

        assertThat(access).isEqualTo(Access.OK)
        assertThat(openedDuringCheck).isEqualTo(0)
        assertThat(opened).isAtLeast(1)
    }

    @Test
    fun `tokens the server refuses lead to the sign-in`() = runTest {
        val access = checkServer(hasTokens = true, authRequired = { true }, signedIn = { false }, openSocket = {})

        assertThat(access).isEqualTo(Access.SIGN_IN)
    }
}
