package org.evsyukov.hermes.data.server

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** How the app reaches a server that is not on the open internet. */
@Serializable
enum class ProxyType { NONE, HTTP, SOCKS5, TAILSCALE }

/**
 * A proxy for the traffic of one server. [TAILSCALE] means the tailnet built into the app: it serves
 * a local SOCKS5, so the phone needs no VPN and keeps another VPN running.
 */
@Serializable
data class ProxySettings(
    val type: ProxyType = ProxyType.NONE,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
)

/** Settings of the tailnet built into the app: the control server and a pre-authorised key. */
@Serializable
data class TailnetSettings(
    val controlUrl: String = "",
    val authKey: String = "",
    val hostname: String = "hermes-android",
)

/** A Hermes dashboard the app talks to, as the user entered it. */
@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val proxy: ProxySettings = ProxySettings(),
    val tailnet: TailnetSettings = TailnetSettings(),
) {
    /** The parsed address without a trailing slash, or null while it is not a valid http(s) URL. */
    val url: HttpUrl? get() = normalizeBaseUrl(baseUrl)?.toHttpUrlOrNull()
}

/**
 * Adds https:// to a bare host and drops the trailing slash, so `hermes.example.org/` and
 * `https://hermes.example.org` name the same server. Returns null for anything else than http(s).
 */
fun normalizeBaseUrl(input: String): String? {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    if (url.scheme != "http" && url.scheme != "https") return null
    return url.toString().trimEnd('/')
}
