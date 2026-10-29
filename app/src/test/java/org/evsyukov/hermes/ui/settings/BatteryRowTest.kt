package org.evsyukov.hermes.ui.settings

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// A bare application: the row needs none of the services HermesApplication starts.
@Config(sdk = [36], application = Application::class)
class BatteryRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun exempt(on: Boolean) =
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, on)

    @Test
    fun `without the permanent connection an optimized app shows no row`() {
        exempt(false)
        compose.setContent { HermesTheme { BatteryRow(keepConnected = false) } }
        assertThat(compose.onAllNodes(hasText("Battery optimization")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `the permanent connection shows that optimization is on`() {
        exempt(false)
        compose.setContent { HermesTheme { BatteryRow(keepConnected = true) } }
        compose.onNodeWithText("Battery optimization").assertIsDisplayed()
        compose.onNodeWithText("On: while the phone sleeps, the system may close the permanent connection").assertIsDisplayed()
    }

    @Test
    fun `an exempt app shows the row without the permanent connection, so it can be undone`() {
        exempt(true)
        compose.setContent { HermesTheme { BatteryRow(keepConnected = false) } }
        compose.onNodeWithText("Off: the connection stays open while the phone sleeps, and the battery drains faster").assertIsDisplayed()
    }

    @Test
    fun `the row opens the system list instead of asking for the exemption`() {
        exempt(true)
        compose.setContent { HermesTheme { BatteryRow(keepConnected = true) } }
        compose.onNodeWithText("Battery optimization").performClick()
        assertThat(shadowOf(app).nextStartedActivity.action).isEqualTo(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    @Test
    fun `the state is read again when the user comes back`() {
        exempt(false)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { HermesTheme { BatteryRow(keepConnected = true) } } }
        compose.onNodeWithText("On: while the phone sleeps, the system may close the permanent connection").assertIsDisplayed()

        // The user leaves for the system list and turns optimization off there.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        exempt(true)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("Off: the connection stays open while the phone sleeps, and the battery drains faster").assertIsDisplayed()
    }

    @Test
    fun `the app does not ask for the exemption`() {
        @Suppress("DEPRECATION")
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertThat(requested.toList()).doesNotContain(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    }
}
