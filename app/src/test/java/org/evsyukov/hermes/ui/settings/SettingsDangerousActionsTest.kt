package org.evsyukov.hermes.ui.settings

import java.util.Locale
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import androidx.compose.ui.platform.LocalResources
import android.content.res.Configuration
import android.app.Application
import android.graphics.Bitmap
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.evsyukov.hermes.ui.LocalSignOutResult
import java.io.File
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.server.ProxySettings
import org.evsyukov.hermes.data.server.ProxyType
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.IOException

/**
 * The two local actions of the settings screen that cannot be taken back: signing out of a server
 * and leaving the embedded tailnet. Both ask first, and a dialog belongs to the server it was opened
 * for; picking another server meanwhile takes it away.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsDangerousActionsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(id: Int) = context.getString(id)

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun profile(id: String, name: String) = ServerProfile(
        id = id,
        name = name,
        baseUrl = "https://$id.example.org",
        proxy = ProxySettings(ProxyType.NONE),
    )

    private val alpha = profile("alpha", "Alpha")
    private val beta = profile("beta", "Beta")

    private fun showSignOut(active: ServerProfile?, onSignOut: suspend (ServerProfile) -> Boolean) {
        compose.setContent {
            HermesTheme { SignOutRow(active = active, onSignOut = onSignOut) }
        }
    }

    private fun showLeaveTailnet(onLeave: () -> Unit) {
        compose.setContent {
            HermesTheme { LeaveTailnetRow(onLeave = onLeave) }
        }
    }

    private fun openSignOut() {
        compose.onNodeWithText(string(R.string.settings_sign_out)).performClick()
        compose.waitForIdle()
    }

    private fun openLeaveTailnet() {
        compose.onNodeWithText(string(R.string.settings_tailnet_leave)).performClick()
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.waitForIdle()
    }

    private fun dismissFromPlatform() {
        compose.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()
            // Compose dismisses on a press that starts and ends outside its content.
            val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, -1000f, -1000f, 0)
            val up = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, -1000f, -1000f, 0)
            dialog.onTouchEvent(down)
            dialog.onTouchEvent(up)
            down.recycle()
            up.recycle()
        }
        compose.waitForIdle()
    }

    @Test
    fun `signing out names the active server and removes only local session data on confirm`() {
        val calls = mutableListOf<ServerProfile>()
        showSignOut(alpha) { calls += it; true }

        openSignOut()
        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_title, alpha.name)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_text, alpha.name)).assertIsDisplayed()
        assertThat(calls).isEmpty()

        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_action)).performClick()
        compose.waitForIdle()

        assertThat(calls).containsExactly(alpha)
    }

    @Test
    fun `cancelling the sign-out makes no destructive call`() {
        val calls = mutableListOf<ServerProfile>()
        showSignOut(alpha) { calls += it; true }

        openSignOut()
        compose.onNodeWithText(string(R.string.common_cancel)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_title, alpha.name)).assertDoesNotExist()
        assertThat(calls).isEmpty()
    }

    @Test
    fun `back and an outside dismissal of the sign-out make no destructive call`() {
        val calls = mutableListOf<ServerProfile>()
        showSignOut(alpha) { calls += it; true }

        openSignOut()
        pressBack()
        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_title, alpha.name)).assertDoesNotExist()

        openSignOut()
        dismissFromPlatform()
        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_title, alpha.name)).assertDoesNotExist()

        assertThat(calls).isEmpty()
    }

    @Test
    fun `a pending sign-out ignores a second confirm`() {
        val calls = mutableListOf<ServerProfile>()
        val gate = CompletableDeferred<Unit>()
        showSignOut(alpha) { calls += it; gate.await(); true }

        openSignOut()
        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_action)).performClick()
        compose.waitUntil(5_000) { calls.size == 1 }

        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_action)).assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_action)).performClick()
        compose.waitForIdle()
        assertThat(calls).hasSize(1)

        gate.complete(Unit)
        compose.waitForIdle()
        assertThat(calls).hasSize(1)
    }

    @Test
    fun `switching the active server while the sign-out dialog is open invalidates it`() {
        val calls = mutableListOf<ServerProfile>()
        var active by mutableStateOf(alpha)
        compose.setContent {
            HermesTheme { SignOutRow(active = active, onSignOut = { calls += it; true }) }
        }

        openSignOut()
        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_title, alpha.name)).assertIsDisplayed()

        compose.runOnIdle { active = beta }
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_title, alpha.name)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_title, beta.name)).assertDoesNotExist()
        assertThat(calls).isEmpty()
    }

    @Test
    fun `leaving the tailnet describes the node identity it drops and waits for confirmation`() {
        var leaves = 0
        showLeaveTailnet { leaves++ }

        openLeaveTailnet()
        compose.onNodeWithText(string(R.string.settings_tailnet_leave_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_tailnet_leave_confirm_text)).assertIsDisplayed()
        assertThat(leaves).isEqualTo(0)

        compose.onNodeWithText(string(R.string.settings_tailnet_leave_confirm_action)).performClick()
        compose.waitForIdle()
        assertThat(leaves).isEqualTo(1)
    }

    @Test
    fun `cancelling or dismissing the tailnet dialog keeps the node`() {
        var leaves = 0
        showLeaveTailnet { leaves++ }

        openLeaveTailnet()
        compose.onNodeWithText(string(R.string.common_cancel)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.settings_tailnet_leave_confirm_title)).assertDoesNotExist()

        openLeaveTailnet()
        pressBack()
        compose.onNodeWithText(string(R.string.settings_tailnet_leave_confirm_title)).assertDoesNotExist()

        openLeaveTailnet()
        dismissFromPlatform()
        compose.onNodeWithText(string(R.string.settings_tailnet_leave_confirm_title)).assertDoesNotExist()

        assertThat(leaves).isEqualTo(0)
    }

    @Test
    fun `a failed sign-out re-enables the action and names the server still signed in`() {
        var attempts = 0
        showSignOut(alpha) { attempts++; throw IOException("storage is unavailable") }

        openSignOut()
        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_action)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.settings_sign_out_failed, alpha.name)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_action)).assertIsEnabled()
        assertThat(attempts).isEqualTo(1)

        // The user can try again after the failure.
        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_action)).performClick()
        compose.waitForIdle()
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    @Config(qualifiers = "ru-w360dp-h800dp")
    fun `the Russian dialogs fit a narrow screen with a large font`() {
        var leaves = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                HermesTheme { LeaveTailnetRow(onLeave = { leaves++ }) }
            }
        }

        openLeaveTailnet()
        val title = compose.onNodeWithText(string(R.string.settings_tailnet_leave_confirm_title)).assertIsDisplayed().getBoundsInRoot()
        val text = compose.onNodeWithText(string(R.string.settings_tailnet_leave_confirm_text)).assertIsDisplayed().getBoundsInRoot()
        assertThat(title.left.value).isAtLeast(0f)
        assertThat(title.right.value).isAtMost(360f)
        assertThat(text.left.value).isAtLeast(0f)
        assertThat(text.right.value).isAtMost(360f)
        compose.onNodeWithText(string(R.string.settings_tailnet_leave_confirm_action)).assertIsDisplayed()
        assertThat(leaves).isEqualTo(0)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `the English sign-out dialog fits a narrow screen with a large font`() {
        val calls = mutableListOf<ServerProfile>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                HermesTheme { SignOutRow(active = alpha, onSignOut = { calls += it; true }) }
            }
        }

        openSignOut()
        val title = compose.onNodeWithText(string(R.string.settings_sign_out_confirm_title, alpha.name)).assertIsDisplayed().getBoundsInRoot()
        val text = compose.onNodeWithText(string(R.string.settings_sign_out_confirm_text, alpha.name)).assertIsDisplayed().getBoundsInRoot()
        assertThat(title.left.value).isAtLeast(0f)
        assertThat(title.right.value).isAtMost(360f)
        assertThat(text.left.value).isAtLeast(0f)
        assertThat(text.right.value).isAtMost(360f)
        assertThat(calls).isEmpty()
    }
    private fun partialNotice(stem: String) {
        var prepared = 0
        var executed = 0
        val notice = string(R.string.settings_sign_out_cleanup_failed, alpha.name)
        compose.setContent {
            val density = LocalDensity.current
            val host = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                HermesTheme {
                    Scaffold(snackbarHost = { SnackbarHost(host) }) { padding ->
                        Box(Modifier.padding(padding)) {
                            SignOutRow(
                                active = alpha,
                                onSignOut = { throw AssertionError("The captured action must be used") },
                                prepareSignOut = {
                                    prepared++
                                    {
                                        executed++
                                        scope.launch { host.showSnackbar(notice, duration = SnackbarDuration.Indefinite) }
                                        LocalSignOutResult.INCOMPLETE
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        openSignOut()
        val folder = System.getenv("HERMES_TEST_CAPTURE_DIR")?.let { File(it).apply { mkdirs() } }
        if (folder != null) compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder, "$stem-confirm.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertThat(prepared).isEqualTo(0)
        assertThat(executed).isEqualTo(0)
        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_action)).performClick()
        compose.waitForIdle()
        assertThat(prepared).isEqualTo(1)
        assertThat(executed).isEqualTo(1)
        compose.onNodeWithText(string(R.string.settings_sign_out_confirm_title, alpha.name)).assertDoesNotExist()
        compose.onNodeWithText(notice).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_sign_out_failed, alpha.name)).assertDoesNotExist()
        if (folder != null) compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder, "$stem-partial.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    @Config(qualifiers = "en-w360dp-h800dp")
    fun `incomplete signout shows truthful English notice at 360dp and enlarged font`() = partialNotice("en-360-font150")

    @Test
    @Config(qualifiers = "ru-w360dp-h800dp")
    fun `incomplete signout shows truthful Russian notice at 360dp and enlarged font`() = partialNotice("ru-360-font150")

    @Test
    fun `signout false result uses resources updated while pending and retry keeps captured target`() = configurationAwareFailure(false)

    @Test
    fun `signout exception uses resources updated while pending and retry keeps captured target`() = configurationAwareFailure(true)

    private fun configurationAwareFailure(throwing: Boolean) {
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }).resources
        val russian = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale("ru")) }).resources
        var resources by mutableStateOf(english)
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<ServerProfile>()
        val producers = mutableListOf<Job>()
        var primary: Throwable? = null
        compose.setContent {
            CompositionLocalProvider(LocalResources provides resources) {
                HermesTheme {
                SignOutRow(active = alpha, onSignOut = { captured ->
                    calls += captured
                    producers += currentCoroutineContext().job
                    if (calls.size == 1) {
                        release.await()
                        if (throwing) throw IOException("synthetic signout failure")
                        false
                    } else true
                })
                }
            }
        }
        try {
            compose.onNodeWithText(english.getString(R.string.settings_sign_out)).performClick()
            compose.onNodeWithText(english.getString(R.string.settings_sign_out_confirm_action)).performClick()
            compose.runOnIdle {
                assertThat(calls).containsExactly(alpha)
                assertThat(producers).hasSize(1)
                assertThat(producers.single().isCompleted).isFalse()
                resources = russian
            }
            // This actual dialog recomposed with the replacement resources before completion.
            compose.onNodeWithText(russian.getString(R.string.settings_sign_out_confirm_title, alpha.name)).assertIsDisplayed()
            assertThat(context.getString(R.string.settings_sign_out_failed, alpha.name)).isNotEqualTo(russian.getString(R.string.settings_sign_out_failed, alpha.name))
            compose.runOnIdle { release.complete(Unit) }
            compose.waitForIdle()
            compose.onNodeWithText(russian.getString(R.string.settings_sign_out_failed, alpha.name)).assertIsDisplayed()
            compose.onNodeWithText(russian.getString(R.string.settings_sign_out_confirm_action)).assertIsEnabled().performClick()
            compose.waitForIdle()
            assertThat(calls).containsExactly(alpha, alpha).inOrder()
            compose.onNodeWithText(russian.getString(R.string.settings_sign_out_confirm_title, alpha.name)).assertDoesNotExist()
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            try {
                compose.runOnIdle { release.complete(Unit) }
                compose.waitForIdle()
                runBlocking { withTimeout(10_000) { producers.forEach { it.cancel(); it.join() } } }
            } catch (cleanup: Throwable) {
                val first = primary
                if (first == null) throw cleanup else first.addSuppressed(cleanup)
            }
        }
    }

}
