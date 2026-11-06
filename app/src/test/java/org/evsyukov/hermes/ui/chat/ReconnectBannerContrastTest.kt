package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.ChatController
import org.evsyukov.hermes.chat.LiveGateway
import org.evsyukov.hermes.chat.LiveServer
import org.evsyukov.hermes.data.settings.Settings
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.evsyukov.hermes.ui.theme.ThemeMode
import org.evsyukov.hermes.ui.theme.chatColors
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The reconnect banner is the one place where [ChatScreen] paints text straight onto [ChatColors.warning].
 * The palette test proves that the pair is readable; this test proves the screen actually uses that pair,
 * which reading the colours alone cannot: a call site left on the shared on-contrast colour keeps passing
 * the token check while the banner goes unreadable in the light theme.
 *
 * The oracle matches both captured colours to the expected tokens and then computes the ratio of the
 * captured pair. A darkest-pixel threshold alone would accept text that is dark but still below 4.5:1.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReconnectBannerContrastTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = LiveGateway()
    private val server = LiveServer(gateway)
    private val db = LiveServer.database(app)

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    @Test
    fun `the light banner paints dark text on the warning background`() = assertBanner(ThemeMode.LIGHT)

    @Test
    fun `the dark banner paints dark text on the warning background`() = assertBanner(ThemeMode.DARK)

    private fun assertBanner(mode: ThemeMode) {
        val controller = ChatController(server.session(app, scope, db), gateway, scope, AppStrings(app.resources), "s1")
        compose.setContent {
            HermesTheme(mode) {
                ChatScreen(
                    controller = controller, settings = Settings(), serverName = "Home", connected = false,
                    onMenu = {}, onNewChat = {}, openFile = {}, loadFile = { null }, loadModels = { error("not opened") },
                    openLocal = {}, onShown = { _, _ -> }, draft = ChatDraft(),
                )
            }
        }
        compose.waitForIdle()

        val pixels = compose.onNodeWithText(app.getString(R.string.chat_reconnecting)).captureToImage().toPixelMap()
        var lightest = Color.Transparent
        var lightestLuminance = -1.0
        var darkest = Color.Transparent
        var darkestLuminance = 2.0
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) {
                val pixel = pixels[x, y]
                val luminance = relativeLuminance(pixel)
                if (luminance > lightestLuminance) {
                    lightest = pixel
                    lightestLuminance = luminance
                }
                if (luminance < darkestLuminance) {
                    darkest = pixel
                    darkestLuminance = luminance
                }
            }
        }

        val colors = chatColors(dark = mode == ThemeMode.DARK)
        assertWithMessage("$mode: the banner text is onWarning, not a shared on-color")
            .that(distance(darkest, colors.onWarning)).isWithin(0.01).of(0.0)
        assertWithMessage("$mode: the bar behind the text is warning, not another surface")
            .that(distance(lightest, colors.warning)).isWithin(0.01).of(0.0)

        val captured = contrastRatio(darkest, lightest)
        val expected = contrastRatio(colors.onWarning, colors.warning)
        assertWithMessage("$mode: the captured banner pair reaches 4_5 to 1")
            .that(captured).isAtLeast(MIN_NORMAL_TEXT)
        assertWithMessage("$mode: the captured pair is the expected onWarning-on-warning pair")
            .that(captured).isWithin(0.01).of(expected)
    }

    /** Euclidean distance between two sRGB colours, each channel in 0..1. */
    private fun distance(first: Color, second: Color): Double {
        val red = (first.red - second.red).toDouble()
        val green = (first.green - second.green).toDouble()
        val blue = (first.blue - second.blue).toDouble()
        return sqrt(red * red + green * green + blue * blue)
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        return (maxOf(firstLuminance, secondLuminance) + 0.05) / (minOf(firstLuminance, secondLuminance) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private companion object {
        const val MIN_NORMAL_TEXT = 4.5
    }
}
