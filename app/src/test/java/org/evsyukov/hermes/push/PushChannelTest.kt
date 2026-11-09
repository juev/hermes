package org.evsyukov.hermes.push

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.testing.ChatTestResources
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PushChannelTest {
    @Test
    fun `selecting the channel retires only this phones old global subscription before registration`() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val resources = ChatTestResources()
        val server = MockWebServer()
        val file = File.createTempFile("push-retirement-", ".preferences_pb", app.cacheDir)
        val backing = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val owner = "a".repeat(64)
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse = MockResponse.Builder().body(
                if (request.url.encodedPath.endsWith("/key")) """{"key":"fixture-vapid","channel":"android-minimal-v1","channel_owner":"$owner"}"""
                else """{"ok":true}"""
            ).build()
        }
        server.start()
        try {
            val profile = ServerProfile("push-retirement", "Channel", server.url("/").toString(), androidChannel = true)
            backing.edit { it[stringPreferencesKey("endpoint:${profile.id}")] = "https://push.example/old-phone" }
            val cipher = object : TokenCipher {
                override fun encrypt(plain: String) = plain
                override fun decrypt(sealed: String) = sealed
            }
            val store = resources.store(app, cipher, scope, profile)
            val session = ServerSession(profile, OkHttpClient(), store, AppStrings(app.resources))
            val distributor = object : PushDistributor {
                override fun installed() = listOf(Distributor("fixture", "Fixture distributor"))
                override fun saved() = "fixture"
                override fun save(packageName: String) {}
                override fun register(instance: String, name: String, vapid: String) = true
                override fun unregister(instance: String) {}
            }
            val manager = PushManager(app, distributor, { session }, { true }, backing)
            manager.reconcile()
            manager.onNewEndpoint(profile.id, "https://push.example/new-phone", "fixture-public", "fixture-auth")
            val retired = server.takeRequest()
            assertThat(retired.method).isEqualTo("DELETE")
            assertThat(retired.url.encodedPath).isEqualTo("/api/plugins/hermes-push/subscriptions")
            assertThat(retired.body!!.utf8()).contains("https://push.example/old-phone")
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/api/plugins/hermes-android/push/key")
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/api/plugins/hermes-android/push/subscriptions")
            assertThat(server.requestCount).isEqualTo(3)
            assertThat(manager.status.value.state).isEqualTo(PushState.ON)
        } finally {
            resources.finish(scope.coroutineContext.job, listOf("close server" to { server.close() }, "delete push preferences" to { check(!file.exists() || file.delete()) }))
        }
    }

    @Test
    fun `channel pushes require current profile sign in generation and verified channel owner`() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val resources = ChatTestResources()
        val server = MockWebServer()
        val file = File.createTempFile("push-channel-", ".preferences_pb", app.cacheDir)
        val backing = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        server.start()
        try {
            val owner = "a".repeat(64)
            server.enqueue(MockResponse.Builder().body("""{"key":"fixture-vapid","channel":"android-minimal-v1","channel_owner":"$owner"}""").build())
            val profile = ServerProfile("push-channel", "Channel", server.url("/").toString(), androidChannel = true)
            val cipher = object : TokenCipher {
                override fun encrypt(plain: String) = plain
                override fun decrypt(sealed: String) = sealed
            }
            val store = resources.store(app, cipher, scope, profile)
            val session = ServerSession(profile, OkHttpClient(), store, AppStrings(app.resources))
            val distributor = object : PushDistributor {
                override fun installed() = listOf(Distributor("fixture", "Fixture distributor"))
                override fun saved() = "fixture"
                override fun save(packageName: String) {}
                override fun register(instance: String, name: String, vapid: String) = true
                override fun unregister(instance: String) {}
            }
            val manager = PushManager(app, distributor, { session }, { true }, backing)
            manager.reconcile()
            val owned = PushMessage("turn", "own-chat", "android", preview = "Readable", channel = "android-minimal-v1", channelOwner = owner)
            assertThat(manager.accepts(PushMessage("turn", "foreign-chat", "telegram", preview = "PRIVATE"), profile.id)).isFalse()
            assertThat(manager.accepts(owned, "another-profile")).isFalse()
            assertThat(manager.accepts(owned.copy(channelOwner = "b".repeat(64)), profile.id)).isFalse()
            assertThat(manager.accepts(owned, profile.id)).isTrue()
            assertThat(session.signOut(profile.id)).isInstanceOf(org.evsyukov.hermes.data.server.NativeResult.Applied::class.java)
            assertThat(manager.accepts(owned, profile.id)).isFalse()
        } finally {
            resources.finish(scope.coroutineContext.job, listOf("close server" to { server.close() }, "delete push preferences" to { check(!file.exists() || file.delete()) }))
        }
    }
}
