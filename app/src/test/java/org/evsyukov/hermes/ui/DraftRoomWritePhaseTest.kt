package org.evsyukov.hermes.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import org.evsyukov.hermes.data.cache.CacheDatabase
import org.evsyukov.hermes.data.cache.ResponseCache
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DraftRoomWritePhaseTest {
    @Test fun `actual draft scenario keeps its phase attached until fixture launch roots finish`() {
        val fixture = DraftNavigationTest()
        fixture.runFixture {
            fixture.`a signout suspended at the real cache transaction clears only its captured profile`()
            assertThat(fixture.container.scope.coroutineContext.job.isActive).isTrue()
            com.google.common.truth.Truth.assertWithMessage("PHASE_MUST_OUTLIVE_ACTIVE_LAUNCH_ROOTS")
                .that(fixture.container.responses.beforeOwnedWriteObservation).isNotNull()
            assertThat(fixture.container.responses.beforeOwnedWrite).isNotNull()
        }
        assertThat(fixture.container.scope.coroutineContext.job.isCompleted).isTrue()
        assertThat(fixture.container.responses.beforeOwnedWriteObservation).isNull()
        assertThat(fixture.container.responses.beforeOwnedWrite).isNull()
        assertThat(fixture.container.cacheDatabase.isOpen).isFalse()
    }

    @Test fun `closing the real phase pumps queued Main work needed by an actual cache writer`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, CacheDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cache = ResponseCache(db.responses(), scope, { null })
        val phase = DraftRoomWritePhase(cache, probe = false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var pumps = 0
        try {
            val writer = cache.keep("synthetic", "phase-cleanup", "retained body") {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS)) { "Main-dependent writer was not released" }
                true
            }
            check(entered.await(5, TimeUnit.SECONDS))
            assertThat(phase.producers).contains(writer)
            Handler(Looper.getMainLooper()).post { release.countDown() }
            assertThat(writer.isCompleted).isFalse()
            phase.close { pumps++; shadowOf(Looper.getMainLooper()).idle() }
            assertThat(pumps).isGreaterThan(0)
            assertThat(writer.isCompleted && !writer.isCancelled).isTrue()
            runBlocking { assertThat(cache.read("synthetic", "phase-cleanup")).isEqualTo("retained body") }
            assertThat(cache.beforeOwnedWrite).isNull()
            assertThat(cache.beforeOwnedWriteObservation).isNull()
        } finally {
            release.countDown()
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { withTimeout(5_000) { scope.coroutineContext.job.cancelAndJoin() } }
            db.close()
        }
    }
}
