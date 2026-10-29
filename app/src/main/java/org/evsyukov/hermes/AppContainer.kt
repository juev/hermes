package org.evsyukov.hermes

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.evsyukov.hermes.background.ActivityCheck
import org.evsyukov.hermes.background.Notifications
import org.evsyukov.hermes.background.TurnWatcher
import org.evsyukov.hermes.chat.LocalPins
import org.evsyukov.hermes.data.auth.KeystoreTokenCipher
import org.evsyukov.hermes.data.cache.CacheDatabase
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.files.AttachmentCache
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.remote.SocketState
import org.evsyukov.hermes.data.server.ProxyType
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.ServerStore
import org.evsyukov.hermes.data.server.baseClient
import org.evsyukov.hermes.data.server.clientFor
import org.evsyukov.hermes.data.settings.AppSettings
import org.evsyukov.hermes.push.PushManager
import org.evsyukov.hermes.push.PushMessages
import org.evsyukov.hermes.push.UnifiedPushDistributor
import org.evsyukov.hermes.tailnet.Tailnet
import java.io.File

/** The server the app works with now, its HTTP routes and its chat socket. */
class Connection(val session: ServerSession, val socket: GatewaySocket)

/** Objects shared by the whole app. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = baseClient()

    val strings = AppStrings(context.resources)
    val settings = AppSettings(context, scope)
    val servers = ServerStore(context, KeystoreTokenCipher(), scope)
    val tailnet = Tailnet(context, scope)
    val attachments = AttachmentCache(File(context.cacheDir, "attachments"))
    val responses = ResponseCache(CacheDatabase.open(context).responses(), scope, { settings.cacheLimitMb.value?.let { it * MEGABYTE } })
    val pins = LocalPins(context)

    /**
     * Rebuilt when the active server, its address or its proxy changes; tokens alone do not
     * rebuild it, since the session reads them from the store on every request.
     */
    val connection: StateFlow<Connection?> = combine(servers.state.map { it.active }.distinctUntilChanged(), tailnet.proxy) { profile, tailnetProxy ->
        profile to tailnetProxy
    }
        .distinctUntilChanged()
        .runningFold(null as Connection?) { previous, (profile, tailnetProxy) ->
            previous?.socket?.stop()
            if (profile?.url == null) return@runningFold null
            val client = clientFor(http, profile.proxy, tailnetProxy)
            val session = ServerSession(profile, client, servers, strings, responses)
            Connection(session, GatewaySocket(session.http, scope, strings))
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val notifications = Notifications(context)
    val turns = TurnWatcher(context, scope, connection, notifications, settings.keepConnected)
    val activity = ActivityCheck(context)
    val push = PushManager(context, UnifiedPushDistributor(context), { connection.value?.session }, { settings.current.value.push })
    val pushMessages = PushMessages(this)

    private val _online = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits when a network becomes the default one, e.g. when the phone is back online. */
    val online: SharedFlow<Unit> = _online.asSharedFlow()

    init {
        scope.launch {
            settings.current.map { it.notifyActivity to it.checkMinutes }.distinctUntilChanged().collect { (on, minutes) ->
                if (on) ActivityCheck.schedule(context, minutes) else ActivityCheck.cancel(context)
            }
        }
        // The screens open the socket only while the app is in front; a permanent connection opens
        // every connection itself, also one built while only the service runs. A connection replaced
        // meanwhile has its socket stopped, and a stopped socket does not open again.
        scope.launch {
            connection.keptOpen(settings.keepConnected.filterNotNull()).collect { it.socket.start() }
        }
        // For the log: how long after the start of the process the first socket could take calls.
        scope.launch {
            connection.filterNotNull().flatMapLatest { it.socket.state }.first { it == SocketState.READY }
            Log.i(TIMING_TAG, "Gateway ready ${SystemClock.uptimeMillis() - Process.getStartUptimeMillis()} ms after the app started")
        }
        // A network that came back or changed is tried at once, not after the pause of the socket.
        context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connection.value?.socket?.checkAlive()
                    _online.tryEmit(Unit)
                }
            },
        )
        // With a permanent connection the gateway's change signal replaces the periodic check.
        scope.launch {
            connection.filterNotNull().flatMapLatest { c -> c.socket.events.map { c to it } }
                .filter { (_, event) -> event.type == "sessions.changed" }
                .debounce(2_000)
                .collect { (c, _) ->
                    val current = settings.current.value
                    if (current.keepConnected && current.notifyActivity && !turns.inForeground) {
                        runCatching { activity.run(c.session, notifications, turns.visibleChat) }
                    }
                }
        }
        // A new server or the push switch registers again; a sign-in does it through MainViewModel.
        scope.launch {
            combine(connection.map { it?.session?.profile?.id }.distinctUntilChanged(), settings.current.map { it.push }.distinctUntilChanged()) { _, _ -> }
                .collect { runCatching { push.reconcile() } }
        }
        // A lowered limit evicts at once, not with the next write.
        scope.launch {
            settings.cacheLimitMb.filterNotNull().distinctUntilChanged().collect { runCatching { responses.trim() } }
        }
        // The node runs only while the active server goes through it.
        scope.launch {
            servers.state.map { it.active }.distinctUntilChanged().collect { profile ->
                if (profile?.proxy?.type == ProxyType.TAILSCALE) tailnet.ensure(profile.tailnet) else tailnet.stop()
            }
        }
    }
}

private const val MEGABYTE = 1_048_576L

/** The log tag of the timings of the connection and of the first reply. */
const val TIMING_TAG = "HermesTiming"

/** Each connection that must stay open: the current one whenever [keep] is on. */
internal fun <T : Any> Flow<T?>.keptOpen(keep: Flow<Boolean>): Flow<T> =
    combine(this, keep) { connection, on -> connection.takeIf { on } }.filterNotNull()
