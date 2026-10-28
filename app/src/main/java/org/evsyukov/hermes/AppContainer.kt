package org.evsyukov.hermes

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.evsyukov.hermes.background.ActivityCheck
import org.evsyukov.hermes.background.HermesService
import org.evsyukov.hermes.background.Notifications
import org.evsyukov.hermes.background.TurnWatcher
import org.evsyukov.hermes.data.auth.KeystoreTokenCipher
import org.evsyukov.hermes.data.files.AttachmentCache
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.server.ProxyType
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.ServerStore
import org.evsyukov.hermes.data.server.baseClient
import org.evsyukov.hermes.data.server.clientFor
import org.evsyukov.hermes.data.settings.AppSettings
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
            val session = ServerSession(profile, client, servers, strings)
            Connection(session, GatewaySocket(session.http, scope, strings))
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val notifications = Notifications(context)
    val turns = TurnWatcher(context, scope, connection, notifications) { settings.current.value.keepConnected }
    val activity = ActivityCheck(context)

    init {
        scope.launch {
            settings.current.map { it.notifyActivity to it.checkMinutes }.distinctUntilChanged().collect { (on, minutes) ->
                if (on) ActivityCheck.schedule(context, minutes) else ActivityCheck.cancel(context)
            }
        }
        scope.launch {
            settings.current.map { it.keepConnected }.distinctUntilChanged().collect { keep ->
                if (keep) HermesService.start(context, context.getString(R.string.notify_connected)) else turns.settle()
            }
        }
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
        // The node runs only while the active server goes through it.
        scope.launch {
            servers.state.map { it.active }.distinctUntilChanged().collect { profile ->
                if (profile?.proxy?.type == ProxyType.TAILSCALE) tailnet.ensure(profile.tailnet) else tailnet.stop()
            }
        }
    }
}
