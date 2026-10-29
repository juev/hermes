package org.evsyukov.hermes.push

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.remote.HermesHttp
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PushApiTest {
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
    fun `a subscription names the device, so the server can drop the endpoints it lost`() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().body("""{"ok": true}""").build())
        val strings = AppStrings(ApplicationProvider.getApplicationContext<Application>().resources)
        val http = HermesHttp(server.url("/"), OkHttpClient(), tokens = { null }, saveTokens = {}, strings = strings)

        PushApi(http).subscribe("https://ntfy.example.org/upAbC?up=1", "key", "secret", "CPH2747", "9774d56d682e549c")

        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/api/plugins/hermes-push/subscriptions")
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertThat(body.mapValues { it.value.jsonPrimitive.content }).containsExactly(
            "endpoint", "https://ntfy.example.org/upAbC?up=1",
            "p256dh", "key",
            "auth", "secret",
            "name", "CPH2747",
            "device", "9774d56d682e549c",
        )
    }
}
