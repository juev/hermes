package org.evsyukov.hermes.data.server

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
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.auth.Tokens
import org.evsyukov.hermes.data.cache.CacheDatabase
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.files.AttachmentCache
import org.evsyukov.hermes.data.remote.cacheKey
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Signing out is bound to the server the user confirmed. A session that now belongs to another
 * profile must not drop the tokens or the cache of that profile, and the attachment cache is a
 * different store: the confirmation says so, and this test keeps it true.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class SignOutTargetTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ResponseCache(db.responses(), scope, { Long.MAX_VALUE })

    internal val fixtureCleanup = org.evsyukov.hermes.testing.FixtureCleanup()
    internal fun fixtureProducerScope() = scope
    internal val fixtureDatabaseOpen get() = db.isOpen
    internal var resetFailure: RuntimeException? = null
    internal var closeFailure: RuntimeException? = null

    @Before fun setUp() { db.openHelper.writableDatabase }

    @After
    fun tearDown() {
        val owner = scope.coroutineContext[kotlinx.coroutines.Job]!!
        val store = ServerStore(context, PlainCipher, scope)
        fixtureCleanup.finish(
            listOf(owner),
            listOf("reset profiles" to { resetFailure?.let { throw it }; runBlocking { org.evsyukov.hermes.testing.resetFixtureProfiles(store, listOf("signout-home", "signout-other")) } }),
            listOf("close Room" to { db.close() }, "report close failure" to { closeFailure?.let { throw it } }),
        )
    }

    private fun session(profile: ServerProfile, store: ServerStore) =
        ServerSession(profile, OkHttpClient(), store, AppStrings(context.resources), cache)

    @Test
    fun `a stale sign-out target drops nothing`() = runBlocking<Unit> {
        val store = ServerStore(context, PlainCipher, scope)
        val profile = ServerProfile("signout-home", "Home", "https://hermes.invalid")
        store.save(profile, makeActive = true)
        store.setTokens(profile.id, Tokens("access"))
        val key = cacheKey("api/sessions/s1", emptyMap())
        cache.write(profile.id, key, """{"id": "s1"}""")
        val attachments = AttachmentCache(File(context.cacheDir, "attachments-sign-out"))
        val attachment = attachments.fileFor(profile.id, "/api/fs/download?path=report.pdf").apply { writeText("pdf") }

        session(profile, store).signOut("another-profile")

        assertThat(store.load().tokens[profile.id]).isNotNull()
        assertThat(cache.read(profile.id, key)).isNotNull()
        assertThat(attachment.isFile).isTrue()
    }

    @Test
    fun `the confirmed server loses its tokens and responses but keeps downloaded files`() = runBlocking<Unit> {
        val store = ServerStore(context, PlainCipher, scope)
        val profile = ServerProfile("signout-home", "Home", "https://hermes.invalid")
        store.save(profile, makeActive = true)
        store.setTokens(profile.id, Tokens("access"))
        val key = cacheKey("api/sessions/s1", emptyMap())
        cache.write(profile.id, key, """{"id": "s1"}""")
        val attachments = AttachmentCache(File(context.cacheDir, "attachments-sign-out-kept"))
        val attachment = attachments.fileFor(profile.id, "/api/fs/download?path=report.pdf").apply { writeText("pdf") }

        session(profile, store).signOut(profile.id)

        assertThat(store.load().tokens[profile.id]).isNull()
        assertThat(cache.read(profile.id, key)).isNull()
        // Downloaded files are a separate cache, and signing out does not delete them.
        assertThat(attachment.isFile).isTrue()
        assertThat(attachment.readText()).isEqualTo("pdf")
    }

    @Test
    fun `another profile keeps its tokens and responses when a server signs out`() = runBlocking<Unit> {
        val store = ServerStore(context, PlainCipher, scope)
        val profile = ServerProfile("signout-home", "Home", "https://hermes.invalid")
        val other = ServerProfile("signout-other", "Other", "https://other.invalid")
        store.save(profile, makeActive = true)
        store.save(other, makeActive = false)
        store.setTokens(profile.id, Tokens("access"))
        store.setTokens(other.id, Tokens("other-access"))
        val key = cacheKey("api/sessions/s1", emptyMap())
        cache.write(other.id, key, """{"id": "s1"}""")

        session(profile, store).signOut(profile.id)

        assertThat(store.load().tokens[other.id]?.accessToken).isEqualTo("other-access")
        assertThat(cache.read(other.id, key)).isNotNull()
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}
