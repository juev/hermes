package org.evsyukov.hermes.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the build packs for the Licenses screen, read the way the app reads it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class LicenseAssetsTest {
    @Test
    fun `the build puts the app license and the library licenses into the APK`() {
        val licenses = loadLicenses(ApplicationProvider.getApplicationContext())
        assertThat(licenses.app.text).startsWith("MIT License")
        val okhttp = licenses.libraries.first { it.name.contains("okhttp", ignoreCase = true) }
        assertThat(okhttp.licenses.map { it.name }).contains("Apache-2.0")
        // BSD asks for the copyright of the library itself, not the placeholder of the SPDX text.
        val protobuf = licenses.libraries.first { it.name.contains("Protocol Buffers", ignoreCase = true) }
        assertThat(protobuf.licenses.single().text).startsWith("Copyright 2008 Google Inc.")
        assertThat(licenses.libraries.flatMap { it.licenses }.none { "<<var;" in it.text }).isTrue()
    }
}
