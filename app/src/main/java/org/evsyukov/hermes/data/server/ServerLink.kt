package org.evsyukov.hermes.data.server

import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/**
 * A server described by a link, so it is set up without typing keys on the phone:
 *
 * `org.evsyukov.hermes://add-server?url=hermes.example.org&route=tailscale&control=https://hs.example.org&key=…`
 *
 * `route` is `direct` (the default), `tailscale`, `socks5` or `http`; a proxy takes `host`, `port`,
 * `user` and `password`; the tailnet takes `control`, `key` and `hostname`. The app only fills the
 * form from it; the user saves.
 */
fun parseServerLink(link: String): ServerProfile? {
    val uri = try {
        URI(link)
    } catch (e: Exception) {
        return null
    }
    if (uri.scheme != SCHEME || uri.host != HOST) return null
    val params = uri.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate { pair ->
        URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(pair.substringAfter('='), Charsets.UTF_8)
    }
    val url = normalizeBaseUrl(params["url"].orEmpty()) ?: return null
    val type = when (params["route"]?.lowercase()) {
        null, "", "direct" -> ProxyType.NONE
        "tailscale" -> ProxyType.TAILSCALE
        "socks5", "socks" -> ProxyType.SOCKS5
        "http" -> ProxyType.HTTP
        else -> return null
    }
    return ServerProfile(
        id = UUID.randomUUID().toString(),
        name = params["name"]?.takeIf { it.isNotBlank() } ?: "Hermes",
        baseUrl = url,
        proxy = ProxySettings(
            type = type,
            host = params["host"].orEmpty(),
            port = params["port"]?.toIntOrNull() ?: 0,
            username = params["user"].orEmpty(),
            password = params["password"].orEmpty(),
        ),
        tailnet = TailnetSettings(
            controlUrl = params["control"].orEmpty().trimEnd('/'),
            authKey = params["key"].orEmpty(),
            hostname = params["hostname"]?.takeIf { it.isNotBlank() } ?: "hermes-android",
        ),
    )
}

const val SCHEME = "org.evsyukov.hermes"
private const val HOST = "add-server"
