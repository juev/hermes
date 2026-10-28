package org.evsyukov.hermes.data.server

import android.util.Base64
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * An HTTP proxy on 127.0.0.1 without a password that sends every connection on along the route of a
 * server: directly, through an HTTP or SOCKS5 proxy with its login, or through the built-in tailnet.
 * The sign-in WebView uses it, since Chromium can neither log in to a SOCKS5 proxy nor reach the
 * tailnet by itself. It takes CONNECT for https and absolute URLs for plain http. [route] is the
 * effective one (see [effectiveRoute]): the tailnet comes as its local SOCKS5.
 */
class LocalForwardProxy(private val route: ProxySettings) : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    val port: Int get() = server.localPort

    init {
        thread(name = "forward-proxy", isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                thread(name = "forward-proxy-conn", isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        client.use {
            val input = BufferedInputStream(client.getInputStream())
            val head = readHead(input) ?: return
            val requestLine = head.lineSequence().first()
            val (method, target) = requestLine.split(' ').let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
            val out = client.getOutputStream()
            try {
                if (method == "CONNECT") {
                    val host = target.substringBeforeLast(':')
                    val port = target.substringAfterLast(':').toIntOrNull() ?: 443
                    open(host.trim('[', ']'), port).use { upstream ->
                        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                        out.flush()
                        pipe(input, out, upstream)
                    }
                } else {
                    // Plain http: the request goes on in origin form, one per connection.
                    val url = java.net.URI(target)
                    val port = if (url.port > 0) url.port else 80
                    open(url.host, port).use { upstream ->
                        val path = (url.rawPath.ifEmpty { "/" }) + (url.rawQuery?.let { "?$it" } ?: "")
                        val headers = head.lineSequence().drop(1)
                            .filter { it.isNotEmpty() && !it.startsWith("Proxy-", true) && !it.startsWith("Connection:", true) }
                            .joinToString("\r\n")
                        upstream.getOutputStream().write("$method $path HTTP/1.1\r\n$headers\r\nConnection: close\r\n\r\n".toByteArray())
                        pipe(input, out, upstream)
                    }
                }
            } catch (e: IOException) {
                runCatching { out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
            }
        }
    }

    /** Opens a connection to [host] along the route; names resolve at the proxy, so tailnet names work. */
    private fun open(host: String, port: Int): Socket = when (route.type) {
        ProxyType.NONE, ProxyType.TAILSCALE -> Socket(host, port)
        ProxyType.SOCKS5 -> {
            if (route.username.isNotEmpty()) registerSocksLogin(route)
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(route.host, route.port))).apply {
                connect(InetSocketAddress.createUnresolved(host, port), 20_000)
            }
        }
        ProxyType.HTTP -> {
            val socket = Socket(route.host, route.port)
            val auth = if (route.username.isNotEmpty()) {
                val token = Base64.encodeToString("${route.username}:${route.password}".toByteArray(), Base64.NO_WRAP)
                "Proxy-Authorization: Basic $token\r\n"
            } else {
                ""
            }
            socket.getOutputStream().write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n$auth\r\n".toByteArray())
            val answer = readHead(BufferedInputStream(socket.getInputStream(), 1)) ?: throw IOException("Proxy closed")
            if (answer.lineSequence().first().split(' ').getOrNull(1) != "200") {
                socket.close()
                throw IOException("Proxy refused: ${answer.lineSequence().first()}")
            }
            socket
        }
    }

    private fun pipe(clientIn: InputStream, clientOut: OutputStream, upstream: Socket) {
        val back = thread(isDaemon = true) {
            runCatching { upstream.getInputStream().copyTo(clientOut) }
            runCatching { clientOut.close() }
        }
        runCatching { clientIn.copyTo(upstream.getOutputStream()) }
        runCatching { upstream.shutdownOutput() }
        back.join()
    }

    /** Reads up to the blank line that ends the head of an HTTP message. */
    private fun readHead(input: InputStream): String? {
        val buffer = StringBuilder()
        while (!buffer.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return null
            buffer.append(b.toChar())
            if (buffer.length > 64 * 1024) return null
        }
        return buffer.toString().trimEnd()
    }

    override fun close() {
        server.close()
    }
}
