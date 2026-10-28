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
}
