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
    private val saveTokens: suspend (Tokens?) -> Unit,
    private val strings: AppStrings,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val refreshLock = Mutex()

    fun endpoint(path: String): HttpUrl.Builder = base.newBuilder().addPathSegments(path.trimStart('/'))

    /** A valid access token, refreshed when it is about to run out; null on a server without sign-in. */
    suspend fun accessToken(force: Boolean = false): String? {
        val current = tokens() ?: return null
        if (!force && (current.expiresAt == 0L || now() < current.expiresAt - SKEW_SECONDS)) return current.accessToken
        return refreshLock.withLock {
            // Another request may have refreshed while this one waited.
            val latest = tokens() ?: return@withLock null
            if (latest.accessToken != current.accessToken && now() < latest.expiresAt - SKEW_SECONDS) return@withLock latest.accessToken
            refresh(latest).accessToken
        }
    }

    private suspend fun refresh(current: Tokens): Tokens {
        if (current.refreshToken.isEmpty()) {
            saveTokens(null)
            throw SignInRequiredException(strings.get(R.string.error_session_expired))
        }
        val body = JsonObject(mapOf("refresh_token" to JsonPrimitive(current.refreshToken), "provider" to JsonPrimitive(current.provider)))
        val request = Request.Builder().url(endpoint("auth/native/refresh").build()).post(body.toBody()).build()
        client.newCall(request).await().use { response ->
            when {
                response.isSuccessful -> {
                    val fresh = parseTokens(response.body.string(), current)
                    saveTokens(fresh)
                    return fresh
                }
                response.code == 401 || response.code == 400 -> {
                    saveTokens(null)
                    throw SignInRequiredException(strings.get(R.string.error_session_expired))
                }
                // 503: the identity provider is unreachable; the tokens stay for the next try.
                else -> throw errorOf(response)
            }
        }
    }

    /** Sends the request with the token and returns the open response; the caller closes it. */
    suspend fun execute(build: Request.Builder.() -> Unit): Response {
        val first = send(build, accessToken())
        if (first.code != 401 || tokens() == null) return first
        first.close()
        val retried = send(build, accessToken(force = true))
        if (retried.code == 401) {
            retried.close()
            saveTokens(null)
            throw SignInRequiredException(strings.get(R.string.error_sign_in_rejected))
        }
        return retried
    }

    private suspend fun send(build: Request.Builder.() -> Unit, token: String?): Response {
        val builder = Request.Builder().apply(build)
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.newCall(builder.build()).await()
    }

    suspend fun getJson(path: String, query: Map<String, Any?> = emptyMap()): JsonElement = json {
        url(withQuery(path, query))
    }

    suspend fun postJson(path: String, body: JsonElement = JsonObject(emptyMap())): JsonElement = json {
        url(withQuery(path, emptyMap())).post(body.toBody())
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

    suspend fun json(build: Request.Builder.() -> Unit): JsonElement = execute(build).use { response ->
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
