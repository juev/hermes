package org.evsyukov.hermes.ui

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.evsyukov.hermes.AppContainer
import org.evsyukov.hermes.Connection
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.ChatController
import org.evsyukov.hermes.chat.ChatMutations
import org.evsyukov.hermes.chat.withLocalPins
import org.evsyukov.hermes.data.auth.CallbackPageText
import org.evsyukov.hermes.data.remote.ChatApi
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.data.remote.HermesException
import org.evsyukov.hermes.data.remote.SearchHit
import org.evsyukov.hermes.data.remote.SignInRequiredException
import org.evsyukov.hermes.data.server.ProxySettings
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.parseServerLink
import org.evsyukov.hermes.data.server.ProxyType
import org.evsyukov.hermes.data.server.effectiveRoute
import org.evsyukov.hermes.ui.admin.AdminSection
import org.evsyukov.hermes.ui.viewer.ViewerFile
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Screens above the chat; the chat itself is the bottom of the stack. */
sealed interface Screen {
    data object Search : Screen
    data class Admin(val section: AdminSection? = null) : Screen
    data object Settings : Screen
    data object Licenses : Screen
    data class ServerEdit(val id: String?) : Screen
    data class Viewer(val file: ViewerFile) : Screen
}

/** What the sidebar lists: conversations, the archive, or the runs of scheduled jobs. */
enum class ListMode(val archived: Boolean, val sources: String?, val excludeSources: String?) {
    CHATS(false, null, "cron"),
    ARCHIVE(true, null, null),
    CRON(false, "cron", null),
    ;

    /** Whether [chat] belongs to this list, as the server filters it. */
    fun admits(chat: ChatSummary): Boolean =
        chat.archived == archived &&
            (sources == null || chat.source in sources.split(',')) &&
            (excludeSources == null || chat.source !in excludeSources.split(','))
}

data class SidebarState(
    val chats: List<ChatSummary> = emptyList(),
    val total: Int = 0,
    /** How many chats the pages of the server brought: [chats] also holds those pinned on the phone. */
    val listed: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val mode: ListMode = ListMode.CHATS,
)

data class SearchState(val query: String = "", val hits: List<SearchHit> = emptyList(), val searching: Boolean = false, val error: String? = null)

/** What the app needs from the server before chatting: nothing, a sign-in, or a working network. */
enum class Access { UNKNOWN, OK, SIGN_IN, OFFLINE }

/**
 * Checks a server found offline again when the network comes back. A network that comes while a
 * check runs finds no offline server, so it is counted: the check that then fails runs once more.
 * Only networks bring checks, so a server that stays down is not polled, and a server that answered,
 * if only to ask for a sign-in, is not checked again.
 */
internal class OfflineRecovery(private val access: StateFlow<Access>, private val recheck: () -> Unit) {
    private val networks = AtomicLong()

    /** What a check remembers from its start. */
    fun checkStarting(): Long = networks.get()

    /** The check that started with [startedAt] found the server offline. */
    fun checkFailed(startedAt: Long) {
        if (networks.get() != startedAt) recheck()
    }

    suspend fun follow(online: Flow<Unit>) {
        online.collect {
            networks.incrementAndGet()
            if (access.value == Access.OFFLINE) recheck()
        }
    }
}

/**
 * Finds out whether the app may chat with the server and opens the socket once it may. Tokens kept on
 * the phone mostly still work, so with them the socket connects while the checks run, not after them,
 * and the first message finds it ready; a sign-in that turns out to be over stops the socket again.
 */
internal suspend fun checkServer(
    hasTokens: Boolean,
    authRequired: suspend () -> Boolean,
    signedIn: suspend () -> Boolean,
    openSocket: () -> Unit,
): Access {
    if (hasTokens) openSocket()
    val required = authRequired()
    if (required && (!hasTokens || !signedIn())) return Access.SIGN_IN
    openSocket()
    return Access.OK
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(val container: AppContainer) : ViewModel() {
    val stack = mutableStateListOf<Screen>()

    private val _sidebar = MutableStateFlow(SidebarState())
    val sidebar: StateFlow<SidebarState> = _sidebar.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _chat = MutableStateFlow<ChatController?>(null)
    val chat: StateFlow<ChatController?> = _chat.asStateFlow()

    private val _access = MutableStateFlow(Access.UNKNOWN)
    val access: StateFlow<Access> = _access.asStateFlow()

    private val _accessError = MutableStateFlow<String?>(null)
    val accessError: StateFlow<String?> = _accessError.asStateFlow()

    val connection: StateFlow<Connection?> get() = container.connection

    private var loadJob: Job? = null

    /** The server has answered for the list shown, so the copy on the phone must not replace it. */
    private var listedByServer = false

    /** The check of the server under way; a new one replaces it, so an older answer cannot win. */
    private var accessJob: Job? = null

    private val recovery = OfflineRecovery(access) { checkAccess() }

    /** A chat asked for before the server connection exists, e.g. by a notification at a cold start. */
    private var pendingChat: String? = null

    init {
        viewModelScope.launch {
            container.connection.collectLatest { connection ->
                _chat.value?.close()
                _chat.value = null
                _sidebar.value = SidebarState()
                listedByServer = false
                if (connection == null) return@collectLatest
                showCachedChats(connection)
                newChat()
                pendingChat?.let { id ->
                    pendingChat = null
                    openChat(id)
                }
                checkAccess()
            }
        }
        // The gateway tells when the list of chats changed anywhere: Telegram, the desktop, cron.
        viewModelScope.launch {
            container.connection.filterNotNull().flatMapLatest { it.socket.events }
                .filter { it.type == "sessions.changed" || it.type == "session.title" }
                .debounce(700)
                .collect { refreshChats() }
        }
        // A server found offline is checked again when the network comes back; the button stays for
        // the cases the network does not explain.
        viewModelScope.launch { recovery.follow(container.online) }
        viewModelScope.launch {
            _search.debounce(300).distinctUntilChanged { a, b -> a.query == b.query }.collectLatest { state ->
                runSearch(state.query)
            }
        }
    }

    /** Probes the server; a valid session keeps the socket open and loads the chats. */
    fun checkAccess() {
        val connection = container.connection.value ?: return
        accessJob?.cancel()
        _access.value = Access.UNKNOWN
        val since = recovery.checkStarting()
        accessJob = viewModelScope.launch {
            try {
                val hasTokens = container.servers.load().tokens[connection.session.profile.id] != null
                val access = checkServer(hasTokens, { connection.session.authInfo().required }, connection.session::probe, connection.socket::start)
                _access.value = access
                if (access != Access.OK) return@launch
                _accessError.value = null
                refreshChats()
                container.scope.launch { runCatching { container.push.reconcile() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignInRequiredException) {
                _access.value = Access.SIGN_IN
            } catch (e: Exception) {
                Log.w(TAG, "Server check failed", e)
                _accessError.value = e.message ?: e.javaClass.simpleName
                _access.value = Access.OFFLINE
                recovery.checkFailed(since)
            }
        }
    }

    private val _draftServer = MutableStateFlow<ServerProfile?>(null)

    /** A server from an `add-server` link, shown in the form until it is saved or dismissed. */
    val draftServer: StateFlow<ServerProfile?> = _draftServer.asStateFlow()

    /** Fills the server form from a link; a malformed link is reported, nothing else happens. */
    fun openServerLink(link: String) {
        val profile = parseServerLink(link)
        if (profile == null) _notice.value = container.strings.get(R.string.error_link_unreadable) else _draftServer.value = profile
    }

    fun closeDraftServer() {
        _draftServer.value = null
    }

    /** The route the in-app sign-in page must take; null when the server is reached directly. */
    fun signInRoute(): ProxySettings? {
        val profile = container.servers.state.value.active ?: return null
        if (profile.proxy.type == ProxyType.NONE) return null
        return effectiveRoute(profile.proxy, container.tailnet.proxy.value)
    }

    suspend fun signIn(openBrowser: (String) -> Unit) {
        val connection = container.connection.value ?: return
        val info = connection.session.authInfo()
        val page = CallbackPageText(
            signedIn = container.strings.get(R.string.setup_page_signed_in),
            failed = container.strings.get(R.string.setup_page_failed),
            back = container.strings.get(R.string.setup_page_back),
        )
        connection.session.signIn(info.providers.singleOrNull(), RETURN_URL, page, openBrowser)
        checkAccess()
    }

    fun refreshChats() {
        val connection = container.connection.value ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _sidebar.update { it.copy(loading = true, error = null) }
            try {
                val mode = _sidebar.value.mode
                val chat = connection.session.chat
                val page = chat.list(limit = PAGE, archived = mode.archived, sources = mode.sources, excludeSources = mode.excludeSources)
                val chats = withPins(connection, chat, page.chats, mode, forgetGone = true)
                _sidebar.update { it.copy(chats = chats, total = page.total, listed = page.chats.size, loading = false) }
                listedByServer = true
            } catch (e: Exception) {
                if (e is SignInRequiredException) _access.value = Access.SIGN_IN
                _sidebar.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    /**
     * [chats] with the chats pinned on the phone, those beyond the page included. A pinned chat the
     * server no longer has leaves the pins when [forgetGone]; the copy on the phone cannot tell that.
     */
    private suspend fun withPins(connection: Connection, api: ChatApi, chats: List<ChatSummary>, mode: ListMode, forgetGone: Boolean): List<ChatSummary> {
        val server = connection.session.profile.id
        return withLocalPins(chats, container.pins.of(server), mode::admits) { id ->
            try {
                api.get(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (forgetGone && e is HermesException && e.status == 404) container.pins.set(server, id, false)
                null
            }
        }
    }

    /** Shows the first page the phone keeps until the server answers, or when it cannot. */
    private fun showCachedChats(connection: Connection) {
        val cached = connection.session.chat.cached ?: return
        val mode = _sidebar.value.mode
        viewModelScope.launch {
            val page = runCatching {
                cached.list(limit = PAGE, archived = mode.archived, sources = mode.sources, excludeSources = mode.excludeSources)
            }.getOrNull() ?: return@launch
            val chats = withPins(connection, cached, page.chats, mode, forgetGone = false)
            if (!listedByServer && container.connection.value === connection) _sidebar.update { if (it.mode == mode) it.copy(chats = chats, total = page.total, listed = page.chats.size) else it }
        }
    }

    fun loadMoreChats() {
        val connection = container.connection.value ?: return
        val state = _sidebar.value
        if (state.loading || state.listed >= state.total) return
        viewModelScope.launch {
            _sidebar.update { it.copy(loading = true) }
            try {
                val mode = state.mode
                val page = connection.session.chat.list(offset = state.listed, limit = PAGE, archived = mode.archived, sources = mode.sources, excludeSources = mode.excludeSources)
                // The first page already brought every pinned chat, so the ones met again are left out.
                _sidebar.update { s -> s.copy(chats = (s.chats + page.chats).distinctBy { it.id }, total = page.total, listed = s.listed + page.chats.size, loading = false) }
            } catch (e: Exception) {
                _sidebar.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun showList(mode: ListMode) {
        if (_sidebar.value.mode == mode) return
        _sidebar.update { it.copy(mode = mode, chats = emptyList(), total = 0, listed = 0) }
        listedByServer = false
        container.connection.value?.let(::showCachedChats)
        refreshChats()
    }

    fun openChat(id: String) {
        val connection = container.connection.value
        if (connection == null) {
            pendingChat = id
            return
        }
        if (_chat.value?.state?.value?.storedId == id) return
        _chat.value?.close()
        _chat.value = controller(connection, id)
        container.notifications.cancelChat(id)
        stack.clear()
    }

    /** An empty chat, as the pencil button and the pull past the end give; it reaches the server with the first message. */
    fun newChat() {
        val connection = container.connection.value ?: return
        val current = _chat.value
        if (current != null && current.state.value.storedId == null && current.state.value.items.isEmpty()) return
        current?.close()
        _chat.value = controller(connection, null)
        stack.clear()
    }

    private fun controller(connection: Connection, id: String?): ChatController {
        container.turns.visibleChat = id
        return ChatController(
            connection.session, connection.socket, viewModelScope, container.strings, id,
            onSessionCreated = { stored ->
                container.turns.visibleChat = stored
                refreshChats()
            },
            onLive = container.turns::bind,
            onTurnStart = container.turns::turnStarting,
        )
    }

    fun renameChat(id: String, title: String) = mutate(id) { it.rename(id, title) }

    fun pinChat(id: String, pinned: Boolean) = mutate(id) { it.pin(id, pinned) }

    fun archiveChat(id: String, archived: Boolean) = mutate(id) { it.archive(id, archived) }

    fun deleteChat(id: String) = mutate(id) {
        it.delete(id)
        container.connection.value?.session?.forgetChat(id)
        if (_chat.value?.state?.value?.storedId == id) newChat()
    }

    private val _notice = MutableStateFlow<String?>(null)

    /** A one-line message for the user, such as a refused delete; the screen shows it once. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun noticeShown() {
        _notice.value = null
    }

    private fun mutate(id: String, action: suspend (ChatMutations) -> Unit) {
        val connection = container.connection.value ?: return
        viewModelScope.launch {
            try {
                val server = connection.session.profile.id
                action(ChatMutations(connection.session.chat, connection.socket, container.turns::liveOf) { chat, pinned -> container.pins.set(server, chat, pinned) })
                if (_chat.value?.state?.value?.storedId == id) _chat.value?.reload()
            } catch (e: Exception) {
                Log.w(TAG, "Change of chat $id failed", e)
                _notice.value = e.message ?: container.strings.get(R.string.error_failed)
            }
            refreshChats()
        }
    }

    fun setQuery(query: String) = _search.update { it.copy(query = query) }

    private suspend fun runSearch(query: String) {
        val connection = container.connection.value ?: return
        if (query.isBlank()) {
            _search.update { it.copy(hits = emptyList(), searching = false, error = null) }
            return
        }
        _search.update { it.copy(searching = true, error = null) }
        try {
            val hits = connection.session.chat.search(query)
            _search.update { if (it.query == query) it.copy(hits = hits, searching = false) else it }
        } catch (e: Exception) {
            _search.update { it.copy(searching = false, error = e.message) }
        }
    }

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Opens a file of the server in the viewer; it downloads into the cache first. */
    fun openServerFile(path: String, chatId: String? = null) {
        val connection = container.connection.value ?: return
        push(Screen.Viewer(ViewerFile(path.substringAfterLast('/'), null) { container.attachments.get(connection.session, path, chatId) }))
    }

    fun openLocalFile(file: File, name: String, mime: String?) {
        push(Screen.Viewer(ViewerFile(name, mime) { file }))
    }

    suspend fun serverFile(path: String, chatId: String? = null): File? {
        val connection = container.connection.value ?: return null
        return try {
            container.attachments.get(connection.session, path, chatId)
        } catch (e: Exception) {
            null
        }
    }

    /** Waits until the socket is up, for screens that call the gateway. */
    suspend fun awaitSocket() {
        while (container.connection.value == null) delay(100)
    }

    override fun onCleared() {
        _chat.value?.close()
    }

    companion object {
        const val PAGE = 50
        const val RETURN_URL = "org.evsyukov.hermes://signed-in"
        private const val TAG = "HermesMain"
    }
}
