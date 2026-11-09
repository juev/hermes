package org.evsyukov.hermes.ui

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evsyukov.hermes.AppContainer
import org.evsyukov.hermes.Connection
import org.evsyukov.hermes.R
import org.evsyukov.hermes.background.TurnHold
import org.evsyukov.hermes.chat.ChatController
import org.evsyukov.hermes.chat.ChatMutations
import org.evsyukov.hermes.chat.withLocalPins
import org.evsyukov.hermes.data.auth.CallbackPageText
import org.evsyukov.hermes.data.remote.ChatApi
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.data.remote.HermesException
import org.evsyukov.hermes.data.remote.SearchHit
import org.evsyukov.hermes.data.remote.SignInRequiredException
import org.evsyukov.hermes.data.server.NativeResult
import org.evsyukov.hermes.data.server.ProxySettings
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.WorkOwner
import org.evsyukov.hermes.data.server.parseServerLink
import org.evsyukov.hermes.data.server.ProxyType
import org.evsyukov.hermes.data.server.effectiveRoute
import org.evsyukov.hermes.ui.admin.AdminSection
import org.evsyukov.hermes.ui.chat.ChatDraft
import org.evsyukov.hermes.ui.chat.ChatDraftMemory
import org.evsyukov.hermes.ui.chat.DraftKey
import org.evsyukov.hermes.ui.chat.Shared
import org.evsyukov.hermes.ui.viewer.ViewerFile
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/** Screens above the chat; the chat itself is the bottom of the stack. */
sealed interface Screen {
    data object Search : Screen
    data class Admin(val section: AdminSection? = null) : Screen
    data class Settings(val visit: Long) : Screen
    data object Licenses : Screen
    data class ServerEdit(val id: String?) : Screen
    data class Viewer(val file: ViewerFile) : Screen
}

/**
 * What the sidebar lists: conversations, the archive, or the runs of scheduled jobs. The service runs
 * of the dashboard (one-shot questions, tools, API and agent clients, webhooks) are in no list; a
 * Kanban worker is a service run too. Unknown client names stay, so a new interactive client appears
 * without an app change; a new service name needs to join [CHATS]'s exclusions.
 */
enum class ListMode(val archived: Boolean, val sources: String?, val excludeSources: String?) {
    CHATS(false, null, "cron,kanban,oneshot,tool,api_server,acp,hermes_flow,vulcan_delegate,webhook"),
    ARCHIVE(true, null, null),
    CRON(false, "cron", null),
    ;

    /**
     * Whether [chat] belongs to this list, as the server filters it. A local row carries its creator
     * in [ChatSummary.createdSource], so a service that ran through a local session is left out even
     * though the live source is `local`; a row without that provenance is kept.
     */
    fun admits(chat: ChatSummary): Boolean {
        val excluded = excludeSources?.split(',') ?: emptyList()
        val serviceCreator = chat.createdSource != null && chat.createdSource in excluded
        return chat.archived == archived &&
            (sources == null || chat.source in sources.split(',')) &&
            (excludeSources == null || (chat.source !in excluded && !serviceCreator))
    }

    /** The rows of a page that belong here: a server that ignores a filter puts no run among the chats. */
    fun keep(chats: List<ChatSummary>): List<ChatSummary> = chats.filter(::admits)
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

/**
 * [more] chats of [mode] after the pages listed so far; [size] is what the server listed, so the next
 * page starts after it. A page of a list no longer shown changes nothing.
 */
internal fun SidebarState.withMore(mode: ListMode, more: List<ChatSummary>, total: Int, size: Int): SidebarState =
    if (this.mode != mode) this else copy(chats = (chats + mode.keep(more)).distinctBy { it.id }, total = total, listed = listed + size, loading = false)

/**
 * The search a result was opened from, kept while its conversation is on the screen so Back can
 * show the same query, results and list place. It belongs to one request: a replaced connection or a
 * new attempt retires it rather than restoring results of another server.
 */
data class SearchJourney(
    val request: SearchRequestIdentity,
    val chatId: String,
    val index: Int,
    val offset: Int,
)

/**
 * How the search screen opens: the place in its list, and whether it may take the keyboard. A
 * return from a result comes back without asking for focus, so the keyboard does not cover it again.
 */
data class SearchEntry(
    val index: Int = 0,
    val offset: Int = 0,
    val requestFocus: Boolean = true,
)

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

enum class LocalSignOutResult { COMPLETE, INCOMPLETE, SUPERSEDED, REJECTED, FAILED }

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(
    val container: AppContainer,
    /** Looks the query up; a test controls the answers without a server. */
    searchChats: suspend (Connection, String) -> List<SearchHit> = { connection, query ->
        connection.session.chat.search(query)
    },
) : ViewModel() {
    val stack = mutableStateListOf<Screen>()

    private val _sidebar = MutableStateFlow(SidebarState())
    val sidebar: StateFlow<SidebarState> = _sidebar.asStateFlow()

    private val searchOwner = SearchOwner(container.connection, viewModelScope, searchChats)
    val search: StateFlow<SearchState> = searchOwner.search

    /** The result journey under the chat on the screen; null when Back is ordinary. */
    private val _searchJourney = MutableStateFlow<SearchJourney?>(null)
    val searchJourney: StateFlow<SearchJourney?> = _searchJourney.asStateFlow()

    /** Where the search screen opens: its list place and whether it takes the keyboard. */
    private val _searchEntry = MutableStateFlow(SearchEntry())
    val searchEntry: StateFlow<SearchEntry> = _searchEntry.asStateFlow()

    /** The list place the open Search screen showed last, read when a result is activated. */
    private var searchListIndex = 0
    private var searchListOffset = 0

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
    private var accessOwner: WorkOwner? = null
    private var accessConnection: Connection? = null

    /** Actual producer handles for deterministic owner-boundary fixtures. */
    internal val accessProducer: Job? get() = accessJob
    @Volatile
    internal var beforeAccessStoreRead: (suspend () -> Unit)? = null
    @Volatile
    internal var afterSignoutCleanup: (suspend () -> Unit)? = null
    @Volatile internal var beforeChatDeleteAdmission: (suspend () -> Unit)? = null
    internal val listProducer: Job? get() = loadJob
    internal var cachedListProducer: Job? = null
        private set
    @Volatile private var observedreadProducers: java.util.concurrent.CopyOnWriteArrayList<Job>? = null
    internal val readProducers: java.util.concurrent.CopyOnWriteArrayList<Job>
        get() = synchronized(this) {
            observedreadProducers ?: java.util.concurrent.CopyOnWriteArrayList<Job>().also { observedreadProducers = it }
        }
    internal var mutationProducer: Job? = null
        private set

    /** Exact initial application boundary; absent in app use. */
    @Volatile internal var beforeInitialConnection: (suspend (Connection) -> Unit)? = null
    @Volatile internal var beforeInitialOwnerAdmission: (suspend (Connection, WorkOwner) -> Unit)? = null
    // Supplemental initial-owner decision observation; absent in app use.
    @Volatile internal var afterInitialOwnerDecision: ((Connection, WorkOwner, WorkOwner?, Job) -> Unit)? = null
    /** Stored-controller construction boundary, outside the lifetime publication guard. */
    @Volatile internal var beforeStoredController: ((Connection, WorkOwner) -> Unit)? = null

    private val recovery = OfflineRecovery(access) { checkAccess() }

    /** A chat asked for before the server connection exists, e.g. by a notification at a cold start. */
    private var pendingChat: String? = null

    /** Numbers settings visits, so two visits are different routes. */
    private val routeVisits = AtomicLong()

    /**
     * The draft of each controller on the screen. The drafts themselves belong to the profile and
     * are found by the chat's stable id, so closing a controller or rebuilding the profile's
     * connection does not take its draft with it; this map only lets a controller ask for the draft
     * it was opened with. Its keys are weak: a closed controller is not kept alive for its draft.
     */
    private data class DraftBinding(val connection: Connection, val draft: ChatDraft, val memory: ChatDraftMemory)
    private val draftBindings = WeakHashMap<ChatController, DraftBinding>()

    /** The screen of the current chat is up in an app in front; a chat opened now starts shown or hidden by it. */
    private var chatOnScreen = false

    private val _notice = MutableStateFlow<String?>(null)

    /** A one-line message for the user, such as a refused delete; the screen shows it once. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun noticeShown() {
        _notice.value = null
    }

    init {
        viewModelScope.launch {
            container.connection.collectLatest { connection ->
                if (connection != null) beforeInitialConnection?.invoke(connection)
                val hasCurrentAccess = connection != null && accessConnection === connection &&
                    accessJob?.isCancelled == false && accessOwner?.let { eligible(it, connection) } == true
                _searchJourney.value = null
                _searchEntry.value = SearchEntry()
                searchScrolled(0, 0)
                _chat.value?.close()
                _chat.value = null
                draftBindings.clear()
                if (!hasCurrentAccess) {
                    _sidebar.value = SidebarState()
                    listedByServer = false
                }
                if (connection == null) {
                    // No server answers for this one anymore: work for it must not bring its state back.
                    loadJob?.cancel()
                    checkAccess(null)
                    return@collectLatest
                }
                if (!hasCurrentAccess) {
                    accessJob?.cancel()
                    loadJob?.cancel()
                    cachedListProducer?.cancel()
                    _access.value = Access.UNKNOWN
                    _accessError.value = null
                }
                val owner = awaitInitialOwner(connection)
                showCachedChats(connection, owner)
                // A full memory has no slot for another new chat; the last stored chat can still open.
                if (!newChat(connection, owner)) draftsOf(connection).mostRecentChatId()?.let { openChat(it, connection, owner) }
                pendingChat?.let { id ->
                    pendingChat = null
                    openChat(id, connection, owner)
                }
                if (accessOwner != owner || accessConnection !== connection || accessJob?.isCancelled != false) checkAccess(owner, connection)
            }
        }
        // The gateway tells when the list of chats changed anywhere: Telegram, the desktop, cron.
        viewModelScope.launch {
            container.connection.filterNotNull().flatMapLatest { connection -> connection.socket.events.map { connection to it } }
                .filter { (_, event) -> event.type == "sessions.changed" || event.type == "session.title" }
                .debounce(700)
                .collect { (connection, event) -> event.owner?.let { refreshChats(it, connection) } }
        }
        // A server found offline is checked again when the network comes back; the button stays for
        // the cases the network does not explain.
        viewModelScope.launch { recovery.follow(container.online) }
    }

    /** Initial work alone may retry a candidate superseded before admission. */
    private suspend fun awaitInitialOwner(origin: Connection): WorkOwner {
        var admitted: WorkOwner? = null
        container.lifetime.owners.first { owners ->
            val candidate = owners[origin.session.profile.id] ?: return@first false
            beforeInitialOwnerAdmission?.invoke(origin, candidate)
            // Only capture admission under the guard. Initialization and all suspending work
            // happen afterwards, with this immutable owner and exact Connection.
            admitted = container.lifetime.publish(candidate) {
                candidate.takeIf { container.connection.value === origin }
            }
            afterInitialOwnerDecision?.invoke(origin, candidate, admitted, currentCoroutineContext()[Job]!!)
            admitted != null
        }
        return checkNotNull(admitted)
    }

    /**
     * Probes the server; a valid session keeps the socket open and loads the chats. When a caller
     * has a returned authority and origin, the check belongs to exactly them: a newer authorization
     * or a replaced connection is fenced before an older check can cancel the current one or send.
     */
    fun checkAccess(expected: WorkOwner? = null, expectedConnection: Connection? = null) {
        val origin = expectedConnection ?: container.connection.value
        if (origin == null) {
            if (container.connection.value != null) return
            accessJob?.cancel(); accessJob = null
            accessOwner = null; accessConnection = null
            _access.value = Access.UNKNOWN; _accessError.value = null
            return
        }
        val owner = expected ?: origin.session.owner()
        if (!eligible(owner, origin)) return
        accessJob?.cancel()
        accessOwner = owner
        accessConnection = origin
        _access.value = Access.UNKNOWN
        val since = recovery.checkStarting()
        accessJob = viewModelScope.launch {
            fun admit() {
                if (!eligible(owner, origin)) throw CancellationException("Retired access origin")
            }
            try {
                admit()
                beforeAccessStoreRead?.invoke()
                val hasTokens = container.servers.load().tokens[owner.profileId] != null
                admit()
                val access = checkServer(
                    hasTokens,
                    { admit(); val required = origin.session.authInfo(owner).required; admit(); required },
                    { admit(); origin.session.probe(owner) },
                    { admit(); origin.socket.start(owner) },
                )
                if (!eligible(owner, origin)) return@launch
                container.lifetime.publish(owner) {
                    if (container.connection.value === origin) {
                        _access.value = access
                        if (access == Access.OK) _accessError.value = null
                    }
                }
                if (access != Access.OK || !eligible(owner, origin)) return@launch
                refreshChats(owner, origin)
                container.scope.launch { if (eligible(owner, origin)) runCatching { container.push.reconcile() } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                container.lifetime.publish(owner) {
                    if (container.connection.value === origin) {
                        if (e is SignInRequiredException) _access.value = Access.SIGN_IN else {
                            _accessError.value = e.message ?: e.javaClass.simpleName
                            _access.value = Access.OFFLINE
                            recovery.checkFailed(since)
                        }
                    }
                }
            }
        }
    }

    private fun eligible(expected: WorkOwner?, expectedConnection: Connection?): Boolean {
        if (expectedConnection != null && container.connection.value !== expectedConnection) return false
        if (expected != null && !container.lifetime.holds(expected.profileId, expected.generation)) return false
        return true
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

    /** Switches the app to another saved server; its screen replaces the recovery one at once. */
    fun switchServer(id: String) {
        viewModelScope.launch { container.servers.activate(id) }
    }

    /** The route the in-app sign-in page of [profile] must take; null when that server is reached directly. */
    fun signInRoute(profile: ServerProfile): ProxySettings? {
        if (profile.proxy.type == ProxyType.NONE) return null
        return effectiveRoute(profile.proxy, container.tailnet.proxy.value)
    }

    private val nativeAttempts = AtomicLong()

    /** Captured synchronously by the click, before its coroutine is launched. */
    fun captureSignIn(): suspend ((String) -> Unit) -> Unit {
        val origin = container.connection.value
        val owner = origin?.session?.owner()
        val attempt = nativeAttempts.incrementAndGet()
        return { browser ->
            if (origin != null && owner != null) signIn(origin, owner, attempt, browser)
        }
    }

    suspend fun signIn(connection: Connection, browser: (String) -> Unit) {
        val owner = connection.session.owner()
        if (!eligible(owner, connection)) throw CancellationException("The server changed")
        var opened = false
        signIn(connection, owner, nativeAttempts.incrementAndGet()) { url ->
            opened = true
            browser(url)
        }
        if (!opened && container.connection.value !== connection) throw CancellationException("The server changed")
    }

    suspend fun signIn(openBrowser: (String) -> Unit) = captureSignIn()(openBrowser)

    private suspend fun signIn(origin: Connection, owner: WorkOwner, attempt: Long, browser: (String) -> Unit) {
        fun current(expected: WorkOwner = owner) = nativeAttempts.get() == attempt && eligible(expected, origin)
        if (!current()) return
        try {
            val info = origin.session.authInfo(owner)
            if (!current()) return
            val page = CallbackPageText(
                signedIn = container.strings.get(R.string.setup_page_signed_in),
                failed = container.strings.get(R.string.setup_page_failed),
                back = container.strings.get(R.string.setup_page_back),
            )
            when (val result = origin.session.signIn(info.providers.singleOrNull(), RETURN_URL, page, owner) { url ->
                if (!current()) throw CancellationException("Retired native origin")
                browser(url)
            }) {
                is NativeResult.Applied -> if (current(result.owner)) checkAccess(result.owner, origin)
                is NativeResult.Failed -> if (current()) throw result.error
                NativeResult.Rejected -> Unit
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { if (current()) throw error }
    }

    private fun currentOrigin(origin: Connection, profileId: String): Boolean =
        container.connection.value === origin && container.lifetime.present(profileId)

    fun refreshChats(expected: WorkOwner? = null, expectedConnection: Connection? = null) {
        val connection = expectedConnection ?: container.connection.value ?: return
        // Captured before the request: a sign-out or removal while it is out makes the answer stale,
        // and it must neither greet the user as the list nor mark its chats read.
        val owner = expected ?: connection.session.owner()
        val at = owner.generation
        val wanted = { eligible(owner, connection) }
        if (!wanted()) return
        container.lifetime.publish(owner) {
            if (!wanted()) return@publish
            loadJob?.cancel()
            loadJob = viewModelScope.launch {
            if (!wanted()) return@launch
            container.lifetime.publish(owner) { if (wanted()) _sidebar.update { it.copy(loading = true, error = null) } }
            try {
                val mode = _sidebar.value.mode
                val chat = connection.session.chatFor(WorkOwner(connection.session.profile.id, at))
                val page = chat.list(limit = PAGE, archived = mode.archived, sources = mode.sources, excludeSources = mode.excludeSources)
                val chats = withReads(connection, chat, withPins(connection, chat, mode.keep(page.chats), mode, forgetGone = true, owner = owner), wanted, owner)
                container.lifetime.publish(owner) {
                    if (wanted()) {
                        _sidebar.update { if (it.mode == mode) it.copy(chats = chats, total = page.total, listed = page.chats.size, loading = false) else it }
                        listedByServer = true
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                container.lifetime.publish(owner) {
                    if (wanted()) {
                        if (e is SignInRequiredException) _access.value = Access.SIGN_IN
                        _sidebar.update { it.copy(loading = false, error = e.message) }
                    }
                }
            }
        }
        }
    }

    /**
     * [chats] with the chats pinned on the phone, those beyond the page included. A pinned chat the
     * server no longer has leaves the pins when [forgetGone]; the copy on the phone cannot tell that.
     */
    private suspend fun withPins(connection: Connection, api: ChatApi, chats: List<ChatSummary>, mode: ListMode, forgetGone: Boolean, owner: WorkOwner): List<ChatSummary> {
        val server = connection.session.profile.id
        return withLocalPins(chats, container.pins.of(server), mode::admits, useLocalPins = !connection.session.profile.androidChannel) { id ->
            try {
                api.get(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (forgetGone && e is HermesException && e.status == 404) container.pins.setOwned(owner, container.lifetime, id, false) { eligible(owner, connection) }
                null
            }
        }
    }

    /**
     * [chats] with the unread mark: an answer after where the chat is read, by the phone's mark or the
     * server's. [api] looks for the answers; the copy on the phone keeps no history for that, so a list
     * shown from it marks the answers found before, which the phone keeps.
     */
    private suspend fun withReads(connection: Connection, api: ChatApi, chats: List<ChatSummary>, wanted: () -> Boolean, owner: WorkOwner): List<ChatSummary> =
        container.reads.unread(connection.session.profile.id, chats, wanted, owner, container.lifetime) { id, after -> api.lastReply(id, after)?.timestamp }

    /** Shows the first page the phone keeps until the server answers, or when it cannot. */
    private fun showCachedChats(connection: Connection, owner: WorkOwner = connection.session.owner()) {
        val cached = connection.session.chatFor(owner).cached ?: return
        val mode = _sidebar.value.mode
        val wanted = { eligible(owner, connection) }
        cachedListProducer = viewModelScope.launch {
            val page = runCatching {
                cached.list(limit = PAGE, archived = mode.archived, sources = mode.sources, excludeSources = mode.excludeSources)
            }.getOrNull() ?: return@launch
            val chats = container.reads.cached(connection.session.profile.id, withPins(connection, cached, mode.keep(page.chats), mode, forgetGone = false, owner = owner))
            container.lifetime.publish(owner) {
                if (!listedByServer && wanted()) _sidebar.update { if (it.mode == mode) it.copy(chats = chats, total = page.total, listed = page.chats.size) else it }
            }
        }
    }

    fun loadMoreChats() {
        val connection = container.connection.value ?: return
        val state = _sidebar.value
        if (state.loading || state.listed >= state.total) return
        val at = container.lifetime.current(connection.session.profile.id)
        val owner = WorkOwner(connection.session.profile.id, at)
        val wanted = { eligible(owner, connection) }
        viewModelScope.launch {
            if (!wanted()) return@launch
            container.lifetime.publish(owner) { if (wanted()) _sidebar.update { it.copy(loading = true) } }
            try {
                val mode = state.mode
                val chat = connection.session.chatFor(WorkOwner(connection.session.profile.id, at))
                val page = chat.list(offset = state.listed, limit = PAGE, archived = mode.archived, sources = mode.sources, excludeSources = mode.excludeSources)
                val more = withReads(connection, chat, mode.keep(page.chats), wanted, owner)
                // The first page already brought every pinned chat, so the ones met again are left out.
                container.lifetime.publish(owner) { if (wanted()) _sidebar.update { s -> s.withMore(mode, more, page.total, page.chats.size) } }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                container.lifetime.publish(owner) { if (wanted()) _sidebar.update { it.copy(loading = false, error = e.message) } }
            }
        }
    }

    fun showList(mode: ListMode) {
        if (_sidebar.value.mode == mode) return
        _sidebar.update { it.copy(mode = mode, chats = emptyList(), total = 0, listed = 0) }
        listedByServer = false
        container.connection.value?.let { showCachedChats(it) }
        refreshChats()
    }

    /** Ordinary navigation ends a result journey only after the destination is admitted. */
    fun openChat(id: String) {
        val connection = container.connection.value
        if (connection == null) {
            pendingChat = id
            return
        }
        if (openChat(id, connection, connection.session.owner(), clearScreens = true)) _searchJourney.value = null
    }

    /** One admitted owner-bound destination for ordinary navigation and result activation. */
    private fun openChat(id: String, connection: Connection, owner: WorkOwner, clearScreens: Boolean = true): Boolean {
        if (!eligible(owner, connection)) return false
        val draft = draftsOf(connection).obtain(DraftKey.Chat(id))
        if (draft == null) {
            refuseDraft()
            return false
        }
        val current = _chat.value
        val alreadyOpen = current != null && current.state.value.storedId == id && draftOf(current, connection) === draft
        markRead(id, owner = owner, expectedConnection = connection)
        if (!alreadyOpen) {
            current?.close()
            _chat.value = controller(connection, id, draft, owner)
        }
        if (clearScreens) stack.clear()
        return true
    }

    /**
     * The chat is read now: its row loses the mark, the notification of its answer goes, and the
     * phone remembers the activity it showed. The server is told too, where its route accepts a
     * chat of its own process; a refusal leaves the phone's mark in charge.
     */
    private fun markRead(id: String, lastActive: Double? = null, owner: WorkOwner? = null, expectedConnection: Connection? = null) {
        val connection = expectedConnection ?: container.connection.value ?: return
        // A callback carries the profile and generation its load started in; a mark made right here
        // is current work and takes both from now. A callback of the old generation changes nothing:
        // not the sidebar, not the watcher, not the phone's mark, and it does not tell the server.
        val profileId = owner?.profileId ?: connection.session.profile.id
        val generation = owner?.generation ?: container.lifetime.current(profileId)
        val wanted = {
            container.connection.value === connection &&
                container.lifetime.holds(profileId, generation)
        }
        val origin = WorkOwner(profileId, generation)
        var activity: Double? = null
        val admitted = container.lifetime.publish(origin) {
            if (!wanted()) false else {
                container.turns.read(id)
                _sidebar.update { state -> state.copy(chats = state.chats.map { if (it.id == id) it.copy(unread = false) else it }) }
                activity = lastActive ?: _sidebar.value.chats.firstOrNull { it.id == id }?.lastActive
                true
            }
        } == true
        if (!admitted) return
        val at = activity ?: return
        val write = viewModelScope.launch {
            container.lifetime.withOwner(origin) { container.reads.markRead(profileId, id, at, wanted) }
            if (!wanted()) return@launch
            runCatching { connection.session.chatFor(origin).mutate(id, unread = false) }
                .onFailure { e -> if (e is CancellationException) throw e else Log.d(TAG, "Read state of $id not sent", e) }
        }
        observedreadProducers?.add(write)
    }

    /**
     * The app came back to the front: the chat on the screen is read, and its notification goes. A
     * chat under another screen is not on the screen, and stays as it was.
     */
    fun appForeground() {
        if (!chatOnScreen) return
        _chat.value?.state?.value?.storedId?.let { markRead(it) }
    }

    /**
     * The current controller's issued draft, only for its exact current connection. A retired or
     * unbound controller is rejected without looking up a profile or creating any retained state.
     * Callers collecting the connection separately pass that value too, so a stale pair stays hidden
     * until both collectors catch up. Ordinary draft retention lives in [org.evsyukov.hermes.ui.chat.ProfileDraftMemory].
     */
    fun draftOf(controller: ChatController, connection: Connection? = container.connection.value): ChatDraft? {
        val binding = draftBindings[controller] ?: return null
        if (controller !== _chat.value || connection !== container.connection.value || binding.connection !== connection ||
            container.drafts.existing(binding.connection.session.profile.id) !== binding.memory) return null
        return binding.draft
    }

    /** The drafts of a connection's profile, kept while the app runs, across connection rebuilds. */
    private fun draftsOf(connection: Connection): ChatDraftMemory =
        container.drafts.of(connection.session.profile.id)

    /** A new slot cannot be taken without losing words or files, so the screen stays where it is. */
    private fun refuseDraft() {
        _notice.value = container.strings.get(R.string.draft_limit_reached, container.drafts.capacity)
    }

    /**
     * The screen of [controller] came up in an app in front, or went away: under another screen, with
     * the app in the background, or for another chat. Only a chat on the screen works on its
     * transcript and takes its answers as read.
     */
    fun chatShown(controller: ChatController, shown: Boolean) {
        controller.setVisible(shown)
        if (controller !== _chat.value) return
        chatOnScreen = shown
        container.turns.visibleChat = if (shown) controller.state.value.storedId else null
    }

    /**
     * An empty chat, as the pencil button and the pull past the end give; the server lists it from the
     * first message. False when every draft slot holds words or files: the screen keeps the current
     * chat, and [notice] says what to clear.
     */
    fun newChat(): Boolean {
        val connection = container.connection.value ?: return false
        return newChat(connection, connection.session.owner())
    }

    private fun newChat(connection: Connection, owner: WorkOwner): Boolean {
        if (!eligible(owner, connection)) return false
        // The first message of the open new chat is still getting its id. Handing the same draft to
        // another controller would later move the follow-up into the sending chat under its feet.
        // This is checked before the empty-chat shortcut: the controller's published state is empty
        // while the chat is off screen, even after its first message went out.
        val memory = draftsOf(connection)
        if (memory.isSendingNewChat()) {
            _notice.value = container.strings.get(R.string.draft_new_chat_pending)
            return false
        }
        val current = _chat.value
        // Sign-out retires bindings even for an empty controller. Only an issued current binding
        // can take this shortcut; otherwise install a fresh controller with the retained/new draft.
        if (current != null && current.state.value.storedId == null && current.state.value.items.isEmpty() &&
            draftOf(current, connection) != null) {
            _searchJourney.value = null
            stack.clear()
            return true
        }
        val draft = memory.obtain(DraftKey.NewChat)
        if (draft == null) {
            refuseDraft()
            return false
        }
        current?.close()
        _chat.value = controller(connection, null, draft, owner)
        _searchJourney.value = null
        stack.clear()
        return true
    }

    /**
     * The default model of new chats changed. An empty chat on the screen was started on the old
     * default and its agent is already built, so it is started again: the next message uses the
     * new model. A chat with messages keeps its own model, and the server gives an open chat that
     * never picked one the new default at its next turn.
     */
    fun mainModelChanged() {
        val current = _chat.value ?: return
        val state = current.state.value
        if (state.storedId != null || state.items.isNotEmpty()) return
        val connection = container.connection.value ?: return
        val draft = draftsOf(connection).obtain(DraftKey.NewChat) ?: return refuseDraft()
        current.close()
        _chat.value = controller(connection, null, draft)
    }

    private val _shared = MutableStateFlow<Shared?>(null)

    /** A share from another app, waiting for the empty new chat to take it into its field. */
    val shared: StateFlow<Shared?> = _shared.asStateFlow()

    /** Opens a new chat for a share; [read] takes the files of the sharing app, off the main thread. */
    fun share(read: () -> Shared?) {
        if (!newChat()) return
        stack.clear()
        viewModelScope.launch {
            val shared = withContext(Dispatchers.IO) { read() } ?: return@launch
            if (shared.skipped > 0) _notice.value = container.strings.get(R.string.share_skipped)
            if (shared.text.isNotEmpty() || shared.attachments.isNotEmpty()) _shared.value = shared
        }
    }

    fun sharedTaken() {
        _shared.value = null
    }

    private fun controller(connection: Connection, id: String?, draft: ChatDraft, initialOwner: WorkOwner = connection.session.owner()): ChatController {
        // The chat takes the place of the one on the screen, shown or hidden as that one was.
        container.turns.visibleChat = id.takeIf { chatOnScreen }
        // The controller this completion belongs to. A callback that ran before construction ended
        // has no owner yet, so it changes no visibility rather than comparing with an unset value.
        var owner: ChatController? = null
        val memory = draftsOf(connection)
        if (id != null) beforeStoredController?.invoke(connection, initialOwner)
        val created = ChatController(
            connection.session, connection.socket, viewModelScope, container.strings, id,
            onSessionCreated = { stored ->
                // An empty new chat got its id with the first message: its leftovers, if any, are that
                // chat's now, not the next new chat's. This belongs to the captured draft alone, even
                // when another chat is on the screen.
                if (id == null && container.drafts.existing(connection.session.profile.id) === memory) memory.migrate(draft, draftKey(stored))
                // The screen and the notification owner belong to the chat and connection that are
                // current now. A late answer of a chat the user left may not take them away.
                val current = owner
                val sameConnection = container.connection.value === connection
                if (current != null && _chat.value === current && sameConnection && chatOnScreen) {
                    container.turns.visibleChat = stored
                }
                // The sidebar of the connection that asked still needs the new chat, whatever chat
                // of that connection is on the screen.
                if (sameConnection) refreshChats()
            },
            onLive = { live, stored, title -> if (container.connection.value === connection) container.turns.bind(live, stored, title) },
            onTurnStart = {
                val hold = container.turns.turnStarting()
                if (id == null && memory.isNewChat(draft)) {
                    memory.markSending(draft)
                    object : TurnHold {
                        override fun live(id: String) = hold.live(id)
                        override fun submitted() = hold.submitted()
                        override fun release() {
                            memory.clearSending(draft)
                            hold.release()
                        }
                    }
                } else {
                    hold
                }
            },
            onRead = { id, at, work -> markRead(id, at, work, connection) },
            visible = chatOnScreen,
            questionMemory = connection.questions,
            wanted = { container.connection.value === connection },
            initialOwner = initialOwner,
        )
        owner = created
        draftBindings[created] = DraftBinding(connection, draft, memory)
        return created
    }

    private fun draftKey(storedId: String?): DraftKey = if (storedId == null) DraftKey.NewChat else DraftKey.Chat(storedId)

    fun renameChat(id: String, title: String) = mutate(id) { api, _, _ -> api.rename(id, title) }
    fun pinChat(id: String, pinned: Boolean) = mutate(id) { api, _, _ -> api.pin(id, pinned) }
    fun archiveChat(id: String, archived: Boolean) = mutate(id) { api, _, _ -> api.archive(id, archived) }

    fun deleteChat(id: String) {
        val captured = container.connection.value ?: return
        val memory = container.drafts.existing(captured.session.profile.id)
        mutate(id, captured) { api, origin, owner ->
        api.delete(id)
        beforeChatDeleteAdmission?.invoke()
        val wanted = { container.lifetime.holds(owner.profileId, owner.generation) }
        container.lifetime.withOwner(owner) {
            if (wanted()) {
                container.reads.forget(owner.profileId, id, wanted)
                container.drafts.forgetCaptured(owner.profileId, id, memory)
            }
        }
        origin.session.forgetChat(id, owner, wanted)
        container.lifetime.publish(owner) {
            if (wanted()) {
                origin.questions.forget(id)
                if (eligible(owner, origin) && _chat.value?.state?.value?.storedId == id) newChat()
            }
        }
    }
    }

    /**
     * Removes [target] from the phone and drops what the phone kept for it. The form may go away
     * while this runs, so the work belongs to the view model, not to the form's scope. True when
     * the profile is gone.
     */
    fun prepareRemoval(target: ServerProfile): suspend () -> Boolean {
        val owner = container.lifetime.owner(target.id)
        val route = stack.lastOrNull()
        val memory = container.drafts.existing(target.id)
        return { executeRemoval(target, owner, route, memory) }
    }

    fun prepareSignOut(target: ServerProfile): suspend () -> LocalSignOutResult = prepareSignOut(target, freshComposer = true)

    private fun prepareSignOut(target: ServerProfile, freshComposer: Boolean): suspend () -> LocalSignOutResult {
        val origin = container.connection.value
        val owner = origin?.session?.owner()
        val route = stack.lastOrNull()
        val memory = container.drafts.existing(target.id)
        return {
            if (origin == null || owner == null || owner.profileId != target.id) LocalSignOutResult.REJECTED
            else executeSignOut(target, origin, owner, route, memory, freshComposer)
        }
    }

    suspend fun removeServer(target: ServerProfile): Boolean = prepareRemoval(target)()

    private suspend fun executeRemoval(target: ServerProfile, owner: WorkOwner, route: Screen?, memory: ChatDraftMemory?): Boolean {
        return viewModelScope.async {
            if (!container.lifetime.holds(owner.profileId, owner.generation)) return@async false
            val applied = when (val removed = container.servers.remove(target.id, owner)) {
                is NativeResult.Applied -> removed
                NativeResult.Rejected -> return@async false
                is NativeResult.Failed -> {
                    container.lifetime.publish(owner) {
                        if (stack.lastOrNull() === route) _notice.value = container.strings.get(R.string.setup_remove_failed, target.name)
                    }
                    return@async false
                }
            }
            // The exact removal marker is checked and its local effects run under the authority
            // gate. The same id added back clears the marker, so this older removal cannot erase
            // the new profile's data; it then has no effect at all.
            var cleanupFailed = applied.cleanupError != null || applied.cleanupSuperseded
            val cleaned = container.lifetime.withRemoval(target.id, applied.owner.generation) {
                try {
                    container.responses.forget(target.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    cleanupFailed = true
                }
                try {
                    container.reads.clear(target.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    cleanupFailed = true
                }
                try { container.drafts.forgetCaptured(target.id, memory) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { cleanupFailed = true }
            }
            if (cleaned != null && cleanupFailed) {
                container.lifetime.publishRemoval(applied.owner) {
                    if (stack.lastOrNull() === route) _notice.value = container.strings.get(R.string.setup_remove_cleanup_failed, target.name)
                }
            }
            true
        }.await()
    }

    /**
     * Signs out of [target] when it is still the server the app works with, then drops its local
     * data. The form may go away while this runs, so the work belongs to the view model. Navigation
     * and the access check happen only for the captured connection and the settings visit that
     * started it, never for a server or a screen that replaced them meanwhile.
     */
    suspend fun signOut() {
        val target = container.connection.value?.session?.profile ?: return
        prepareSignOut(target)()
    }

    suspend fun signOut(target: ServerProfile): Boolean = prepareSignOut(target, freshComposer = false)() in setOf(LocalSignOutResult.COMPLETE, LocalSignOutResult.SUPERSEDED)

    private suspend fun executeSignOut(target: ServerProfile, origin: Connection, owner: WorkOwner, route: Screen?, memory: ChatDraftMemory?, freshComposer: Boolean): LocalSignOutResult {
        return viewModelScope.async {
            if (!eligible(owner, origin)) return@async LocalSignOutResult.REJECTED
            val result = try {
                origin.session.signOut(target.id, owner, clearDrafts = { container.drafts.forgetCaptured(target.id, memory) }) { container.reads.clear(target.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.lifetime.publish(owner) {
                    if (eligible(owner, origin) && stack.lastOrNull() === route) _notice.value = container.strings.get(R.string.settings_sign_out_failed, target.name)
                }
                return@async LocalSignOutResult.FAILED
            }
            // A rejected or failed sign-out changed nothing: no cleanup, no navigation, no access
            // check. Only an applied retirement of this exact generation may clean its local data.
            if (result == NativeResult.Rejected) return@async LocalSignOutResult.REJECTED
            if (result is NativeResult.Failed) {
                container.lifetime.publish(owner) {
                    if (eligible(owner, origin) && stack.lastOrNull() === route) _notice.value = container.strings.get(R.string.settings_sign_out_failed, target.name)
                }
                return@async LocalSignOutResult.FAILED
            }
            result as NativeResult.Applied
            afterSignoutCleanup?.invoke()
            if (result.cleanupSuperseded || !eligible(result.owner, origin)) return@async LocalSignOutResult.SUPERSEDED
            if (result.cleanupError != null) {
                container.lifetime.publish(result.owner) {
                    if (container.connection.value === origin) {
                        _access.value = Access.UNKNOWN
                        _notice.value = container.strings.get(R.string.settings_sign_out_cleanup_failed, target.name)
                    }
                }
                return@async LocalSignOutResult.INCOMPLETE
            }
            val popped = container.lifetime.publish(result.owner) {
                if (container.connection.value === origin && stack.lastOrNull() === route) {
                    if (route is Screen.Settings) pop()
                    if (freshComposer) newChat()
                    true
                } else false
            } == true
            if (popped) checkAccess(result.owner, origin)
            LocalSignOutResult.COMPLETE
        }.await()
    }

    private fun mutate(id: String, expectedConnection: Connection? = null, action: suspend (ChatMutations, Connection, WorkOwner) -> Unit) {
        val origin = expectedConnection ?: container.connection.value ?: return
        val owner = origin.session.owner()
        val wanted = { eligible(owner, origin) }
        if (!wanted()) return
        val live = container.turns.liveOf(id)
        val api = ChatMutations(
            origin.session.chatFor(owner), origin.socket,
            liveOf = { stored -> live.takeIf { stored == id } },
            pinLocally = { chat, pinned -> container.pins.setOwned(owner, container.lifetime, chat, pinned, wanted) },
            owner = owner, wanted = wanted, transport = origin.socket.transport.value,
        )
        mutationProducer = viewModelScope.launch {
            try {
                if (!wanted()) return@launch
                action(api, origin, owner)
                container.lifetime.publish(owner) {
                    if (wanted() && _chat.value?.state?.value?.storedId == id) _chat.value?.reload()
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                container.lifetime.publish(owner) {
                    if (wanted()) _notice.value = error.message ?: container.strings.get(R.string.error_failed)
                }
            }
            if (wanted()) refreshChats(owner, origin)
        }
    }

    /** Opens Search from the drawer, fresh: its list starts at the top and the keyboard may open. */
    fun showSearch() {
        _searchJourney.value = null
        _searchEntry.value = SearchEntry()
        searchScrolled(0, 0)
        push(Screen.Search)
    }

    /** Current viewport reported by the composed results list. */
    internal val searchListPosition: Pair<Int, Int> get() = searchListIndex to searchListOffset

    /** The place the open Search screen shows; kept for the return from a result. */
    fun searchScrolled(index: Int, offset: Int) {
        searchListIndex = index
        searchListOffset = offset
    }

    fun setQuery(query: String) {
        val before = search.value.request
        searchOwner.setQuery(query)
        if (search.value.request != before) {
            _searchJourney.value = null
            _searchEntry.value = SearchEntry()
        }
    }

    /**
     * Opens a search result, if it still belongs to the current request of the current server. The
     * conversation is opened first; Search and its place are cleared only once it is the chat on the
     * screen, so a refused navigation leaves the person where they were with the results intact.
     */
    fun openSearchHit(hit: SearchHit, identity: SearchRequestIdentity?) {
        if (identity == null || !searchOwner.admits(hit, identity)) return
        val journey = _searchJourney.value
        val current = _chat.value?.state?.value?.storedId
        // A second tap of the same row is the journey already under way, not another navigation.
        if (journey != null && journey.request == identity && journey.chatId == hit.chat.id && current == hit.chat.id) return
        val place = SearchJourney(identity, hit.chat.id, searchListIndex, searchListOffset)

        if (!openChat(hit.chat.id, identity.connection, identity.connection.session.owner(), clearScreens = false)) return

        stack.clear()
        _searchJourney.value = place
    }

    /**
     * Back from a result's conversation: shows Search with the same query, results and list place,
     * without asking the server again. A journey whose request or chat no longer belongs to the
     * screen is dropped instead of restoring results of another server; the caller then leaves the
     * conversation as usual. False when there was no journey to return to.
     */
    fun returnToSearch(): Boolean {
        val journey = _searchJourney.value ?: return false
        // A screen over the conversation handles Back itself; the journey waits under it.
        if (stack.isNotEmpty()) return false
        if (container.connection.value !== journey.request.connection || search.value.request != journey.request ||
            _chat.value?.state?.value?.storedId != journey.chatId
        ) {
            _searchJourney.value = null
            return false
        }
        _searchJourney.value = null
        _searchEntry.value = SearchEntry(journey.index, journey.offset, requestFocus = false)
        push(Screen.Search)
        return true
    }

    fun push(screen: Screen) {
        stack.add(screen)
        // The screen covers the chat: its answers are not being read, so they are announced.
        container.turns.visibleChat = null
    }

    /** Opens a settings visit; each visit is its own route, so a late action cannot pop a later one. */
    fun pushSettings() {
        push(Screen.Settings(routeVisits.incrementAndGet()))
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        // Back on the chat, its screen says it is shown again (chatShown).
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
