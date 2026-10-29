package org.evsyukov.hermes.data.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class AppSettingsTest {
    @Test
    fun `the permanent connection is unknown until the disk is read, not the default off`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AppSettings(context, CoroutineScope(Dispatchers.IO)).update { it.copy(keepConnected = true) }

        // A process the system restarted for the service, before its settings were read from disk.
        val restarted = AppSettings(context, CoroutineScope(StandardTestDispatcher()))
        assertThat(restarted.current.value.keepConnected).isFalse()
        assertThat(restarted.keepConnected.value).isNull()

        val read = AppSettings(context, CoroutineScope(Dispatchers.IO))
        assertThat(withTimeout(5_000) { read.keepConnected.filterNotNull().first() }).isTrue()
    }

    @Test
    fun `the cache limit starts at 500 MB and keeps any size the user sets`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertThat(Settings().cacheLimitMb).isEqualTo(500)
        AppSettings(context, CoroutineScope(Dispatchers.IO)).update { it.copy(cacheLimitMb = 20_000) }

        val read = AppSettings(context, CoroutineScope(Dispatchers.IO))
        assertThat(withTimeout(5_000) { read.current.first { it.cacheLimitMb != 500 } }.cacheLimitMb).isEqualTo(20_000)
    }

    @Test
    fun `a typed cache limit is a whole number of megabytes from zero up, without an upper bound`() {
        assertThat(cacheLimitOf(" 250 ")).isEqualTo(250)
        assertThat(cacheLimitOf("0")).isEqualTo(0)
        assertThat(cacheLimitOf("100000")).isEqualTo(100_000)
        assertThat(cacheLimitOf("-1")).isNull()
        assertThat(cacheLimitOf("1.5")).isNull()
        assertThat(cacheLimitOf("")).isNull()
    }
}
