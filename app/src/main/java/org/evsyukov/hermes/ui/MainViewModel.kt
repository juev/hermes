package org.evsyukov.hermes.ui

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import org.evsyukov.hermes.chat.ChatController
import org.evsyukov.hermes.chat.ChatMutations
import org.evsyukov.hermes.data.remote.ChatSummary
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

/** Screens above the chat; the chat itself is the bottom of the stack. */
sealed interface Screen {
    data object Search : Screen
    data class Admin(val section: AdminSection? = null) : Screen
    data object Settings : Screen
    data class ServerEdit(val id: String?) : Screen
    data class Viewer(val file: ViewerFile) : Screen
}

/** What the sidebar lists: conversations, the archive, or the runs of scheduled jobs. */
enum class ListMode(val archived: Boolean, val sources: String?, val excludeSources: String?) {
    CHATS(false, null, "cron"),
    ARCHIVE(true, null, null),
    CRON(false, "cron", null),
}

data class SidebarState(
    val chats: List<ChatSummary> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val mode: ListMode = ListMode.CHATS,
)

data class SearchState(val query: String = "", val hits: List<SearchHit> = emptyList(), val searching: Boolean = false, val error: String? = null)

/** What the app needs from the server before chatting: nothing, a sign-in, or a working network. */
enum class Access { UNKNOWN, OK, SIGN_IN, OFFLINE }

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

    /** A chat asked for before the server connection exists, e.g. by a notification at a cold start. */
    private var pendingChat: String? = null

    init {
        viewModelScope.launch {
            container.connection.collectLatest { connection ->
                _chat.value?.close()
                _chat.value = null
                _sidebar.value = SidebarState()
                if (connection == null) return@collectLatest
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
        viewModelScope.launch {
            _search.debounce(300).distinctUntilChanged { a, b -> a.query == b.query }.collectLatest { state ->
                runSearch(state.query)
            }
        }
    }

    /** Probes the server; a valid session opens the socket and loads the chats. */
    fun checkAccess() {
        val connection = container.connection.value ?: return
        viewModelScope.launch {
            _access.value = Access.UNKNOWN
            try {
                val info = connection.session.authInfo()
                val hasTokens = container.servers.load().tokens[connection.session.profile.id] != null
                if (info.required && !hasTokens) {
                    _access.value = Access.SIGN_IN
                    return@launch
                }
                if (info.required && !connection.session.probe()) {
                    _access.value = Access.SIGN_IN
                    return@launch
                }
                _access.value = Access.OK
                _accessError.value = null
                connection.socket.start()
                refreshChats()
            } catch (e: SignInRequiredException) {
                _access.value = Access.SIGN_IN
            } catch (e: Exception) {
                Log.w(TAG, "Server check failed", e)
                _accessError.value = e.message ?: e.javaClass.simpleName
                _access.value = Access.OFFLINE
            }
        }
    }

    private val _draftServer = MutableStateFlow<ServerProfile?>(null)

    /** A server from an `add-server` link, shown in the form until it is saved or dismissed. */
    val draftServer: StateFlow<ServerProfile?> = _draftServer.asStateFlow()

    /** Fills the server form from a link; a malformed link is reported, nothing else happens. */
    fun openServerLink(link: String) {
        val profile = parseServerLink(link)
        if (profile == null) _notice.value = "Ссылка на сервер не разобрана" else _draftServer.value = profile
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
        connection.session.signIn(info.providers.singleOrNull(), RETURN_URL, openBrowser)
        checkAccess()
    }

    fun refreshChats() {
        val connection = container.connection.value ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _sidebar.update { it.copy(loading = true, error = null) }
            try {
                val mode = _sidebar.value.mode
                val page = connection.session.chat.list(limit = PAGE, archived = mode.archived, sources = mode.sources, excludeSources = mode.excludeSources)
                _sidebar.update { it.copy(chats = page.chats, total = page.total, loading = false) }
            } catch (e: Exception) {
                if (e is SignInRequiredException) _access.value = Access.SIGN_IN
                _sidebar.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun loadMoreChats() {
        val connection = container.connection.value ?: return
        val state = _sidebar.value
        if (state.loading || state.chats.size >= state.total) return
        viewModelScope.launch {
            _sidebar.update { it.copy(loading = true) }
            try {
                val mode = state.mode
                val page = connection.session.chat.list(offset = state.chats.size, limit = PAGE, archived = mode.archived, sources = mode.sources, excludeSources = mode.excludeSources)
                _sidebar.update { s -> s.copy(chats = (s.chats + page.chats).distinctBy { it.id }, total = page.total, loading = false) }
            } catch (e: Exception) {
                _sidebar.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun showList(mode: ListMode) {
        if (_sidebar.value.mode == mode) return
        _sidebar.update { it.copy(mode = mode, chats = emptyList(), total = 0) }
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
            connection.session, connection.socket, viewModelScope, id,
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
                action(ChatMutations(connection.session.chat, connection.socket, container.turns::liveOf))
                if (_chat.value?.state?.value?.storedId == id) _chat.value?.reload()
            } catch (e: Exception) {
                Log.w(TAG, "Change of chat $id failed", e)
                _notice.value = e.message ?: "Не получилось"
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
