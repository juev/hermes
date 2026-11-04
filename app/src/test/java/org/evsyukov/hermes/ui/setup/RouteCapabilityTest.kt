package org.evsyukov.hermes.ui.setup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.evsyukov.hermes.data.server.ProxyType
import org.junit.Test

class RouteCapabilityTest {
    @Test
    fun `the embedded route is supported only when the build carries the node`() {
        assertThat(routeSupported(ProxyType.TAILSCALE, false)).isFalse()
        assertThat(routeSupported(ProxyType.TAILSCALE, true)).isTrue()
    }

    @Test
    fun `other routes do not depend on the embedded node`() {
        for (type in listOf(ProxyType.NONE, ProxyType.HTTP, ProxyType.SOCKS5)) {
            assertThat(routeSupported(type, false)).isTrue()
        }
    }

    @Test
    fun `a check of the embedded route does not start or await anything without the node`() = runTest {
        var probed = false

        val answer = guardedCheck(ProxyType.TAILSCALE, tailnetAvailable = false, blocked = "no node") {
            probed = true
            "server answered"
        }

        assertThat(answer).isEqualTo("no node")
        assertThat(probed).isFalse()
    }

    @Test
    fun `a check runs when the build has the node`() = runTest {
        var probed = false

        val answer = guardedCheck(ProxyType.TAILSCALE, tailnetAvailable = true, blocked = "no node") {
            probed = true
            "server answered"
        }

        assertThat(answer).isEqualTo("server answered")
        assertThat(probed).isTrue()
    }
}
