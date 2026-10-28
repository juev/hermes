package org.evsyukov.hermes.tailnet

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.evsyukov.hermes.data.server.ProxySettings
import org.evsyukov.hermes.data.server.ProxyType
import org.evsyukov.hermes.data.server.TailnetSettings
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class TailnetTest {
    @get:Rule
    val dir = TemporaryFolder()

    /** Like tsnet: the SOCKS listener is up when start returns, the node runs only later. */
    private class FakeEngine : TailnetEngine {
        var starts = 0
        var port = 41641

        @Volatile
        var state = "Starting"

        override fun start(dir: String, control: String, key: String, host: String): String {
            starts++
            return "127.0.0.1:$port\nsecret$starts"
        }

        override fun stop() {
            state = "Stopped"
        }

        override fun status() = """{"state":"$state"}"""

        override fun logs() = ""
    }

    private val home = TailnetSettings("https://hs.example.org", "hskey-auth", "hermes-android")

    @Test
    fun `the proxy is handed out once the node runs, not when its listener opens`() = runTest {
        val engine = FakeEngine()
        val tailnet = Tailnet(dir.root, backgroundScope, engine, StandardTestDispatcher(testScheduler))

        tailnet.ensure(home)
        runCurrent()
        assertThat(engine.starts).isEqualTo(1)
        assertThat(tailnet.proxy.value).isNull()

        advanceTimeBy(10_000)
        assertThat(tailnet.proxy.value).isNull()

        engine.state = "Running"
        advanceTimeBy(1_600)
        assertThat(tailnet.proxy.value).isEqualTo(ProxySettings(ProxyType.SOCKS5, "127.0.0.1", 41641, "tsnet", "secret1"))
        assertThat(tailnet.status.value.running).isTrue()
    }

    @Test
    fun `asking again while the node starts does not start it anew`() = runTest {
        val engine = FakeEngine()
        val tailnet = Tailnet(dir.root, backgroundScope, engine, StandardTestDispatcher(testScheduler))

        tailnet.ensure(home)
        runCurrent()
        advanceTimeBy(3_000)
        tailnet.ensure(home)
        runCurrent()

        assertThat(engine.starts).isEqualTo(1)
    }

    @Test
    fun `a node started with other settings withdraws the old proxy until it runs again`() = runTest {
        val engine = FakeEngine()
        val tailnet = Tailnet(dir.root, backgroundScope, engine, StandardTestDispatcher(testScheduler))
        engine.state = "Running"
        tailnet.ensure(home)
        advanceTimeBy(1_600)
        assertThat(tailnet.proxy.value?.port).isEqualTo(41641)

        engine.state = "Starting"
        engine.port = 41642
        tailnet.ensure(home.copy(hostname = "phone"))
        runCurrent()
        assertThat(engine.starts).isEqualTo(2)
        assertThat(tailnet.proxy.value).isNull()

        engine.state = "Running"
        advanceTimeBy(1_600)
        assertThat(tailnet.proxy.value?.port).isEqualTo(41642)
    }

    /** The first status call, of the first node, blocks until released and then says that node runs. */
    private class BlockingEngine : TailnetEngine {
        val starts = AtomicInteger()
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun start(dir: String, control: String, key: String, host: String): String =
            "127.0.0.1:${41640 + starts.incrementAndGet()}\nsecret"

        override fun stop() = Unit

        override fun status(): String {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                release.await()
                return """{"state":"Running"}"""
            }
            return """{"state":"Starting"}"""
        }

        override fun logs() = ""
    }

    @Test
    fun `a poller of a replaced node that comes back late changes nothing`() = runBlocking {
        val engine = BlockingEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val tailnet = Tailnet(dir.root, scope, engine, Dispatchers.IO)
            tailnet.ensure(home)
            assertThat(engine.entered.await(5, TimeUnit.SECONDS)).isTrue()

            // The first node's poller hangs in its status call; the node is replaced meanwhile.
            tailnet.ensure(home.copy(hostname = "phone"))
            withTimeout(5_000) { while (engine.starts.get() < 2 || engine.calls.get() < 2) delay(20) }
            val seen = CopyOnWriteArrayList<TailnetStatus>()
            val watching = launch(Dispatchers.IO) { tailnet.status.collect { seen += it } }
            delay(200)

            engine.release.countDown()
            delay(500)
            watching.cancel()

            assertThat(seen.map { it.state }).doesNotContain("Running")
            assertThat(tailnet.status.value.state).isEqualTo("Starting")
            assertThat(tailnet.proxy.value).isNull()
        } finally {
            engine.release.countDown()
            scope.cancel()
        }
    }
}
