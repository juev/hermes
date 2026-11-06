package org.evsyukov.hermes.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.evsyukov.hermes.Connection
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.server.ProxySettings
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.ui.setup.WebSignIn
import org.evsyukov.hermes.data.remote.SocketState
import org.evsyukov.hermes.data.server.ProxyType
import org.evsyukov.hermes.data.remote.ConnectorsApi
import org.evsyukov.hermes.ui.admin.AdminScreen
import org.evsyukov.hermes.ui.chat.ChatScreen
import org.evsyukov.hermes.ui.search.SearchScreen
import org.evsyukov.hermes.ui.settings.LicensesScreen
import org.evsyukov.hermes.ui.settings.SettingsScreen
import org.evsyukov.hermes.ui.setup.ServerEditScreen
import org.evsyukov.hermes.ui.sidebar.ServerFooter
import org.evsyukov.hermes.ui.sidebar.Sidebar
import org.evsyukov.hermes.ui.sidebar.SidebarActions
import org.evsyukov.hermes.ui.theme.ChatTheme
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.evsyukov.hermes.ui.viewer.ViewerScreen

@Composable
fun HermesApp(vm: MainViewModel) {
    val container = vm.container
    val settings by container.settings.current.collectAsStateWithLifecycle()
    val loaded by container.servers.loaded.collectAsStateWithLifecycle()
    val servers by container.servers.state.collectAsStateWithLifecycle()
    val draft by vm.draftServer.collectAsStateWithLifecycle()
    HermesTheme(settings.theme) {
        Box(Modifier.fillMaxSize().background(ChatTheme.colors.background)) {
            when {
                !loaded -> Unit
                draft != null -> {
                    BackHandler { vm.closeDraftServer() }
                    ServerEditScreen(container, id = null, onBack = vm::closeDraftServer, onSaved = vm::closeDraftServer, onRemove = vm::removeServer, prepareRemoval = vm::prepareRemoval, draft = draft)
                }
                servers.servers.isEmpty() -> ServerEditScreen(container, id = null, onBack = null, onSaved = {}, onRemove = vm::removeServer, prepareRemoval = vm::prepareRemoval)
                else -> MainScreen(vm)
            }
        }
    }
}

@Composable
private fun MainScreen(vm: MainViewModel) {
    val container = vm.container
    val access by vm.access.collectAsStateWithLifecycle()
    val accessError by vm.accessError.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val servers by container.servers.state.collectAsStateWithLifecycle()
    val settings by container.settings.current.collectAsStateWithLifecycle()
    val sidebar by vm.sidebar.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val socketState = connection?.socket?.state?.collectAsStateWithLifecycle()
    val top = vm.stack.lastOrNull()

    val notice by vm.notice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.noticeShown()
        }
    }

    // Opening the drawer puts the keyboard away, so the list is not half hidden behind it.
    val focus = LocalFocusManager.current
    LaunchedEffect(drawer.targetValue) { if (drawer.targetValue == DrawerValue.Open) focus.clearFocus() }

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && top != null) { vm.pop() }

    fun closeDrawer() = scope.launch { drawer.close() }

    when (top) {
        is Screen.Search -> {
            SearchScreen(search, vm::setQuery, onOpen = vm::openSearchHit, onBack = { vm.pop() })
            return
        }
        is Screen.Admin -> {
            val c = connection ?: return
            AdminScreen(
                c.session.admin, remember(c) { ConnectorsApi(c.socket) }, top.section,
                onOpen = { vm.push(Screen.Admin(it)) }, onBack = { vm.pop() },
                onMainModelChanged = vm::mainModelChanged,
            )
            return
        }
        is Screen.Settings -> {
            SettingsScreen(container, onBack = { vm.pop() }, onLicenses = { vm.push(Screen.Licenses) }, onSignOut = vm::signOut, prepareSignOut = vm::prepareSignOut)
            return
        }
        is Screen.Licenses -> {
            LicensesScreen(onBack = { vm.pop() })
            return
        }
        is Screen.ServerEdit -> {
            ServerEditScreen(container, top.id, onBack = { vm.pop() }, onSaved = { vm.pop() }, onRemove = vm::removeServer, prepareRemoval = vm::prepareRemoval)
            return
        }
        is Screen.Viewer -> {
            ViewerScreen(top.file, onBack = { vm.pop() })
            return
        }
        null -> Unit
    }

    when (access) {
        Access.SIGN_IN -> {
            val current = connection
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return
            }
            // The route, the browser page, the job and the error belong to this server alone.
            key(current) { SignInScreen(vm, current, servers.servers) }
            return
        }
        Access.OFFLINE -> {
            val current = connection
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return
            }
            key(current) {
                OfflineScreen(
                    vm = vm,
                    connection = current,
                    servers = servers.servers,
                    error = accessError,
                    tailnetHint = current.session.profile.proxy.type == ProxyType.NONE,
                )
            }
            return
        }
        Access.UNKNOWN -> if (chat == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        Access.OK -> Unit
    }

    val actions = SidebarActions(
        onSearch = { vm.push(Screen.Search); closeDrawer() },
        onNewChat = { vm.newChat(); closeDrawer() },
        onOpen = { vm.openChat(it); closeDrawer() },
        onRename = vm::renameChat,
        onPin = vm::pinChat,
        onArchive = vm::archiveChat,
        onDelete = vm::deleteChat,
        onLoadMore = vm::loadMoreChats,
        onRefresh = vm::refreshChats,
        onShowList = vm::showList,
        onAdmin = { vm.push(Screen.Admin()); closeDrawer() },
        onSettings = { vm.pushSettings(); closeDrawer() },
        onSwitchServer = { id -> vm.switchServer(id); closeDrawer() },
        onEditServer = { id -> vm.push(Screen.ServerEdit(id)); closeDrawer() },
    )

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = true,
        scrimColor = ChatTheme.colors.background.copy(alpha = 0.6f),
        drawerContent = {
            // The drawer is composed while closed too: it follows the id of the chat, not each step of its answer.
            val currentChatId by remember(chat) { chat?.state?.map { it.storedId }?.distinctUntilChanged() ?: flowOf(null) }.collectAsStateWithLifecycle(null)
            Sidebar(sidebar, currentChatId, servers.servers, servers.active, actions)
        },
    ) {
        val controller = chat
        val draft = controller?.let { vm.draftOf(it, connection) }
        if (controller == null || connection == null || draft == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val c = connection!!
            ChatScreen(
                controller = controller,
                settings = settings,
                serverName = servers.active?.name ?: "Hermes",
                connected = socketState?.value != SocketState.DISCONNECTED || access != Access.OK,
                onMenu = { scope.launch { drawer.open() } },
                onNewChat = { vm.newChat() },
                openFile = { path -> vm.openServerFile(path, controller.state.value.storedId) },
                loadFile = { path -> vm.serverFile(path, controller.state.value.storedId) },
                loadModels = { c.session.admin.modelOptions() },
                openLocal = { attachment -> vm.openLocalFile(container.attachments.put(attachment.name, attachment.bytes), attachment.name, attachment.mime) },
                onShown = vm::chatShown,
                draft = draft,
                shared = vm.shared.collectAsStateWithLifecycle().value,
                onSharedTaken = vm::sharedTaken,
            )
        }
    }
}

/**
 * The recovery screens: the server on them does not work, so the way to another saved server and to
 * the local settings stays on the screen, under the content and outside its scroll.
 */
@Composable
private fun RecoveryScaffold(
    vm: MainViewModel,
    connection: Connection,
    servers: List<ServerProfile>,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
        HorizontalDivider(color = ChatTheme.colors.divider)
        ServerFooter(
            servers = servers,
            active = connection.session.profile,
            onSwitchServer = vm::switchServer,
            onEditServer = { vm.push(Screen.ServerEdit(it)) },
            onSettings = { vm.pushSettings() },
        )
    }
}

@Composable
private fun SignInScreen(vm: MainViewModel, connection: Connection, servers: List<ServerProfile>) {
    val colors = ChatTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = connection.session.profile
    val route = remember { vm.signInRoute(profile) }
    var job by remember { mutableStateOf<Job?>(null) }
    var webUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // The system browser goes its own way; a server behind a proxy or the built-in tailnet signs in inside the app.
    fun start(inApp: Boolean) {
        error = null
        val signIn = vm.captureSignIn()
        job = scope.launch {
            try {
                signIn { url ->
                    if (inApp) webUrl = url else CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                webUrl = null
                job = null
            }
        }
    }

    val url = webUrl
    if (url != null) {
        WebSignIn(url, route ?: ProxySettings(), onCancel = { job?.cancel() })
        return
    }
    RecoveryScaffold(vm, connection, servers) {
        Text(stringResource(R.string.setup_signin_title, profile.name), color = colors.text, fontSize = 24.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (route == null) R.string.setup_signin_browser_hint else R.string.setup_signin_inapp_hint),
            color = colors.secondaryText, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            enabled = job == null,
            colors = ButtonDefaults.buttonColors(containerColor = colors.contrast, contentColor = colors.onContrast),
            onClick = { start(inApp = route != null) },
        ) { if (job != null) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.setup_signin)) }
        TextButton(enabled = job == null, onClick = { start(inApp = route == null) }) {
            Text(stringResource(if (route == null) R.string.setup_signin_inapp else R.string.setup_signin_browser))
        }
        if (job != null) TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.setup_signin_cancel)) }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = colors.dangerText, textAlign = TextAlign.Center)
        }
        TextButton(onClick = { vm.push(Screen.ServerEdit(profile.id)) }) { Text(stringResource(R.string.setup_server_settings)) }
    }
}

@Composable
private fun OfflineScreen(
    vm: MainViewModel,
    connection: Connection,
    servers: List<ServerProfile>,
    error: String?,
    tailnetHint: Boolean,
) {
    val colors = ChatTheme.colors
    val context = LocalContext.current
    RecoveryScaffold(vm, connection, servers) {
        Text(stringResource(R.string.setup_offline_title), color = colors.text, fontSize = 24.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(error.orEmpty(), color = colors.secondaryText, textAlign = TextAlign.Center)
        if (tailnetHint) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.setup_offline_hint),
                color = colors.faintText, textAlign = TextAlign.Center, fontSize = 14.sp,
            )
            TextButton(onClick = {
                val intent = context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.tailscale.ipn"))
                context.startActivity(intent)
            }) { Text(stringResource(R.string.setup_open_tailscale)) }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = vm::checkAccess, colors = ButtonDefaults.buttonColors(containerColor = colors.contrast, contentColor = colors.onContrast)) { Text(stringResource(R.string.setup_retry)) }
        OutlinedButton(onClick = { vm.push(Screen.ServerEdit(connection.session.profile.id)) }) { Text(stringResource(R.string.setup_server_settings)) }
    }
}
