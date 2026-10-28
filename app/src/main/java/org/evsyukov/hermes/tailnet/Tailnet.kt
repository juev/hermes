package org.evsyukov.hermes.tailnet

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.server.ProxySettings
import org.evsyukov.hermes.data.server.ProxyType
import org.evsyukov.hermes.data.server.TailnetSettings
import java.io.File
import java.lang.reflect.Method

data class TailnetStatus(
    val available: Boolean,
    val state: String = "Stopped",
    val authUrl: String? = null,
    val ips: List<String> = emptyList(),
    val name: String? = null,
    val error: String? = null,
) {
    val running: Boolean get() = state == "Running"
}

/**
 * The Tailscale node built into the app (tailnet/, bound with gomobile). It joins the tailnet in
 * userspace and serves a SOCKS5 proxy on 127.0.0.1, which the HTTP client of a server set to
 * [ProxyType.TAILSCALE] uses. Without the library in the build the node reports itself unavailable.
 */
class Tailnet(context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "tailnet")
    private val engine = Engine.load()
    private val lock = Mutex()
    private var poller: Job? = null
    private var current: TailnetSettings? = null

    private val _proxy = MutableStateFlow<ProxySettings?>(null)
    val proxy: StateFlow<ProxySettings?> = _proxy.asStateFlow()

    private val _status = MutableStateFlow(TailnetStatus(available = engine != null))
    val status: StateFlow<TailnetStatus> = _status.asStateFlow()

    /** Starts the node with [settings] unless it already runs with them. */
    fun ensure(settings: TailnetSettings) {
        scope.launch {
            lock.withLock {
                if (current == settings && _proxy.value != null) return@withLock
                start(settings)
            }
        }
    }

    private fun start(settings: TailnetSettings) {
        val engine = engine ?: return
        current = settings
        _status.value = TailnetStatus(available = true, state = "Starting")
        try {
            dir.mkdirs()
            val answer = engine.start(dir.absolutePath, settings.controlUrl, settings.authKey, settings.hostname.ifBlank { "hermes-android" })
            val (address, password) = answer.split('\n', limit = 2)
            val host = address.substringBeforeLast(':')
            val port = address.substringAfterLast(':').toInt()
            _proxy.value = ProxySettings(ProxyType.SOCKS5, host, port, "tsnet", password)
        } catch (e: Exception) {
            Log.w(TAG, "Tailnet start failed", e)
            _status.value = TailnetStatus(available = true, state = "Error", error = e.cause?.message ?: e.message)
            return
        }
        poll()
    }

    fun stop() {
        scope.launch {
            lock.withLock {
                poller?.cancel()
                engine?.stop()
                current = null
                _proxy.value = null
                _status.value = TailnetStatus(available = engine != null)
            }
        }
    }

    /** Forgets the node key: the next start logs in anew. */
    fun reset() {
        stop()
        scope.launch { lock.withLock { dir.deleteRecursively() } }
    }

    fun logs(): String = engine?.logs().orEmpty()

    private fun poll() {
        poller?.cancel()
        poller = scope.launch(Dispatchers.IO) {
            while (isActive) {
                _status.value = parse(engine?.status().orEmpty())
                delay(if (_status.value.running) 15_000 else 1_500)
            }
        }
    }

    private fun parse(json: String): TailnetStatus = try {
        val obj = HermesJson.parseToJsonElement(json) as JsonObject
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        TailnetStatus(
            available = true,
            state = str("state") ?: "Unknown",
            authUrl = str("authUrl"),
            ips = (obj["ips"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            name = str("name")?.trimEnd('.'),
            error = str("error"),
        )
    } catch (e: Exception) {
        TailnetStatus(available = true, state = "Unknown", error = e.message)
    }

    /** The static methods gomobile generated for the Go package `tailnet`. */
    private class Engine(private val start: Method, private val stop: Method, private val status: Method, private val logs: Method) {
        fun start(dir: String, control: String, key: String, host: String): String = start.invoke(null, dir, control, key, host) as String
        fun stop() {
            stop.invoke(null)
        }
        fun status(): String = status.invoke(null) as String
        fun logs(): String = logs.invoke(null) as String

        companion object {
            fun load(): Engine? = try {
                val cls = Class.forName("org.evsyukov.hermes.tailnet.go.tailnet.Tailnet")
                val s = String::class.java
                Engine(cls.getMethod("start", s, s, s, s), cls.getMethod("stop"), cls.getMethod("status"), cls.getMethod("logs"))
            } catch (e: ReflectiveOperationException) {
                null
            } catch (e: LinkageError) {
                null
            }
        }
    }

    private companion object {
        const val TAG = "HermesTailnet"
    }
}
