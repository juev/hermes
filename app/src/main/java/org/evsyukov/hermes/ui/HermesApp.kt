package org.evsyukov.hermes.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.server.ProxySettings
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
import org.evsyukov.hermes.ui.sidebar.Sidebar
import org.evsyukov.hermes.ui.sidebar.SidebarActions
import org.evsyukov.hermes.ui.theme.ChatTheme
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.evsyukov.hermes.ui.viewer.ViewerScreen

@Composable
fun HermesApp(vm: MainViewModel) {
    val container = vm.container
    val settings by container.settings.current.collectAsState()
    val loaded by container.servers.loaded.collectAsState()
    val servers by container.servers.state.collectAsState()
    val draft by vm.draftServer.collectAsState()
    HermesTheme(settings.theme) {
        Box(Modifier.fillMaxSize().background(ChatTheme.colors.background)) {
            when {
                !loaded -> Unit
                draft != null -> {
                    BackHandler { vm.closeDraftServer() }
                    ServerEditScreen(container, id = null, onBack = vm::closeDraftServer, onSaved = vm::closeDraftServer, draft = draft)
                }
                servers.servers.isEmpty() -> ServerEditScreen(container, id = null, onBack = null, onSaved = {})
                else -> MainScreen(vm)
            }
        }
    }
}

@Composable
private fun MainScreen(vm: MainViewModel) {
    val container = vm.container
    val access by vm.access.collectAsState()
    val connection by vm.connection.collectAsState()
    val servers by container.servers.state.collectAsState()
    val settings by container.settings.current.collectAsState()
    val sidebar by vm.sidebar.collectAsState()
    val search by vm.search.collectAsState()
    val chat by vm.chat.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val socketState = connection?.socket?.state?.collectAsState()
    val top = vm.stack.lastOrNull()

    val notice by vm.notice.collectAsState()
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
            SearchScreen(search, vm::setQuery, onOpen = { vm.openChat(it) }, onBack = { vm.pop() })
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
            SettingsScreen(container, onBack = { vm.pop() }, onLicenses = { vm.push(Screen.Licenses) }, onSignOut = {
                scope.launch {
                    connection?.session?.signOut()
                    vm.pop()
                    vm.checkAccess()
                }
            })
            return
        }
        is Screen.Licenses -> {
            LicensesScreen(onBack = { vm.pop() })
            return
        }
        is Screen.ServerEdit -> {
            ServerEditScreen(container, top.id, onBack = { vm.pop() }, onSaved = { vm.pop() })
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
            SignInScreen(vm, servers.active?.name.orEmpty(), onEditServer = { vm.push(Screen.ServerEdit(servers.active?.id)) })
            return
        }
        Access.OFFLINE -> {
            OfflineScreen(
                error = vm.accessError.collectAsState().value,
                tailnetHint = servers.active?.proxy?.type == ProxyType.NONE,
                onRetry = vm::checkAccess,
                onEditServer = { vm.push(Screen.ServerEdit(servers.active?.id)) },
            )
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
        onSettings = { vm.push(Screen.Settings); closeDrawer() },
        onSwitchServer = { id -> scope.launch { container.servers.activate(id) }; closeDrawer() },
        onEditServer = { id -> vm.push(Screen.ServerEdit(id)); closeDrawer() },
    )

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = true,
        scrimColor = ChatTheme.colors.background.copy(alpha = 0.6f),
        drawerContent = {
            Sidebar(sidebar, chat?.state?.collectAsState()?.value?.storedId, servers.servers, servers.active, actions)
        },
    ) {
        val controller = chat
        if (controller == null || connection == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val c = connection!!
            ChatScreen(
                controller = controller,
                settings = settings,
                serverName = servers.active?.name ?: "Hermes",
                connected = socketState?.value != SocketState.DISCONNECTED || access != Access.OK,
                onMenu = { scope.launch { drawer.open() } },
                onNewChat = vm::newChat,
                openFile = { path -> vm.openServerFile(path, controller.state.value.storedId) },
                loadFile = { path -> vm.serverFile(path, controller.state.value.storedId) },
                loadModels = { c.session.admin.modelOptions() },
                transcribe = { audio, mime -> c.session.chat.transcribe(audio, mime) },
                openLocal = { attachment -> vm.openLocalFile(container.attachments.put(attachment.name, attachment.bytes), attachment.name, attachment.mime) },
                shared = vm.shared.collectAsState().value,
                onSharedTaken = vm::sharedTaken,
            )
        }
    }
}

@Composable
private fun SignInScreen(vm: MainViewModel, serverName: String, onEditServer: () -> Unit) {
    val colors = ChatTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val route = remember { vm.signInRoute() }
    var job by remember { mutableStateOf<Job?>(null) }
    var webUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // The system browser goes its own way; a server behind a proxy or the built-in tailnet signs in inside the app.
    fun start(inApp: Boolean) {
        error = null
        job = scope.launch {
            try {
                vm.signIn { url ->
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
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.setup_signin_title, serverName), color = colors.text, fontSize = 24.sp)
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
            Text(it, color = colors.danger, textAlign = TextAlign.Center)
        }
        TextButton(onClick = onEditServer) { Text(stringResource(R.string.setup_server_settings)) }
    }
}

@Composable
private fun OfflineScreen(error: String?, tailnetHint: Boolean, onRetry: () -> Unit, onEditServer: () -> Unit) {
    val colors = ChatTheme.colors
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.setup_offline_title), color = colors.text, fontSize = 24.sp)
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
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = colors.contrast, contentColor = colors.onContrast)) { Text(stringResource(R.string.setup_retry)) }
        OutlinedButton(onClick = onEditServer) { Text(stringResource(R.string.setup_server_settings)) }
    }
}
