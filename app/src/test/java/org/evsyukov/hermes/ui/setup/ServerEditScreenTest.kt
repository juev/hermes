package org.evsyukov.hermes.ui.setup

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.server.ProxySettings
import org.evsyukov.hermes.data.server.ProxyType
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.TailnetSettings
import org.evsyukov.hermes.tailnet.TailnetStatus
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The server form on a build without the embedded tailnet, and on one that has it. The node itself
 * never runs here: [TailnetStatus] stands for what the build reports at compile time.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerEditScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(id: Int) = context.getString(id)

    private fun tailnetProfile(id: String = "existing") = ServerProfile(
        id = id,
        name = "Home",
        baseUrl = "https://hermes.example.org",
        proxy = ProxySettings(ProxyType.TAILSCALE),
        tailnet = TailnetSettings("https://hs.example.org", "hskey-auth-abc", "phone"),
    )

    private fun directProfile() = ServerProfile(
        id = "direct",
        name = "Direct",
        baseUrl = "https://hermes.example.org",
        proxy = ProxySettings(ProxyType.NONE),
    )

    private fun show(
        status: TailnetStatus,
        existing: ServerProfile? = null,
        draft: ServerProfile? = null,
        servers: List<ServerProfile> = listOfNotNull(existing),
        onCheck: suspend (ServerProfile) -> String = { string(R.string.setup_check_ok) },
        onSave: suspend (ServerProfile) -> Unit = {},
        onSaved: () -> Unit = {},
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HermesTheme {
                    ServerEditContent(
                        existing = existing,
                        draft = draft,
                        servers = servers,
                        tailnetStatus = status,
                        onCheck = onCheck,
                        onSave = onSave,
                        onDelete = { true },
                        onBack = {},
                        onSaved = onSaved,
                    )
                }
            }
        }
    }

    @Test
    fun `minimal channel is an explicit saved connection choice rather than silent fallback`() {
        val existing = directProfile()
        var saved: ServerProfile? = null
        var checked: ServerProfile? = null
        show(TailnetStatus(available = false), existing = existing, onSave = { saved = it }, onCheck = { checked = it; "OK" })
        compose.onNodeWithText("Android channel plugin").performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.setup_check)).performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(checked?.androidChannel).isTrue()
        compose.onNodeWithText(string(R.string.common_save)).performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(saved?.androidChannel).isTrue()
        assertThat(saved?.id).isEqualTo(existing.id)
        assertThat(saved?.baseUrl).isEqualTo(existing.baseUrl)
    }

    @Test
    fun `without the embedded node a new server cannot pick Tailscale and is told what to use instead`() {
        show(TailnetStatus(available = false))

        compose.onNodeWithText("Tailscale").assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.setup_tailscale_missing)).performScrollTo().assertIsDisplayed()

        compose.onNode(hasSetTextAction() and hasText(string(R.string.setup_address))).performTextInput("hermes.example.org")
        compose.onNodeWithText(string(R.string.setup_route_direct)).performClick()

        compose.onNodeWithText(string(R.string.setup_check)).assertIsEnabled()
        compose.onNodeWithText(string(R.string.common_save)).assertIsEnabled()
    }

    @Test
    fun `an imported tailnet server keeps its fields and saves over Direct after an explicit choice`() {
        val imported = tailnetProfile()
        var saved: ServerProfile? = null
        var checked = 0
        show(
            status = TailnetStatus(available = false),
            existing = imported,
            onCheck = { checked++; string(R.string.setup_check_ok) },
            onSave = { saved = it },
        )

        compose.onNodeWithText("Tailscale").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText("https://hs.example.org").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.setup_check)).assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.common_save)).assertIsNotEnabled()

        compose.onNodeWithText(string(R.string.setup_route_direct)).performClick()
        compose.onNodeWithText(string(R.string.setup_check)).assertIsEnabled()
        compose.onNodeWithText(string(R.string.common_save)).performScrollTo().performClick()
        compose.waitForIdle()

        assertThat(checked).isEqualTo(0)
        assertThat(saved).isNotNull()
        assertThat(saved!!.id).isEqualTo(imported.id)
        assertThat(saved!!.baseUrl).isEqualTo(imported.baseUrl)
        assertThat(saved!!.proxy.type).isEqualTo(ProxyType.NONE)
        assertThat(saved!!.tailnet).isEqualTo(imported.tailnet)
    }

    @Test
    fun `a build with the node saves a server whether the node is stopped, starting or failed`() {
        val draft = tailnetProfile("draft")
        var status by mutableStateOf(TailnetStatus(available = true, state = "Stopped"))
        var saved: ServerProfile? = null
        compose.setContent {
            HermesTheme {
                ServerEditContent(
                    existing = null,
                    draft = draft,
                    servers = emptyList(),
                    tailnetStatus = status,
                    onCheck = { string(R.string.setup_check_ok) },
                    onSave = { saved = it },
                    onDelete = { true },
                    onBack = {},
                    onSaved = {},
                )
            }
        }

        compose.onNodeWithText("Tailscale").assertIsSelected().assertIsEnabled()
        compose.onNodeWithText(string(R.string.setup_auth_key)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.common_save)).assertIsEnabled()

        status = TailnetStatus(available = true, state = "Starting", authUrl = "https://login.tailscale.com/a/example")
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.setup_tailnet_sign_in)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.common_save)).assertIsEnabled()

        status = TailnetStatus(available = true, state = "Error", error = "no key")
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.common_save)).performScrollTo().performClick()
        compose.waitForIdle()

        assertThat(saved).isNotNull()
        assertThat(saved!!.proxy.type).isEqualTo(ProxyType.TAILSCALE)
        assertThat(saved!!.tailnet).isEqualTo(draft.tailnet)
    }

    @Test
    fun `an ordinary Direct profile saves even when the build and the network cannot carry Tailscale`() {
        val direct = directProfile()
        var saved: ServerProfile? = null
        show(status = TailnetStatus(available = false, state = "Stopped"), existing = direct, onSave = { saved = it })

        compose.onNodeWithText("Tailscale").assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.setup_check)).assertIsEnabled()
        compose.onNodeWithText(string(R.string.common_save)).performScrollTo().performClick()
        compose.waitForIdle()

        assertThat(saved).isNotNull()
        assertThat(saved!!.id).isEqualTo(direct.id)
        assertThat(saved!!.proxy.type).isEqualTo(ProxyType.NONE)
    }

    @Test
    @Config(qualifiers = "ru-w360dp-h800dp")
    fun `the Russian explanation and the controls fit a narrow screen with a large font`() {
        show(TailnetStatus(available = false), existing = tailnetProfile(), fontScale = 1.5f)

        val root = compose.onRoot().getBoundsInRoot()
        val explanation = compose.onNodeWithText(string(R.string.setup_tailscale_missing)).performScrollTo().assertIsDisplayed().getBoundsInRoot()
        assertThat(explanation.left.value).isAtLeast(root.left.value)
        assertThat(explanation.right.value).isAtMost(root.right.value)
        assertThat(explanation.bottom.value).isAtMost(root.bottom.value)

        compose.onNodeWithText("Tailscale").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.setup_route_direct)).performClick()
        compose.onNodeWithText(string(R.string.common_save)).performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(string(R.string.setup_check)).performScrollTo().assertIsDisplayed().assertIsEnabled()
    }
}
