package org.evsyukov.hermes.data.server

import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Java hands SOCKS5 credentials only to the process-wide [Authenticator], so the proxies register
 * their logins here and the authenticator answers by host and port.
 */
private object SocksLogins : Authenticator() {
    private val logins = ConcurrentHashMap<String, PasswordAuthentication>()

    init {
        setDefault(this)
    }

    fun put(host: String, port: Int, user: String, password: String) {
        logins["$host:$port"] = PasswordAuthentication(user, password.toCharArray())
    }

    override fun getPasswordAuthentication(): PasswordAuthentication? =
        if (requestorType == RequestorType.SERVER) logins["$requestingHost:$requestingPort"] else null
}

/**
 * Builds the HTTP client of one server: the shared [base] pool and timeouts with the server's proxy.
 * [tailnetProxy] is the local SOCKS5 of the built-in tailnet, null while it is not running.
 */
fun clientFor(base: OkHttpClient, proxy: ProxySettings, tailnetProxy: ProxySettings?): OkHttpClient {
    val effective = if (proxy.type == ProxyType.TAILSCALE) tailnetProxy ?: return base.newBuilder().proxy(UNREACHABLE).build() else proxy
    return when (effective.type) {
        ProxyType.NONE, ProxyType.TAILSCALE -> base
        ProxyType.HTTP -> base.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(effective.host, effective.port)))
            .apply {
                if (effective.username.isNotEmpty()) {
                    proxyAuthenticator { _, response ->
                        response.request.newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(effective.username, effective.password))
                            .build()
                    }
                }
            }
            .build()
        ProxyType.SOCKS5 -> {
            if (effective.username.isNotEmpty()) SocksLogins.put(effective.host, effective.port, effective.username, effective.password)
            // OkHttp hands the target to a SOCKS proxy unresolved, so the proxy resolves tailnet names itself.
            base.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(effective.host, effective.port))).build()
        }
    }
}

/** A proxy nobody listens on: requests fail fast while the built-in tailnet is down. */
private val UNREACHABLE = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9))

fun baseClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    // The chat socket stays open for hours; pings notice a dead network before the user does.
    .pingInterval(20, TimeUnit.SECONDS)
    .build()
