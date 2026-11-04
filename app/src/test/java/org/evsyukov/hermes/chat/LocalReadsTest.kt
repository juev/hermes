package org.evsyukov.hermes.chat

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.cache.CacheDatabase
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.remote.CachedReads
import org.evsyukov.hermes.data.remote.ChatApi
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.data.remote.HermesHttp
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class LocalReadsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The marks are kept per server and outlive a test; each test has a server of its own. */
    private val server = "unread-${UUID.randomUUID()}"

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun chat(id: String, lastActive: Double, unread: Boolean = false, lastReadAt: Double? = null) =
        ChatSummary(id, id, "", "telegram", null, 0.0, lastActive, 2, pinned = false, archived = false, active = false, unread = unread, lastReadAt = lastReadAt)

    /** The chat routes as a list shown from the phone's copy reads them: no history is kept for a look. */
    private val offline: suspend (String, Double) -> Double? = run {
        val http = HermesHttp("http://127.0.0.1:9/".toHttpUrl(), OkHttpClient(), tokens = { null }, saveTokens = {}, strings = AppStrings(context.resources))
        val api = ChatApi(http, CachedReads(ResponseCache(db.responses(), scope, { Long.MAX_VALUE }), server))
        val find: suspend (String, Double) -> Double? = { id, after -> api.lastReply(id, after)?.timestamp }
        find
    }

    /** Answers looked up by chat, as the history would give them; records what was asked. */
    private class Finder(private val answers: Map<String, Double> = emptyMap()) {
        val asked = mutableListOf<Pair<String, Double>>()

        suspend fun find(id: String, after: Double): Double? {
            asked += id to after
            return answers[id]?.takeIf { it > after }
        }
    }

    private fun failing(): suspend (String, Double) -> Double? = { _, _ -> throw IOException("offline") }

    private suspend fun LocalReads.unreadOf(chats: List<ChatSummary>, find: suspend (String, Double) -> Double?) =
        unread(server, chats, find).associate { it.id to it.unread }

    @Test
    fun `an answer found online stays unread after a restart that can only read the phone's copy`() = runBlocking<Unit> {
        val before = LocalReads(context)
        before.markRead(server, "a", 100.0)
        assertThat(before.unreadOf(listOf(chat("a", 120.0)), Finder(mapOf("a" to 120.0))::find)).containsExactly("a", true)

        val restarted = LocalReads(context)

        assertThat(restarted.unreadOf(listOf(chat("a", 120.0)), offline)).containsExactly("a", true)
        assertThat(restarted.of(server)).containsExactly("a", 100.0)
    }

    @Test
    fun `activity without an answer stays read, an answer in another chat marks it, and a known answer is not looked for again`() = runBlocking<Unit> {
        val reads = LocalReads(context)
        for (id in listOf("working", "answered", "quiet")) reads.markRead(server, id, 100.0)
        val chats = listOf(chat("working", 150.0), chat("answered", 150.0), chat("quiet", 100.0))
        val finder = Finder(mapOf("working" to 90.0, "answered" to 140.0))

        assertThat(reads.unreadOf(chats, finder::find)).containsExactly("working", false, "answered", true, "quiet", false)
        assertThat(finder.asked.map { it.first }).containsExactly("working", "answered")

        // The next turn of the answered chat runs tools while its history cannot be read.
        val again = Finder()
        val later = listOf(chat("working", 150.0), chat("answered", 180.0), chat("quiet", 100.0))
        assertThat(reads.unreadOf(later, failing())).containsExactly("working", false, "answered", true, "quiet", false)
        assertThat(reads.unreadOf(later, again::find)).containsExactly("working", false, "answered", true, "quiet", false)
        assertThat(again.asked).isEmpty()
    }

    @Test
    fun `reading clears the answer and a later answer marks the chat again, also after a restart`() = runBlocking<Unit> {
        val reads = LocalReads(context)
        reads.markRead(server, "a", 100.0)
        assertThat(reads.unreadOf(listOf(chat("a", 120.0)), Finder(mapOf("a" to 120.0))::find)).containsExactly("a", true)

        reads.markRead(server, "a", 120.0)
        assertThat(reads.unreadOf(listOf(chat("a", 120.0)), offline)).containsExactly("a", false)
        assertThat(LocalReads(context).unreadOf(listOf(chat("a", 120.0)), offline)).containsExactly("a", false)

        assertThat(reads.unreadOf(listOf(chat("a", 150.0)), Finder(mapOf("a" to 120.0))::find)).containsExactly("a", false)
        assertThat(reads.unreadOf(listOf(chat("a", 170.0)), Finder(mapOf("a" to 165.0))::find)).containsExactly("a", true)
        assertThat(LocalReads(context).unreadOf(listOf(chat("a", 170.0)), offline)).containsExactly("a", true)
    }

    @Test
    fun `a chat the server calls unread stays unread while its history cannot be read, and is not marked read on the phone`() = runBlocking<Unit> {
        // The dashboard read both at 100; the server calls them unread because the chats moved since.
        val tools = chat("tools", 150.0, unread = true, lastReadAt = 100.0)
        val answered = chat("answered", 150.0, unread = true, lastReadAt = 100.0)
        val reads = LocalReads(context)

        assertThat(reads.unreadOf(listOf(tools, answered), offline)).containsExactly("tools", true, "answered", true)
        assertThat(reads.unreadOf(listOf(tools, answered), failing())).containsExactly("tools", true, "answered", true)
        assertThat(reads.of(server)).isEmpty()

        val finder = Finder(mapOf("tools" to 90.0, "answered" to 140.0))
        assertThat(reads.unreadOf(listOf(tools, answered), finder::find)).containsExactly("tools", false, "answered", true)
        assertThat(finder.asked).containsExactly("tools" to 100.0, "answered" to 100.0)
        assertThat(LocalReads(context).unreadOf(listOf(answered), offline)).containsExactly("answered", true)
    }

    @Test
    fun `a look that fails leaves the chat to the next one, which finds the answer`() = runBlocking<Unit> {
        val reads = LocalReads(context)
        reads.markRead(server, "a", 100.0)

        assertThat(reads.unreadOf(listOf(chat("a", 150.0)), failing())).containsExactly("a", false)
        assertThat(reads.unreadOf(listOf(chat("a", 150.0)), Finder(mapOf("a" to 140.0))::find)).containsExactly("a", true)
    }

    @Test
    fun `a chat looked through at its activity is not looked at again until the activity moves`() = runBlocking<Unit> {
        val reads = LocalReads(context)
        reads.markRead(server, "a", 100.0)
        val finder = Finder()

        reads.unreadOf(listOf(chat("a", 150.0)), finder::find)
        reads.unreadOf(listOf(chat("a", 150.0)), finder::find)
        // A list from the phone's copy is older than the look.
        reads.unreadOf(listOf(chat("a", 130.0)), finder::find)
        reads.unreadOf(listOf(chat("a", 160.0)), finder::find)

        assertThat(finder.asked).containsExactly("a" to 100.0, "a" to 100.0).inOrder()
    }

    @Test
    fun `a look that ends late takes back nothing a newer one found`() = runBlocking<Unit> {
        val reads = LocalReads(context)
        reads.markRead(server, "a", 100.0)
        val slow = CompletableDeferred<Double?>()
        val started = CompletableDeferred<Unit>()

        // A refresh of an older list is still reading when a newer one finds the answer.
        val older = async { reads.unread(server, listOf(chat("a", 120.0))) { _, _ -> started.complete(Unit); slow.await() } }
        started.await()
        reads.unread(server, listOf(chat("a", 160.0)), Finder(mapOf("a" to 155.0))::find)
        slow.complete(115.0)
        older.await()
        reads.markRead(server, "a", 150.0)

        assertThat(LocalReads(context).unreadOf(listOf(chat("a", 160.0)), offline)).containsExactly("a", true)
    }

    @Test
    fun `an answer a look brings back after the chat was forgotten is not kept`() = runBlocking<Unit> {
        val reads = LocalReads(context)
        reads.markRead(server, "a", 100.0)
        val slow = CompletableDeferred<Double?>()
        val started = CompletableDeferred<Unit>()

        val look = async { reads.unread(server, listOf(chat("a", 150.0))) { _, _ -> started.complete(Unit); slow.await() } }
        started.await()
        reads.forget(server, "a")
        slow.complete(140.0)
        look.await()

        assertThat(reads.answers(server)).isEmpty()
        assertThat(LocalReads(context).answers(server)).isEmpty()
    }

    @Test
    fun `a look that ends after the server's read state was cleared settles nothing`() = runBlocking<Unit> {
        val reads = LocalReads(context)
        val unread = chat("a", 150.0, unread = true, lastReadAt = 100.0)
        val slow = CompletableDeferred<Double?>()
        val started = CompletableDeferred<Unit>()

        val look = async { reads.unread(server, listOf(unread)) { _, _ -> started.complete(Unit); slow.await() } }
        started.await()
        reads.clear(server)
        slow.complete(null)
        look.await()

        // Signed in again: the server's word stands until a look that started after the sign-out.
        assertThat(reads.unreadOf(listOf(unread), failing())).containsExactly("a", true)
    }

    @Test
    fun `only a few histories are read at once`() = runBlocking<Unit> {
        val reads = LocalReads(context)
        val chats = (1..20).map { chat("c$it", 150.0) }
        for (c in chats) reads.markRead(server, c.id, 100.0)
        var reading = 0
        var most = 0
        val looked = mutableListOf<String>()

        reads.unread(server, chats) { id, _ ->
            reading++
            most = maxOf(most, reading)
            delay(20)
            reading--
            looked += id
            null
        }

        assertThat(looked).hasSize(20)
        assertThat(most).isAtMost(4)
    }

    @Test
    fun `a mark is kept per server and never goes back`() = runBlocking<Unit> {
        val reads = LocalReads(context)
        reads.markRead("mark-server", "a", 100.0)
        reads.markRead("mark-server", "a", 90.0)
        reads.markRead("work", "a", 50.0)

        assertThat(reads.of("mark-server")).containsExactly("a", 100.0)
        assertThat(reads.of("work")).containsExactly("a", 50.0)
        assertThat(reads.of("other")).isEmpty()

        reads.clear("mark-server")
        assertThat(reads.of("mark-server")).isEmpty()
        assertThat(reads.of("work")).containsEntry("a", 50.0)
    }

    @Test
    fun `the first sight of a chat is read at its current activity`() = runBlocking<Unit> {
        val reads = LocalReads(context)
        reads.baseline("baseline-server", listOf(chat("old", 100.0), chat("new", 200.0)))

        assertThat(reads.of("baseline-server")).containsExactly("old", 100.0, "new", 200.0)
    }

    @Test
    fun `a chat the server reports unread is not baselined, and an existing mark is kept`() = runBlocking<Unit> {
        val reads = LocalReads(context)
        reads.markRead("unread-server", "seen", 100.0)
        reads.baseline("unread-server", listOf(chat("seen", 150.0, unread = true), chat("unread", 150.0, unread = true)))

        assertThat(reads.of("unread-server")).containsExactly("seen", 100.0)
    }

    @Test
    fun `a forgotten chat leaves the marks`() = runBlocking<Unit> {
        val reads = LocalReads(context)
        reads.markRead("forget-server", "a", 100.0)
        reads.markRead("forget-server", "b", 100.0)

        reads.forget("forget-server", "a")

        assertThat(reads.of("forget-server")).containsExactly("b", 100.0)
    }
}
