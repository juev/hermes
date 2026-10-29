package org.evsyukov.hermes.background

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.Connection
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.remote.GatewayEvent
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.ServerStore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class TurnWatcherTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    /** The permanent-connection switch as read from disk; null until it is. */
    private val keep = MutableStateFlow<Boolean?>(null)
    private val watcher = TurnWatcher(context, scope, MutableStateFlow<Connection?>(null), Notifications(context), keep)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `the service follows the switch once it is read, not the defaults before`() {
        // A process the system started for the service, before its settings were read.
        watcher.settle()
        assertThat(shadowOf(context).nextStoppedService).isNull()
        assertThat(shadowOf(context).nextStartedService).isNull()

        keep.value = true
        assertThat(shadowOf(context).nextStartedService?.component?.className).isEqualTo(HermesService::class.java.name)
        watcher.settle()
        assertThat(shadowOf(context).nextStoppedService).isNull()

        keep.value = false
        assertThat(shadowOf(context).nextStoppedService?.component?.className).isEqualTo(HermesService::class.java.name)
    }

    @Test
    fun `a switch turned off at once after on lets the idle service go`() {
        keep.value = true
        keep.value = false

        assertThat(shadowOf(context).nextStoppedService?.component?.className).isEqualTo(HermesService::class.java.name)
    }

    @Test
    fun `the app in front starts the service of a permanent connection again`() {
        // Before the switch is read, and with it off, the app in front starts nothing.
        watcher.appInFront()
        assertThat(shadowOf(context).nextStartedService).isNull()
        keep.value = false
        watcher.appInFront()
        assertThat(shadowOf(context).nextStartedService).isNull()

        // A process started in the background read the switch, and the system refused the service then.
        keep.value = true
        shadowOf(context).nextStartedService

        watcher.appInFront()
        val started = shadowOf(context).nextStartedService
        assertThat(started?.component?.className).isEqualTo(HermesService::class.java.name)
        assertThat(started?.getStringExtra("text")).isEqualTo(context.getString(R.string.notify_connected))
    }

    @Test
    fun `the app in front keeps the text of a turn under way`() {
        keep.value = true
        watcher.bind("live-1", "stored-1", "Chat")
        watcher.onEvent(GatewayEvent("message.start", "live-1", 1, JsonObject(emptyMap())))
        shadowOf(context).clearStartedServices()

        watcher.appInFront()
        assertThat(shadowOf(context).nextStartedService?.getStringExtra("text")).isEqualTo(context.getString(R.string.notify_replying))
    }

    @Test
    fun `a turn being submitted keeps the service when the switch is read as off`() {
        // The user sends at once after a cold start, before the settings were read.
        val hold = watcher.turnStarting()
        assertThat(shadowOf(context).nextStartedService?.component?.className).isEqualTo(HermesService::class.java.name)

        keep.value = false
        assertThat(shadowOf(context).nextStoppedService).isNull()

        // The submission failed or was cancelled: nothing holds the service any more.
        hold.release()
        assertThat(shadowOf(context).nextStoppedService?.component?.className).isEqualTo(HermesService::class.java.name)
    }

    @Test
    fun `a submitted turn holds the service until it starts, then the turn holds it until it ends`() {
        keep.value = false
        shadowOf(context).nextStoppedService
        watcher.bind("live-1", "stored-1", "Chat")
        val hold = watcher.turnStarting()
        hold.live("live-1")
        hold.submitted()

        // Another chat's turn ended in between.
        watcher.settle()
        assertThat(shadowOf(context).nextStoppedService).isNull()

        watcher.onEvent(GatewayEvent("message.start", "live-1", 1, JsonObject(emptyMap())))
        watcher.settle()
        assertThat(shadowOf(context).nextStoppedService).isNull()

        watcher.onEvent(GatewayEvent("message.complete", "live-1", 2, buildJsonObject { put("text", "Done") }))
        assertThat(shadowOf(context).nextStoppedService?.component?.className).isEqualTo(HermesService::class.java.name)
    }

    @Test
    fun `a switch read as off before the watcher is built settles the service, and the watcher keeps following it`() {
        val errors = CopyOnWriteArrayList<Throwable>()
        // A dispatcher that runs at once, the way the settings may already be read when the app starts.
        val early = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher() + CoroutineExceptionHandler { _, e -> errors += e })
        try {
            val read = MutableStateFlow<Boolean?>(false)
            TurnWatcher(context, early, MutableStateFlow<Connection?>(null), Notifications(context), read)

            assertThat(errors).isEmpty()
            assertThat(shadowOf(context).nextStoppedService?.component?.className).isEqualTo(HermesService::class.java.name)

            read.value = true
            assertThat(shadowOf(context).nextStartedService?.component?.className).isEqualTo(HermesService::class.java.name)
        } finally {
            early.cancel()
        }
    }

    @Test
    fun `a submission that uploads for minutes keeps its hold, which times out only after the submit returned`() {
        val dispatcher = StandardTestDispatcher()
        val clock = dispatcher.scheduler
        val timed = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val strings = AppStrings(context.resources)
            val store = ServerStore(context, PlainCipher, timed)
            val session = ServerSession(ServerProfile("home", "Home", "https://hermes.invalid"), OkHttpClient(), store, strings)
            // The watchdog asks nothing of the server here: the chat of the hold is not bound.
            val connection = MutableStateFlow<Connection?>(Connection(session, GatewaySocket(session.http, timed, strings)))
            val read = MutableStateFlow<Boolean?>(false)
            val watcher = TurnWatcher(context, timed, connection, Notifications(context), read) { clock.currentTime }
            clock.runCurrent()
            shadowOf(context).nextStoppedService

            val hold = watcher.turnStarting()
            hold.live("live-1")
            // The session is created and attachments go up one by one, each allowed two minutes.
            clock.advanceTimeBy(5 * 60_000L)
            clock.runCurrent()
            assertThat(shadowOf(context).nextStoppedService).isNull()

            // The submit returned; the start of the turn is awaited a minute at most.
            hold.submitted()
            clock.advanceTimeBy(30_000)
            clock.runCurrent()
            assertThat(shadowOf(context).nextStoppedService).isNull()

            clock.advanceTimeBy(90_000)
            clock.runCurrent()
            assertThat(shadowOf(context).nextStoppedService?.component?.className).isEqualTo(HermesService::class.java.name)
        } finally {
            timed.cancel()
        }
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }

    @Test
    fun `a late end of the previous turn does not end a submission under way or just returned`() {
        keep.value = false
        shadowOf(context).nextStoppedService
        watcher.bind("live-1", "stored-1", "Chat")
        val hold = watcher.turnStarting()
        hold.live("live-1")

        // The answer asked for again: the interrupted turn ends late, while the new one uploads.
        watcher.onEvent(complete("live-1"))
        assertThat(shadowOf(context).nextStoppedService).isNull()

        // The submit returned; the old turn's end comes once more before the new start.
        hold.submitted()
        watcher.onEvent(complete("live-1"))
        assertThat(shadowOf(context).nextStoppedService).isNull()

        watcher.onEvent(start("live-1"))
        watcher.onEvent(complete("live-1"))
        assertThat(shadowOf(context).nextStoppedService?.component?.className).isEqualTo(HermesService::class.java.name)
    }

    @Test
    fun `a start that comes before the submit returns keeps the submission held, and its return lets the hold go`() {
        keep.value = false
        shadowOf(context).nextStoppedService
        watcher.bind("live-1", "stored-1", "Chat")
        val hold = watcher.turnStarting()
        hold.live("live-1")

        watcher.onEvent(start("live-1"))
        // The turn was so short that it ended before the submit returned.
        watcher.onEvent(complete("live-1"))
        assertThat(shadowOf(context).nextStoppedService).isNull()

        hold.submitted()
        assertThat(shadowOf(context).nextStoppedService?.component?.className).isEqualTo(HermesService::class.java.name)
    }

    @Test
    fun `a start of another session leaves a submission under way held`() {
        keep.value = false
        shadowOf(context).nextStoppedService
        watcher.bind("live-2", "stored-2", "Other")
        val hold = watcher.turnStarting()
        hold.live("live-1")

        watcher.onEvent(start("live-2"))
        watcher.onEvent(complete("live-2"))
        assertThat(shadowOf(context).nextStoppedService).isNull()

        hold.release()
        assertThat(shadowOf(context).nextStoppedService?.component?.className).isEqualTo(HermesService::class.java.name)
    }

    private fun start(live: String) = GatewayEvent("message.start", live, 1, JsonObject(emptyMap()))

    private fun complete(live: String) = GatewayEvent("message.complete", live, 2, buildJsonObject { put("text", "Done") })

    @Test
    fun `the service stays sticky unless the switch is read as off`() {
        assertThat(HermesService.sticky(keepConnected = null)).isTrue()
        assertThat(HermesService.sticky(keepConnected = true)).isTrue()
        assertThat(HermesService.sticky(keepConnected = false)).isFalse()
    }
}
