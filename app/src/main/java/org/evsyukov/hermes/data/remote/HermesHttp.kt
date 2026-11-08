package org.evsyukov.hermes.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.auth.Tokens
import org.evsyukov.hermes.data.server.NativeResult
import org.evsyukov.hermes.data.server.ProfileLifetime
import org.evsyukov.hermes.data.server.WorkOwner
import java.io.IOException

/** The server answered with an error; [status] is the HTTP code and [detail] its message. */
class HermesException(val status: Int, val detail: String, val error: String? = null) : IOException("HTTP $status: $detail")

/** The session is over and only a new sign-in helps. */
class SignInRequiredException(detail: String) : IOException(detail)

val HermesJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
}

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

fun JsonElement.toBody(): RequestBody = HermesJson.encodeToString(JsonElement.serializer(), this).toRequestBody(JSON_TYPE)

/**
 * Talks to one dashboard. Each request carries the access token; the token is refreshed a minute before
 * it runs out, and once more after a 401, the way the desktop app does it.
 */
class HermesHttp(
    val base: HttpUrl,
    val client: OkHttpClient,
    private val tokens: suspend () -> Tokens?,
    private val saveTokens: (suspend (Tokens?) -> Unit)? = null,
    private val strings: AppStrings,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** The generations of the profile this client speaks for. */
    private val lifetime: ProfileLifetime? = null,
    private val profileId: String = "",
    /**
     * Saves tokens together with the generation of the request that produced them, so a refresh
     * that was on its way back when the profile was signed out or removed cannot restore them.
     */
    private val saveTokensAt: (suspend (Tokens?, Long) -> NativeResult)? = null,
    /** The one credential snapshot of an exact generation, for a chain that already captured it. */
    private val credentialsAt: ((Long) -> Tokens?)? = null,
    /** Whether a generation is still the current authority; a stale chain sends nothing. */
    private val holdsAt: ((Long) -> Boolean)? = null,
) {
    fun owner(): WorkOwner? = lifetime?.owner(profileId)
    fun holds(owner: WorkOwner?): Boolean = if (owner == null) lifetime == null else
        (profileId.isEmpty() || owner.profileId == profileId) && (holdsAt?.invoke(owner.generation) ?: true)
    fun <T> publishWork(owner: WorkOwner?, block: () -> T): T? = when {
        !holds(owner) -> null
        lifetime != null && owner != null -> lifetime.publish(owner, block)
        else -> block()
    }
    /** Authority guard before socket monitor, even for a stop whose owner has been retired. */
    internal fun <T> withSocketLock(socket: Any, block: () -> T): T {
        val locked = { synchronized(socket) { block() } }
        return if (lifetime == null) locked() else lifetime.withSocketPublicationLock(locked)
    }

    suspend fun accessToken(owner: WorkOwner, force: Boolean = false): String? {
        if (!holds(owner)) throw SignInRequiredException("Retired request origin")
        return accessToken(force, owner.generation)
    }

    private val refreshLock = Mutex()

    fun endpoint(path: String): HttpUrl.Builder = base.newBuilder().addPathSegments(path.trimStart('/'))

    /** A valid access token, refreshed when it is about to run out; null on a server without sign-in. */
    suspend fun accessToken(force: Boolean = false): String? = accessToken(force, generation())

    private suspend fun accessToken(force: Boolean, at: Long): String? {
        val current = credentials(at) ?: return null
        if (!force && (current.expiresAt == 0L || now() < current.expiresAt - SKEW_SECONDS)) return current.accessToken
        return refreshLock.withLock {
            // Another request may have refreshed while this one waited.
            val latest = credentials(at) ?: return@withLock null
            if (latest.accessToken != current.accessToken && now() < latest.expiresAt - SKEW_SECONDS) return@withLock latest.accessToken
            refresh(latest, at).accessToken
        }
    }

    /**
     * Reads the credentials of exactly [at]. A client with an authority fails closed for a stale
     * generation instead of falling back to another read; a plain test client keeps its supplier.
     */
    private suspend fun credentials(at: Long): Tokens? {
        // Generation zero is "no authority captured", used by callers that have not been migrated
        // to owners. It grants nothing: the request can go out unauthenticated, but no credential
        // is read and no cache write passes an authority check.
        if (at != 0L && holdsAt?.invoke(at) == false) {
            throw SignInRequiredException(strings.get(R.string.error_session_expired))
        }
        val exact = credentialsAt
        return if (exact != null) exact(at) else tokens()
    }

    /** The generation the work starting now belongs to; a client without a lifetime never changes it. */
    private fun generation(): Long = lifetime?.current(profileId) ?: 0L

    /**
     * Saves tokens of [at] and stops the chain when the authority did not take them: a rejected
     * refresh belongs to an older authorization and must not continue with its credentials, and a
     * current storage failure is reported rather than treated as a sign-out.
     */
    private suspend fun publish(tokens: Tokens?, at: Long) {
        val saveAt = saveTokensAt
        if (saveAt == null) {
            saveTokens?.invoke(tokens)
            return
        }
        when (val result = saveAt(tokens, at)) {
            is NativeResult.Applied -> Unit
            NativeResult.Rejected -> throw SignInRequiredException(strings.get(R.string.error_session_expired))
            is NativeResult.Failed -> throw result.error
        }
    }

    private suspend fun refresh(current: Tokens, at: Long): Tokens {
        if (current.refreshToken.isEmpty()) {
            publish(null, at)
            throw SignInRequiredException(strings.get(R.string.error_session_expired))
        }
        val body = JsonObject(mapOf("refresh_token" to JsonPrimitive(current.refreshToken), "provider" to JsonPrimitive(current.provider)))
        val request = Request.Builder().url(endpoint("auth/native/refresh").build()).post(body.toBody()).build()
        return client.newCall(request).await { enqueue -> publishWork(lifetime?.let { WorkOwner(profileId, at) }) { enqueue(); true } == true }.consume { response ->
            when {
                response.isSuccessful -> {
                    val fresh = parseTokens(response.body.string(), current)
                    publish(fresh, at)
                    fresh
                }
                response.code == 401 || response.code == 400 -> {
                    publish(null, at)
                    throw SignInRequiredException(strings.get(R.string.error_session_expired))
                }
                // 503: the identity provider is unreachable; the tokens stay for the next try.
                else -> throw errorOf(response)
            }
        }
    }

    /** Sends the request with the token and returns the open response; the caller closes it. */
    suspend fun execute(build: Request.Builder.() -> Unit): Response = executeFor(null, build)

    /**
     * Sends a request of the chain that captured [owner]. A superseded owner is rejected before
     * anything leaves the phone; the request can never borrow the credentials of a newer auth.
     */
    suspend fun execute(owner: WorkOwner, build: Request.Builder.() -> Unit): Response = executeFor(owner, build)

    private suspend fun executeFor(owner: WorkOwner?, build: Request.Builder.() -> Unit): Response {
        // One generation for the whole request, retry included: if the profile left while the
        // first call was out, the retry's fresh tokens are as stale as the first answer.
        val at = owner?.generation ?: generation()
        if (owner != null && !holds(owner)) throw SignInRequiredException(strings.get(R.string.error_session_expired))
        val first = send(build, accessToken(false, at), at)
        val retry = try { first.code == 401 && credentials(at) != null }
        catch (failure: Throwable) {
            try { first.close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
        if (!retry) return first
        first.close()
        val retried = send(build, accessToken(true, at), at)
        if (retried.code == 401) {
            retried.close()
            publish(null, at)
            throw SignInRequiredException(strings.get(R.string.error_sign_in_rejected))
        }
        return retried
    }

    private suspend fun send(build: Request.Builder.() -> Unit, token: String?, at: Long): Response {
        val builder = Request.Builder().apply(build)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val origin = lifetime?.let { WorkOwner(profileId, at) }
        return client.newCall(builder.build()).await { enqueue -> publishWork(origin) { enqueue(); true } == true }
    }

    suspend fun getJson(path: String, query: Map<String, Any?> = emptyMap()): JsonElement = json {
        url(withQuery(path, query))
    }

    /** A read of the chain that captured [owner]; a stale owner is rejected before sending. */
    suspend fun getJson(owner: WorkOwner, path: String, query: Map<String, Any?> = emptyMap()): JsonElement = json(owner) {
        url(withQuery(path, query))
    }

    suspend fun postJson(path: String, body: JsonElement = JsonObject(emptyMap())): JsonElement = json {
        url(withQuery(path, emptyMap())).post(body.toBody())
    }

    suspend fun postJson(owner: WorkOwner, path: String, body: JsonElement = JsonObject(emptyMap())): JsonElement = json(owner) {
        url(withQuery(path, emptyMap())).post(body.toBody())
    }
    suspend fun patchJson(owner: WorkOwner, path: String, body: JsonElement): JsonElement = json(owner) {
        url(withQuery(path, emptyMap())).patch(body.toBody())
    }
    suspend fun delete(owner: WorkOwner, path: String, body: JsonElement? = null): JsonElement = json(owner) {
        url(withQuery(path, emptyMap())).delete(body?.toBody())
    }

    suspend fun putJson(path: String, body: JsonElement): JsonElement = json {
        url(withQuery(path, emptyMap())).put(body.toBody())
    }

    suspend fun patchJson(path: String, body: JsonElement): JsonElement = json {
        url(withQuery(path, emptyMap())).patch(body.toBody())
    }

    suspend fun delete(path: String, body: JsonElement? = null): JsonElement = json {
        url(withQuery(path, emptyMap())).delete(body?.toBody())
    }

    suspend fun json(build: Request.Builder.() -> Unit): JsonElement = jsonFor(null, build)

    suspend fun json(owner: WorkOwner, build: Request.Builder.() -> Unit): JsonElement = jsonFor(owner, build)

    private suspend fun jsonFor(owner: WorkOwner?, build: Request.Builder.() -> Unit): JsonElement =
        executeFor(owner, build).consume { response ->
            if (!response.isSuccessful) throw errorOf(response)
            val text = response.body.string()
            if (text.isBlank()) JsonObject(emptyMap()) else HermesJson.parseToJsonElement(text)
        }

    private fun withQuery(path: String, query: Map<String, Any?>): HttpUrl {
        val builder = base.newBuilder()
        val (segments, raw) = path.trimStart('/').split('?', limit = 2).let { it[0] to it.getOrNull(1) }
        builder.addPathSegments(segments)
        if (raw != null) builder.encodedQuery(raw)
        query.forEach { (key, value) -> if (value != null) builder.addQueryParameter(key, value.toString()) }
        return builder.build()
    }

    private companion object {
        const val SKEW_SECONDS = 60
    }
}

fun parseTokens(text: String, previous: Tokens? = null): Tokens {
    val obj = HermesJson.parseToJsonElement(text) as JsonObject
    fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
    return Tokens(
        accessToken = str("access_token") ?: error("No access_token in the answer"),
        // An identity provider that does not rotate leaves the old refresh token valid.
        refreshToken = str("refresh_token")?.takeIf { it.isNotEmpty() } ?: previous?.refreshToken.orEmpty(),
        expiresAt = str("expires_at")?.toDoubleOrNull()?.toLong() ?: 0L,
        provider = str("provider") ?: previous?.provider.orEmpty(),
        userId = str("user_id") ?: previous?.userId.orEmpty(),
    )
}

/** FastAPI puts the message into `detail`, which is a string or a list of validation errors. */
fun errorOf(response: Response): HermesException {
    val text = try {
        response.body.string()
    } catch (e: IOException) {
        ""
    }
    val parsed = try {
        HermesJson.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    }
    val detail = when (val d = parsed?.get("detail")) {
        is JsonPrimitive -> d.contentOrNull
        null -> (parsed?.get("message") as? JsonPrimitive)?.contentOrNull
        else -> d.toString()
    } ?: text.take(300).ifBlank { response.message }
    val error = (parsed?.get("error") as? JsonPrimitive)?.contentOrNull
    return HermesException(response.code, detail, error)
}

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.string(key: String): String? = this[key]?.let { (it as? JsonPrimitive)?.contentOrNull }

internal fun JsonObject.long(key: String): Long? = this[key]?.let { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() }

internal fun JsonObject.double(key: String): Double? = this[key]?.let { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }

internal fun JsonObject.bool(key: String): Boolean? = this[key]?.let { (it as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() }

internal fun JsonElement.primitiveOrNull(): String? = (this as? JsonPrimitive)?.jsonPrimitive?.contentOrNull
