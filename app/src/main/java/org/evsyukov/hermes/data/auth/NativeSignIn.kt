package org.evsyukov.hermes.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Tokens the dashboard issued to this app; [expiresAt] is in unix seconds. */
@Serializable
data class Tokens(
    val accessToken: String,
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val provider: String = "",
    val userId: String = "",
)

/** A PKCE pair and the state that ties the callback to this sign-in. */
class PkceRequest(random: SecureRandom = SecureRandom()) {
    val verifier: String = base64Url(ByteArray(32).also(random::nextBytes))
    val challenge: String = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    val state: String = base64Url(ByteArray(24).also(random::nextBytes))

    private companion object {
        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** What came back to the loopback address after the browser part of the sign-in. */
sealed interface CallbackResult {
    data class Code(val code: String, val state: String) : CallbackResult
    data class Error(val error: String, val description: String?) : CallbackResult
}

/** The words of the page the browser shows after the sign-in, supplied by the caller in the user's language. */
data class CallbackPageText(val signedIn: String, val failed: String, val back: String)

/**
 * The dashboard accepts a native redirect only on the loopback interface (`native_pkce`), so the app
 * listens on 127.0.0.1 while the sign-in runs in a Custom Tab, the way RFC 8252 describes.
 */
class LoopbackReceiver : AutoCloseable {
    private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

    val redirectUri: String = "http://127.0.0.1:${socket.localPort}/callback"

    /**
     * Waits for the one request to `/callback` and answers it with a page that sends the browser back
     * to the app through [returnUrl], worded by [page]. Other requests (a favicon) are answered with 404 and skipped.
     */
    suspend fun await(returnUrl: String, page: CallbackPageText, timeoutMillis: Long = 5 * 60_000): CallbackResult = withContext(Dispatchers.IO) {
        // accept() ignores coroutine cancellation; closing the socket ends it when the sign-in is abandoned.
        coroutineContext[Job]?.invokeOnCompletion { socket.close() }
        withTimeout(timeoutMillis) {
            var result: CallbackResult? = null
            while (result == null) result = acceptOne(returnUrl, page)
            result
        }
    }

    /** Serves one request; null when it was not the callback. */
    private fun acceptOne(returnUrl: String, page: CallbackPageText): CallbackResult? {
        val client = try {
            socket.accept()
        } catch (e: SocketException) {
            throw IllegalStateException("Sign-in was cancelled", e)
        }
        client.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.US_ASCII))
            val target = reader.readLine().orEmpty().split(' ').getOrNull(1).orEmpty()
            val out = it.getOutputStream()
            if (target.substringBefore('?') != "/callback") {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return null
            }
            val params = parseQuery(target.substringAfter('?', ""))
            val error = params["error"]
            val body = callbackPage(returnUrl, page, error == null).toByteArray(Charsets.UTF_8)
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
            return if (error != null) {
                CallbackResult.Error(error, params["error_description"])
            } else {
                CallbackResult.Code(params["code"].orEmpty(), params["state"].orEmpty())
            }
        }
    }

    override fun close() {
        socket.close()
    }

    companion object {
        fun parseQuery(query: String): Map<String, String> = query.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val key = URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8)
                val value = URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8)
                key to value
            }

        private fun callbackPage(returnUrl: String, page: CallbackPageText, ok: Boolean): String {
            val title = if (ok) page.signedIn else page.failed
            return """
                <!doctype html><html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>$title</title>
                <style>body{font-family:sans-serif;background:#212121;color:#ececec;display:flex;flex-direction:column;
                align-items:center;justify-content:center;height:90vh;margin:0}a{margin-top:24px;padding:14px 28px;
                border-radius:24px;background:#fff;color:#0d0d0d;text-decoration:none;font-weight:600}</style>
                </head><body><h2>$title</h2><a href="$returnUrl">${page.back}</a>
                <script>setTimeout(function(){location.href="$returnUrl"},150)</script></body></html>
            """.trimIndent()
        }
    }
}

/** The address the browser opens: the dashboard sends it on to the identity provider itself. */
fun authorizeUrl(base: HttpUrl, request: PkceRequest, redirectUri: String, provider: String?): HttpUrl =
    base.newBuilder()
        .addPathSegments("auth/native/authorize")
        .addQueryParameter("code_challenge", request.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("state", request.state)
        .apply { if (!provider.isNullOrEmpty()) addQueryParameter("provider", provider) }
        .build()
