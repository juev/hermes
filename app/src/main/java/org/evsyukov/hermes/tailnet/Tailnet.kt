package org.evsyukov.hermes.tailnet

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
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

/** The functions of the Go package `tailnet`; see tailnet/tailnet.go. */
internal interface TailnetEngine {
    /** Returns "host:port\npassword" of the SOCKS5 proxy once it listens, before the node is up. */
    fun start(dir: String, control: String, key: String, host: String): String
    fun stop()
    fun status(): String
    fun logs(): String
}

/**
 * The Tailscale node built into the app (tailnet/, bound with gomobile). It joins the tailnet in
 * userspace and serves a SOCKS5 proxy on 127.0.0.1, which the HTTP client of a server set to
 * [ProxyType.TAILSCALE] uses. Without the library in the build the node reports itself unavailable.
 */
class Tailnet internal constructor(
    private val dir: File,
    private val scope: CoroutineScope,
    private val engine: TailnetEngine?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context, scope: CoroutineScope) : this(File(context.filesDir, "tailnet"), scope, GoEngine.load())

    private val lock = Mutex()
    private var poller: Job? = null
    private var current: TailnetSettings? = null

    /** The SOCKS5 proxy of the node started last; it listens before the node can reach anything. */
    private var listening: ProxySettings? = null

    private val _proxy = MutableStateFlow<ProxySettings?>(null)

    /**
     * The proxy once the node runs. Before that tsnet already accepts connections, but cannot resolve
     * tailnet names or route to peers, so the checks of the server would fail and stay failed.
     */
    val proxy: StateFlow<ProxySettings?> = _proxy.asStateFlow()

    private val _status = MutableStateFlow(TailnetStatus(available = engine != null))
    val status: StateFlow<TailnetStatus> = _status.asStateFlow()

    /** Starts the node with [settings] unless it already runs with them. */
    fun ensure(settings: TailnetSettings) {
        scope.launch {
            lock.withLock {
                if (current == settings && listening != null) return@withLock
                start(settings)
            }
        }
    }

    private fun start(settings: TailnetSettings) {
        val engine = engine ?: return
        current = settings
        // The node of the previous settings closes with this start, and its proxy with it.
        poller?.cancel()
        listening = null
        _proxy.value = null
        _status.value = TailnetStatus(available = true, state = "Starting")
        val startedAt = System.currentTimeMillis()
        val socks = try {
            dir.mkdirs()
            val answer = engine.start(dir.absolutePath, settings.controlUrl, settings.authKey, settings.hostname.ifBlank { "hermes-android" })
            val (address, password) = answer.split('\n', limit = 2)
            val host = address.substringBeforeLast(':')
            val port = address.substringAfterLast(':').toInt()
            ProxySettings(ProxyType.SOCKS5, host, port, "tsnet", password)
        } catch (e: Exception) {
            Log.w(TAG, "Tailnet start failed", e)
            _status.value = TailnetStatus(available = true, state = "Error", error = e.cause?.message ?: e.message)
            return
        }
        Log.i(TAG, "SOCKS5 listening ${System.currentTimeMillis() - startedAt} ms after the start")
        listening = socks
        poll(socks, startedAt)
    }

    fun stop() {
        scope.launch {
            lock.withLock {
                poller?.cancel()
                engine?.stop()
                current = null
                listening = null
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

    /** Follows the state of the node and hands out [socks] the first time it runs. */
    private fun poll(socks: ProxySettings, startedAt: Long) {
        poller?.cancel()
        poller = scope.launch(io) {
            while (isActive) {
                // A blocking call: a cancel does not end it, so its answer may come after the node changed.
                val status = parse(engine?.status().orEmpty())
                lock.withLock {
                    // A start or stop replaced the node this poller follows: its answer is stale.
                    if (listening !== socks) return@launch
                    _status.value = status
                    if (status.running && _proxy.value == null) {
                        _proxy.value = socks
                        Log.i(TAG, "Node running ${System.currentTimeMillis() - startedAt} ms after the start")
                    }
                }
                delay(if (status.running) 15_000 else 1_500)
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
    private class GoEngine(private val start: Method, private val stop: Method, private val status: Method, private val logs: Method) : TailnetEngine {
        override fun start(dir: String, control: String, key: String, host: String): String = start.invoke(null, dir, control, key, host) as String
        override fun stop() {
            stop.invoke(null)
        }
        override fun status(): String = status.invoke(null) as String
        override fun logs(): String = logs.invoke(null) as String

        companion object {
            fun load(): GoEngine? = try {
                val cls = Class.forName("org.evsyukov.hermes.tailnet.go.tailnet.Tailnet")
                val s = String::class.java
                GoEngine(cls.getMethod("start", s, s, s, s), cls.getMethod("stop"), cls.getMethod("status"), cls.getMethod("logs"))
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
