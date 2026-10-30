package org.evsyukov.hermes.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.hermes.data.remote.ChatSummary
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class LocalReadsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun chat(id: String, lastActive: Double, unread: Boolean = false) =
        ChatSummary(id, id, "", "telegram", null, 0.0, lastActive, 2, pinned = false, archived = false, active = false, unread = unread)

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
