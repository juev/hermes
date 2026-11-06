package org.evsyukov.hermes.ui.setup

import java.util.Locale
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import androidx.compose.ui.platform.LocalResources
import androidx.compose.runtime.CompositionLocalProvider
import android.content.res.Configuration
import android.app.Application
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.server.ProxySettings
import org.evsyukov.hermes.data.server.ProxyType
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.tailnet.TailnetStatus
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.IOException

/**
 * Removing a saved profile is local and cannot be taken back, so the form asks first. The dialog
 * belongs to the profile shown when it was opened: switching the form to another server takes it
 * away, and a late delete never navigates the screen of the new one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerEditRemoveTest {
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

    private fun show(
        existing: ServerProfile?,
        servers: List<ServerProfile>,
        onDelete: suspend (ServerProfile) -> Boolean,
        onBack: () -> Unit = {},
        onCheck: suspend (ServerProfile) -> String = { "" },
        onSave: suspend (ServerProfile) -> Unit = {},
    ) {
        compose.setContent {
            HermesTheme {
                ServerEditContent(
                    existing = existing,
                    draft = null,
                    servers = servers,
                    tailnetStatus = TailnetStatus(available = false),
                    onCheck = onCheck,
                    onSave = onSave,
                    onDelete = onDelete,
                    onBack = onBack,
                    onSaved = {},
                )
            }
        }
    }

    private fun openRemoveDialog() {
        compose.onNodeWithContentDescription(string(R.string.setup_delete_server)).performClick()
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
    fun `removing a saved profile names it and waits for the confirmation`() {
        val deleted = mutableListOf<ServerProfile>()
        var checked = 0
        var saved = 0
        show(alpha, listOf(alpha, beta), onDelete = { deleted += it; true }, onCheck = { checked++; "" }, onSave = { saved++ })

        openRemoveDialog()

        compose.onNodeWithText(string(R.string.setup_remove_confirm_title, alpha.name)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.setup_remove_confirm_text)).assertIsDisplayed()
        // The form is still there; no local state has been dropped.
        compose.onNodeWithText(alpha.name).assertIsDisplayed()
        assertThat(deleted).isEmpty()
        assertThat(checked).isEqualTo(0)
        assertThat(saved).isEqualTo(0)
    }

    @Test
    fun `confirming removes the captured profile once and goes back`() {
        val deleted = mutableListOf<ServerProfile>()
        var backs = 0
        show(alpha, listOf(alpha, beta), onDelete = { deleted += it; true }, onBack = { backs++ })

        openRemoveDialog()
        compose.onNodeWithText(string(R.string.setup_remove_confirm_action)).performClick()
        compose.waitForIdle()

        assertThat(deleted).containsExactly(alpha)
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `cancelling makes no destructive call`() {
        val deleted = mutableListOf<ServerProfile>()
        var backs = 0
        show(alpha, listOf(alpha, beta), onDelete = { deleted += it; true }, onBack = { backs++ })

        openRemoveDialog()
        compose.onNodeWithText(string(R.string.common_cancel)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.setup_remove_confirm_title, alpha.name)).assertDoesNotExist()
        assertThat(deleted).isEmpty()
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `back and an outside dismissal make no destructive call`() {
        val deleted = mutableListOf<ServerProfile>()
        show(alpha, listOf(alpha, beta), onDelete = { deleted += it; true })

        openRemoveDialog()
        compose.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.setup_remove_confirm_title, alpha.name)).assertDoesNotExist()

        openRemoveDialog()
        dismissFromPlatform()
        compose.onNodeWithText(string(R.string.setup_remove_confirm_title, alpha.name)).assertDoesNotExist()

        assertThat(deleted).isEmpty()
    }

    @Test
    fun `a pending removal ignores a second confirm`() {
        val deleted = mutableListOf<ServerProfile>()
        val gate = CompletableDeferred<Unit>()
        show(alpha, listOf(alpha, beta), onDelete = { deleted += it; gate.await(); true })

        openRemoveDialog()
        compose.onNodeWithText(string(R.string.setup_remove_confirm_action)).performClick()
        compose.waitUntil(5_000) { deleted.size == 1 }

        compose.onNodeWithText(string(R.string.setup_remove_confirm_action)).assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.setup_remove_confirm_action)).performClick()
        compose.waitForIdle()
        assertThat(deleted).hasSize(1)

        gate.complete(Unit)
        compose.waitForIdle()
        assertThat(deleted).hasSize(1)
    }

    @Test
    fun `switching the form to another server while the dialog is open invalidates it`() {
        val deleted = mutableListOf<ServerProfile>()
        var current by mutableStateOf(alpha)
        compose.setContent {
            HermesTheme {
                ServerEditContent(
                    existing = current,
                    draft = null,
                    servers = listOf(alpha, beta),
                    tailnetStatus = TailnetStatus(available = false),
                    onCheck = { "" },
                    onSave = {},
                    onDelete = { deleted += it; true },
                    onBack = {},
                    onSaved = {},
                )
            }
        }

        openRemoveDialog()
        compose.onNodeWithText(string(R.string.setup_remove_confirm_title, alpha.name)).assertIsDisplayed()

        compose.runOnIdle { current = beta }
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.setup_remove_confirm_title, beta.name)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.setup_remove_confirm_title, alpha.name)).assertDoesNotExist()
        assertThat(deleted).isEmpty()
    }

    @Test
    fun `a removal that finishes after the form moved to another server does not navigate`() {
        val deleted = mutableListOf<ServerProfile>()
        val gate = CompletableDeferred<Unit>()
        var current by mutableStateOf(alpha)
        var backs = 0
        compose.setContent {
            HermesTheme {
                ServerEditContent(
                    existing = current,
                    draft = null,
                    servers = listOf(alpha, beta),
                    tailnetStatus = TailnetStatus(available = false),
                    onCheck = { "" },
                    onSave = {},
                    onDelete = { deleted += it; gate.await(); true },
                    onBack = { backs++ },
                    onSaved = {},
                )
            }
        }

        openRemoveDialog()
        compose.onNodeWithText(string(R.string.setup_remove_confirm_action)).performClick()
        compose.waitUntil(5_000) { deleted.size == 1 }

        // The user moved to the other profile while the local removal was still running.
        compose.runOnIdle { current = beta }
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.setup_remove_confirm_title, alpha.name)).assertDoesNotExist()
        gate.complete(Unit)
        compose.waitForIdle()

        assertThat(deleted).containsExactly(alpha)
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `a failed removal re-enables the action and names the profile it did not remove`() {
        var attempts = 0
        var backs = 0
        show(alpha, listOf(alpha, beta), onDelete = { attempts++; throw IOException("storage is unavailable") }, onBack = { backs++ })

        openRemoveDialog()
        compose.onNodeWithText(string(R.string.setup_remove_confirm_action)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.setup_remove_failed, alpha.name)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.setup_remove_confirm_action)).assertIsEnabled()
        assertThat(attempts).isEqualTo(1)
        assertThat(backs).isEqualTo(0)

        // The user can try again after the failure.
        compose.onNodeWithText(string(R.string.setup_remove_confirm_action)).performClick()
        compose.waitForIdle()
        assertThat(attempts).isEqualTo(2)
    }
    @Test
    fun `removal false result uses resources updated while pending and retry keeps captured target`() = configurationAwareFailure(false)

    @Test
    fun `removal exception uses resources updated while pending and retry keeps captured target`() = configurationAwareFailure(true)

    private fun configurationAwareFailure(throwing: Boolean) {
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }).resources
        val russian = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale("ru")) }).resources
        var resources by mutableStateOf(english)
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<ServerProfile>()
        val producers = mutableListOf<Job>()
        var backs = 0
        var primary: Throwable? = null
        compose.setContent {
            CompositionLocalProvider(LocalResources provides resources) {
                HermesTheme {
                ServerEditContent(
                    existing = alpha, draft = null, servers = listOf(alpha, beta),
                    tailnetStatus = TailnetStatus(available = false),
                    onCheck = { "" }, onSave = {}, onSaved = {}, onBack = { backs++ },
                    onDelete = { captured ->
                        calls += captured
                        producers += currentCoroutineContext().job
                        if (calls.size == 1) {
                            release.await()
                            if (throwing) throw IOException("synthetic removal failure")
                            false
                        } else true
                    },
                )
                }
            }
        }
        try {
            compose.onNodeWithContentDescription(english.getString(R.string.setup_delete_server)).performClick()
            compose.onNodeWithText(english.getString(R.string.setup_remove_confirm_action)).performClick()
            compose.runOnIdle {
                assertThat(calls).containsExactly(alpha)
                assertThat(producers).hasSize(1)
                assertThat(producers.single().isCompleted).isFalse()
                resources = russian
            }
            // This actual dialog recomposed with the replacement resources before completion.
            compose.onNodeWithText(russian.getString(R.string.setup_remove_confirm_title, alpha.name)).assertIsDisplayed()
            assertThat(context.getString(R.string.setup_remove_failed, alpha.name)).isNotEqualTo(russian.getString(R.string.setup_remove_failed, alpha.name))
            compose.runOnIdle { release.complete(Unit) }
            compose.waitForIdle()
            compose.onNodeWithText(russian.getString(R.string.setup_remove_failed, alpha.name)).assertIsDisplayed()
            compose.onNodeWithText(russian.getString(R.string.setup_remove_confirm_action)).assertIsEnabled().performClick()
            compose.waitForIdle()
            assertThat(calls).containsExactly(alpha, alpha).inOrder()
            assertThat(backs).isEqualTo(1)
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
