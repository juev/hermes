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
    private val authority = store.authority
    val observations get() = store.observations
    val observationServer: String get() = if (profile.androidChannel)
        requireNotNull(profile.url).newBuilder().addPathSegments("api/plugins/hermes-android").build().toString()
    else profile.url.toString()

    /** Suspension gates for actual native/cleanup boundary fixtures; absent in app use. */
    @Volatile
    internal var beforeNativeBootstrap: (suspend () -> Unit)? = null
    @Volatile
    internal var afterNativeCommit: (suspend (WorkOwner) -> Unit)? = null
    internal val localDataEnabled: Boolean get() = keepsLocalData

    val http = HermesHttp(
        base = requireNotNull(profile.url) { "Bad server address ${profile.baseUrl}" },
        client = client,
        tokens = { authority.currentCredentials(profile.id) },
        strings = strings,
        lifetime = authority,
        profileId = profile.id,
        saveTokensAt = { tokens, at -> authority.commitRefresh(store, profile.id, at, tokens) },
        credentialsAt = { at -> authority.credentials(profile.id, at) },
        holdsAt = { at -> authority.holds(profile.id, at) },
        androidChannel = profile.androidChannel,
    )
    /** False once the server left the phone or was signed out, so a late answer is not kept again. */
    @Volatile
    private var keepsLocalData = true

    /** The profile and generation work starting now belongs to. A producer captures it once, early. */
    fun owner(): WorkOwner = authority.owner(profile.id)
    fun holds(owner: WorkOwner): Boolean = owner.profileId == profile.id && authority.holds(profile.id, owner.generation)
    fun <T> publish(owner: WorkOwner, block: () -> T): T? =
        if (owner.profileId != profile.id) null else authority.publish(owner, block)

    private val reads = NetworkReads(http, cache, profile.id, authority) { keepsLocalData }
    private val cachedReads = cache?.let { CachedReads(it, profile.id, authority) }
    val admin = AdminApi(http, reads, cachedReads)
    val chat = ChatApi(http, reads, cachedReads)

    /**
     * The chat routes of a chain that captured [owner] before it started: what it keeps stays bound
     * to that generation, so a later request of the same chain cannot borrow a newer permission.
     */
    fun chatFor(owner: WorkOwner): ChatApi = ChatApi(http, reads.ownedBy(owner), cachedReads?.ownedBy(owner), owner)

    /** Stops this session from writing to the phone's cache: its server is leaving. */
    fun retireLocalData() {
        keepsLocalData = false
    }

    /** The server stayed on the phone; this session may keep its answers again. */
    fun resumeLocalData(owner: WorkOwner) {
        publish(owner) { keepsLocalData = true }
    }

    /** Drops what the phone keeps of a deleted chat. */
    suspend fun forgetChat(id: String, owner: WorkOwner = owner(), wanted: () -> Boolean = { true }) {
        if (owner.profileId != profile.id) return
        authority.withOwner(owner) {
            var failure: Exception? = null
            suspend fun obligation(block: suspend () -> Unit) {
                try { block() }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (error: Exception) { val first = failure; if (first == null) failure = error else if (first !== error) first.addSuppressed(error) }
            }
            obligation { cache?.drop(profile.id, cacheKey(http.chatPath("api/sessions/$id"), emptyMap()), wanted) }
            obligation { cache?.forget(profile.id, http.chatPath("api/sessions/$id/"), wanted) }
            obligation { if (wanted()) observations.clearAdmitted(profile.id, observationServer, id) }
            failure?.let { throw it }
        }
    }

    suspend fun authInfo(expected: WorkOwner? = null): AuthInfo {
        val request = Request.Builder().url(http.endpoint("api/status").build()).build()
        val status = client.newCall(request).await { enqueue ->
            if (expected == null) { enqueue(); true } else publish(expected) { enqueue(); true } == true
        }.consume { response ->
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
    suspend fun signIn(
        provider: String?,
        returnUrl: String,
        page: CallbackPageText,
        expected: WorkOwner = owner(),
        openBrowser: (String) -> Unit,
    ): NativeResult {
        beforeNativeBootstrap?.invoke()
        authority.bootstrap(store)
        // A session of a removed profile has no authority to renew. It must not even open the
        // browser: a profile that is not on the phone cannot authorize anything again.
        if (!holds(expected)) return NativeResult.Rejected
        // The intent of this attempt, captured before the browser opens; a competing auth or a
        // retirement that wins while the browser is open makes this attempt stale.
        val pkce = PkceRequest()
        LoopbackReceiver().use { receiver ->
            if (publish(expected) { openBrowser(authorizeUrl(http.base, pkce, receiver.redirectUri, provider).toString()); true } != true) return NativeResult.Rejected
            when (val result = receiver.await(returnUrl, page)) {
                is CallbackResult.Error -> throw IOException(result.description ?: result.error)
                is CallbackResult.Code -> {
                    if (result.state != pkce.state) throw IOException(strings.get(R.string.error_sign_in_state))
                    val body = JsonObject(mapOf("code" to JsonPrimitive(result.code), "code_verifier" to JsonPrimitive(pkce.verifier)))
                    val request = Request.Builder().url(http.endpoint("auth/native/token").build()).post(body.toBody()).build()
                    val tokens = client.newCall(request).await { enqueue -> publish(expected) { enqueue(); true } == true }.consume { response ->
                        if (!response.isSuccessful) throw errorOf(response)
                        parseTokens(response.body.string())
                    }
                    return when (val committed = authority.commitNative(store, profile.id, expected.generation, tokens)) {
                        is NativeResult.Applied -> {
                            afterNativeCommit?.invoke(committed.owner)
                            resumeLocalData(committed.owner)
                            committed
                        }
                        is NativeResult.Failed -> throw committed.error
                        NativeResult.Rejected -> committed
                    }
                }
            }
        }
        error("The sign-in returned without a callback")
    }

    /**
     * Signs out and drops what the phone keeps of this server. [profileId] is the server the user
     * confirmed; a session that now belongs to another server does nothing. The authority clears
     * and retires under its gate; only an applied retirement may clean local data, and only while
     * its exact generation still stands, so a later authorization is not erased by this cleanup.
     */
    suspend fun signOut(profileId: String, expected: WorkOwner = owner(), clearDrafts: () -> Unit = {}, clearReads: suspend () -> Unit = {}): NativeResult {
        if (profileId != profile.id) return NativeResult.Rejected
        val result = authority.retireAndClear(store, profile.id, expected)
        if (result !is NativeResult.Applied) return result
        var failure: Exception? = null
        suspend fun obligation(block: suspend () -> Unit) {
            try { block() }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        val cleaned = authority.withRetirement(profile.id, result.owner.generation) {
            retireLocalData()
            obligation { cache?.forget(profile.id) }
            obligation { observations.clearAdmitted(profile.id) }
            obligation(clearReads)
            obligation { clearDrafts() }
            true
        }
        return result.copy(cleanupError = failure, cleanupSuperseded = cleaned == null)
    }

    /** True when the server answers an authenticated call; a 401 means the sign-in is needed. */
    suspend fun probe(): Boolean = probe(owner())

    suspend fun probe(owner: WorkOwner): Boolean = try {
        http.getJson(owner, "api/system/stats")
        true
    } catch (e: HermesException) {
        if (e.status == 401) false else throw e
    }
}
