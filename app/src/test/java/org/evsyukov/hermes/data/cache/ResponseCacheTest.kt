package org.evsyukov.hermes.data.cache

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ResponseCacheTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), CacheDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var limit = 1_000L
    private var clock = 0L
    private val cache = ResponseCache(db.responses(), scope, { limit }, { ++clock })

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun `a response reads back as it was written, also one larger than a row of the database`() = runBlocking {
        limit = 10_000_000
        val big = "абв".repeat(400_000)
        cache.write("home", "api/sessions/1/messages", big)
        cache.write("home", "api/status", "{}")

        assertThat(cache.read("home", "api/sessions/1/messages")).isEqualTo(big)
        assertThat(cache.read("home", "api/status")).isEqualTo("{}")
        assertThat(cache.read("home", "api/missing")).isNull()
        assertThat(cache.read("work", "api/status")).isNull()
    }

    @Test
    fun `writing again replaces the response and its size`() = runBlocking {
        cache.write("home", "a", "x".repeat(300))
        cache.write("home", "a", "y")

        assertThat(cache.read("home", "a")).isEqualTo("y")
        assertThat(cache.size()).isEqualTo(1)
    }

    @Test
    fun `over the limit the least recently used responses go first, and a read counts as use`() = runBlocking {
        cache.write("home", "a", "x".repeat(400))
        cache.write("home", "b", "x".repeat(400))
        cache.read("home", "a")
        cache.write("home", "c", "x".repeat(400))

        assertThat(cache.read("home", "b")).isNull()
        assertThat(cache.read("home", "a")).isNotNull()
        assertThat(cache.read("home", "c")).isNotNull()
        assertThat(cache.size()).isAtMost(limit)
    }

    @Test
    fun `a lowered limit takes effect on trim`() = runBlocking {
        cache.write("home", "a", "x".repeat(400))
        cache.write("home", "b", "x".repeat(400))
        limit = 500
        cache.trim()

        assertThat(cache.read("home", "a")).isNull()
        assertThat(cache.read("home", "b")).isNotNull()
    }

    @Test
    fun `forget and drop remove the responses of one server only`() = runBlocking {
        cache.write("home", "api/sessions/1", "{}")
        cache.write("home", "api/sessions/1/messages?limit=500", "{}")
        cache.write("home", "api/sessions/10", "{}")
        cache.write("work", "api/sessions/1", "{}")
        cache.forget("home", "api/sessions/1/")
        cache.drop("home", "api/sessions/1")

        assertThat(cache.read("home", "api/sessions/1/messages?limit=500")).isNull()
        assertThat(cache.read("home", "api/sessions/1")).isNull()
        assertThat(cache.read("home", "api/sessions/10")).isNotNull()
        // The prefix is taken literally: `_` and `%` match only themselves.
        cache.write("home", "api/x_y", "{}")
        cache.forget("home", "api/x%")
        assertThat(cache.read("home", "api/x_y")).isNotNull()
        assertThat(cache.read("work", "api/sessions/1")).isNotNull()
    }

    @Test
    fun `forget without a prefix drops the whole server`() = runBlocking {
        cache.write("home", "a", "{}")
        cache.write("work", "a", "{}")
        cache.forget("home")

        assertThat(cache.read("home", "a")).isNull()
        assertThat(cache.read("work", "a")).isNotNull()
    }

    @Test
    fun `clear empties the cache`() = runBlocking {
        cache.write("home", "a", "{}")
        cache.clear()

        assertThat(cache.size()).isEqualTo(0)
        assertThat(cache.read("home", "a")).isNull()
    }
}
