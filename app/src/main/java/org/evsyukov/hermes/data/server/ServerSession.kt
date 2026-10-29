package org.evsyukov.hermes.data.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.auth.CallbackPageText
import org.evsyukov.hermes.data.auth.CallbackResult
import org.evsyukov.hermes.data.auth.LoopbackReceiver
import org.evsyukov.hermes.data.auth.PkceRequest
import org.evsyukov.hermes.data.auth.authorizeUrl
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.remote.AdminApi
import org.evsyukov.hermes.data.remote.CachedReads
import org.evsyukov.hermes.data.remote.ChatApi
import org.evsyukov.hermes.data.remote.HermesException
import org.evsyukov.hermes.data.remote.HermesHttp
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.remote.NetworkReads
import org.evsyukov.hermes.data.remote.cacheKey
import org.evsyukov.hermes.data.remote.consume
import org.evsyukov.hermes.data.remote.await
import org.evsyukov.hermes.data.remote.bool
import org.evsyukov.hermes.data.remote.errorOf
import org.evsyukov.hermes.data.remote.parseTokens
import org.evsyukov.hermes.data.remote.str
import org.evsyukov.hermes.data.remote.toBody
import java.io.IOException

/** What `/api/status` says about signing in, read before the user signs in. */
data class AuthInfo(val required: Boolean, val nativePkce: Boolean, val providers: List<String>, val version: String?)

/** The APIs of one server, bound to its HTTP client and its tokens. */
class ServerSession(
    val profile: ServerProfile,
    val client: OkHttpClient,
    private val store: ServerStore,
    private val strings: AppStrings,
    /** Keeps the answers of chats and screens on the phone; a session only tried out has none. */
    private val cache: ResponseCache? = null,
) {
    val http = HermesHttp(
        base = requireNotNull(profile.url) { "Bad server address ${profile.baseUrl}" },
        client = client,
        tokens = { store.state.value.tokens[profile.id] ?: store.load().tokens[profile.id] },
        saveTokens = { store.setTokens(profile.id, it) },
        strings = strings,
    )
    private val reads = NetworkReads(http, cache, profile.id)
    private val cachedReads = cache?.let { CachedReads(it, profile.id) }
    val admin = AdminApi(http, reads, cachedReads)
    val chat = ChatApi(http, reads, cachedReads)

    /** Drops what the phone keeps of a deleted chat. */
    suspend fun forgetChat(id: String) {
        cache?.drop(profile.id, cacheKey("api/sessions/$id", emptyMap()))
        cache?.forget(profile.id, "api/sessions/$id/")
    }

    suspend fun authInfo(): AuthInfo {
        val request = Request.Builder().url(http.endpoint("api/status").build()).build()
        val status = client.newCall(request).await().consume { response ->
            if (!response.isSuccessful) throw errorOf(response)
            HermesJson.parseToJsonElement(response.body.string()).jsonObject
        }
        return AuthInfo(
            required = status.bool("auth_required") ?: false,
            nativePkce = (status["auth_flows"] as? JsonArray)?.any { it.str() == "native_pkce" } ?: false,
            providers = (status["auth_providers"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty(),
            version = status["version"].str(),
        )
    }

    /**
     * Runs the native sign-in: [openBrowser] shows the dashboard's authorize page in a Custom Tab,
     * the dashboard sends the browser back to the loopback address with a one-time code, and the
     * code is traded for tokens within its two minutes.
     */
    suspend fun signIn(provider: String?, returnUrl: String, page: CallbackPageText, openBrowser: (String) -> Unit) {
        val pkce = PkceRequest()
        LoopbackReceiver().use { receiver ->
            openBrowser(authorizeUrl(http.base, pkce, receiver.redirectUri, provider).toString())
            when (val result = receiver.await(returnUrl, page)) {
                is CallbackResult.Error -> throw IOException(result.description ?: result.error)
                is CallbackResult.Code -> {
                    if (result.state != pkce.state) throw IOException(strings.get(R.string.error_sign_in_state))
                    val body = JsonObject(mapOf("code" to JsonPrimitive(result.code), "code_verifier" to JsonPrimitive(pkce.verifier)))
                    val request = Request.Builder().url(http.endpoint("auth/native/token").build()).post(body.toBody()).build()
                    client.newCall(request).await().consume { response ->
                        if (!response.isSuccessful) throw errorOf(response)
                        store.setTokens(profile.id, parseTokens(response.body.string()))
                    }
                }
            }
        }
    }

    /** Signs out and drops what the phone keeps of this server. */
    suspend fun signOut() {
        store.setTokens(profile.id, null)
        cache?.forget(profile.id)
    }

    /** True when the server answers an authenticated call; a 401 means the sign-in is needed. */
    suspend fun probe(): Boolean = try {
        admin.systemStats()
        true
    } catch (e: HermesException) {
        if (e.status == 401) false else throw e
    }
}
