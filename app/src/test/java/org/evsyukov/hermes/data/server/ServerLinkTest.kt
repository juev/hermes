package org.evsyukov.hermes.data.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServerLinkTest {
    @Test
    fun `a tailnet link fills the route and the node`() {
        val profile = parseServerLink(
            "org.evsyukov.hermes://add-server?url=hermes.example.org&route=tailscale&control=https%3A%2F%2Fhs.example.org%2F&key=hskey-auth-abc_DEF-123&name=Home",
        )!!
        assertThat(profile.baseUrl).isEqualTo("https://hermes.example.org")
        assertThat(profile.name).isEqualTo("Home")
        assertThat(profile.proxy.type).isEqualTo(ProxyType.TAILSCALE)
        assertThat(profile.tailnet).isEqualTo(TailnetSettings("https://hs.example.org", "hskey-auth-abc_DEF-123", "hermes-android"))
    }

    @Test
    fun `a proxy link keeps its login`() {
        val profile = parseServerLink("org.evsyukov.hermes://add-server?url=http://10.0.0.5:9119&route=socks5&host=10.0.0.1&port=1080&user=me&password=p%40ss")!!
        assertThat(profile.baseUrl).isEqualTo("http://10.0.0.5:9119")
        assertThat(profile.proxy).isEqualTo(ProxySettings(ProxyType.SOCKS5, "10.0.0.1", 1080, "me", "p@ss"))
    }

    @Test
    fun `a link without an address, of another host or with an unknown route is refused`() {
        assertThat(parseServerLink("org.evsyukov.hermes://add-server?route=tailscale")).isNull()
        assertThat(parseServerLink("org.evsyukov.hermes://signed-in?url=hermes.example.org")).isNull()
        assertThat(parseServerLink("org.evsyukov.hermes://add-server?url=hermes.example.org&route=carrier-pigeon")).isNull()
    }
}
