package org.evsyukov.hermes.data.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class HermesHttpTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `an answer is read off the thread of the caller, which may be the main one`() = runBlocking<Unit> {
        // The body comes well after the headers, so it is read from the network, not from a buffer.
        server.enqueue(MockResponse.Builder().body("""{"ok": true}""").bodyDelay(300, TimeUnit.MILLISECONDS).build())
        val readers = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().socketFactory(RecordingSockets(readers)).build()
        val strings = AppStrings(ApplicationProvider.getApplicationContext<Application>().resources)
        val http = HermesHttp(server.url("/"), client, tokens = { null }, saveTokens = {}, strings = strings)
        val caller = Executors.newSingleThreadExecutor { Thread(it, "caller") }

        val answer = try {
            withContext(caller.asCoroutineDispatcher()) { http.getJson("api/system/stats") }
        } finally {
            caller.shutdown()
        }

        assertThat(answer.toString()).isEqualTo("""{"ok":true}""")
        assertThat(readers).isNotEmpty()
        // Debug builds of coroutines add the coroutine to the name of the thread.
        assertThat(readers.filter { it.startsWith("caller") }).isEmpty()
    }
}

/** Sockets that note the thread of every read. */
private class RecordingSockets(private val readers: MutableList<String>) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun getInputStream(): InputStream = object : FilterInputStream(super.getInputStream()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                readers += Thread.currentThread().name
                return super.read(b, off, len)
            }
        }
    }

    override fun createSocket(host: String, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: InetAddress, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = throw UnsupportedOperationException()
}
