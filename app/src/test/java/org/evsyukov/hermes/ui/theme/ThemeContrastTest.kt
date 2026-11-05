package org.evsyukov.hermes.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Token-level contrast checks over the foreground/background pairs the UI actually draws text with.
 *
 * Every pair comes from the palette in Theme.kt through [chatColors], with the background named by
 * the call site that paints it; no hex value is copied into this test. The target is the WCAG 2.2
 * SC 1.4.3 ratio of 4.5:1 for normal informational text, computed from the unrounded sRGB channels.
 *
 * Legitimate exclusions:
 * - [ChatColors.divider] and [ChatColors.composerBorder] draw lines, not text. Non-text contrast
 *   (SC 1.4.11) is a separate target and is not asserted here.
 * - Faint icon tints (chevrons, the search clear control) are non-text graphics, so they fall under
 *   SC 1.4.11 rather than the text ratio checked here. The clear control is still reachable through
 *   its content description; it is interactive, not decorative.
 * - Material's disabled control content is inactive UI and is exempt under WCAG.
 *
 * Status colours used as text are informational, not decorative. [ChatColors.warningText],
 * [ChatColors.successText] and [ChatColors.dangerText] are checked on every surface where the call
 * sites draw them. The base [ChatColors.warning], [ChatColors.success] and [ChatColors.danger]
 * remain the tokens for fills, status dots and icons.
 */
class ThemeContrastTest {
    private val themes = linkedMapOf(
        "light" to chatColors(dark = false),
        "dark" to chatColors(dark = true),
    )

    private data class TextPair(val where: String, val foreground: Color, val background: Color)

    /** Every background [ChatColors.faintText] is drawn on as informational text. */
    private fun faintTextPairs(colors: ChatColors): List<TextPair> = listOf(
        TextPair("search placeholder on the search field (SearchScreen.kt:67)", colors.faintText, colors.userBubble),
        TextPair("composer hint on the composer (Composer.kt:153)", colors.faintText, colors.composer),
        TextPair("search help on the screen (SearchScreen.kt:89)", colors.faintText, colors.background),
        TextPair("empty search result on the screen (SearchScreen.kt:84)", colors.faintText, colors.background),
        TextPair("search hit date on the screen (SearchScreen.kt:109)", colors.faintText, colors.background),
        TextPair("sidebar source label on a chat row (Sidebar.kt:276)", colors.faintText, colors.sidebar),
        TextPair("sidebar source label on the selected row (Sidebar.kt:249,276)", colors.faintText, colors.selected),
        TextPair("sidebar group title (Sidebar.kt:169)", colors.faintText, colors.sidebar),
        TextPair("sidebar empty state (Sidebar.kt:161)", colors.faintText, colors.sidebar),
        TextPair("server host in the sidebar footer (Sidebar.kt:354)", colors.faintText, colors.sidebar),
        TextPair("chat subtitle (ChatSubtitle.kt:52)", colors.faintText, colors.background),
        TextPair("question number in an agent card (QuestionCard.kt:79)", colors.faintText, colors.background),
        TextPair("unanswered question line (QuestionCard.kt:102)", colors.faintText, colors.userBubble),
        TextPair("message delivery note (MessageViews.kt:153)", colors.faintText, colors.background),
        TextPair("settings hint (SettingsScreen.kt:105)", colors.faintText, colors.background),
    )

    /** [ChatColors.warningText] as ordinary informational text. */
    private fun warningTextPairs(colors: ChatColors): List<TextPair> = listOf(
        TextPair("offline notice in a question card (QuestionCard.kt:112)", colors.warningText, colors.background),
        TextPair("connection note in the chat (ConnectionCard.kt:353)", colors.warningText, colors.background),
        TextPair("scan warning in the connection dialog (ConnectionCard.kt:427)", colors.warningText, colors.userBubble),
        TextPair("provider warning in the model list (ConfigScreens.kt:105)", colors.warningText, colors.background),
        TextPair("raw-config warning in the dialog (ConfigScreens.kt:267)", colors.warningText, colors.userBubble),
        TextPair("warning line in the log view (OpsScreens.kt:345)", colors.warningText, colors.codeBackground),
    )

    /** [ChatColors.successText] as ordinary informational text. */
    private fun successTextPairs(colors: ChatColors): List<TextPair> = listOf(
        TextPair("installed details in a connection card (ConnectionCard.kt:309)", colors.successText, colors.background),
        TextPair("passed scan in the connection dialog (ConnectionCard.kt:427)", colors.successText, colors.userBubble),
        TextPair("gateway state in the admin card (AdminScreen.kt:142)", colors.successText, colors.sidebar),
    )

    /** [ChatColors.dangerText] as ordinary informational text and destructive actions. */
    private fun dangerTextPairs(colors: ChatColors): List<TextPair> = listOf(
        TextPair("error line on a screen (AdminCommon.kt:143)", colors.dangerText, colors.background),
        TextPair("error line in a list row (OpsScreens.kt:282)", colors.dangerText, colors.background),
        TextPair("error line in a dialog (OpsScreens.kt:127)", colors.dangerText, colors.userBubble),
        TextPair("gateway state in the admin card (AdminScreen.kt:142)", colors.dangerText, colors.sidebar),
        TextPair("error line in a card (AdminScreen.kt:161)", colors.dangerText, colors.sidebar),
        TextPair("destructive action in a dialog (AdminCommon.kt:184)", colors.dangerText, colors.userBubble),
        TextPair("error line in a config field dialog (ConfigScreens.kt:236)", colors.dangerText, colors.userBubble),
        TextPair("raw-config error in the dialog (ConfigScreens.kt:274)", colors.dangerText, colors.userBubble),
        TextPair("sidebar load error (Sidebar.kt:148)", colors.dangerText, colors.sidebar),
        TextPair("delete item in the chat menu (Sidebar.kt:308)", colors.dangerText, colors.sidebar),
        TextPair("delete action in a dialog (Sidebar.kt:320)", colors.dangerText, colors.userBubble),
        TextPair("connection failure in a card (ConnectionCard.kt:317)", colors.dangerText, colors.background),
        TextPair("delivery failure under a message (MessageViews.kt:153)", colors.dangerText, colors.background),
        TextPair("failed model list in a sheet (Sheets.kt:200)", colors.dangerText, colors.background),
        TextPair("error line in the log view (OpsScreens.kt:344)", colors.dangerText, colors.codeBackground),
    )

    @Test
    fun `faint informational text reaches 4_5 to 1 in both themes`() {
        for ((theme, colors) in themes) {
            for (pair in faintTextPairs(colors)) assertReadable(theme, pair)
        }
    }

    @Test
    fun `warning status text reaches 4_5 to 1 in both themes`() {
        for ((theme, colors) in themes) {
            for (pair in warningTextPairs(colors)) assertReadable(theme, pair)
        }
    }

    @Test
    fun `success status text reaches 4_5 to 1 in both themes`() {
        for ((theme, colors) in themes) {
            for (pair in successTextPairs(colors)) assertReadable(theme, pair)
        }
    }

    @Test
    fun `danger status text reaches 4_5 to 1 in both themes`() {
        for ((theme, colors) in themes) {
            for (pair in dangerTextPairs(colors)) assertReadable(theme, pair)
        }
    }

    /**
     * Both error blocks paint [ChatColors.text] rather than a status colour onto a 12% danger tint
     * (ChatScreen.kt:415, MessageViews.kt:277). The tint is composited the way the screen does it.
     */
    @Test
    fun `error text on the danger tint reaches 4_5 to 1 in both themes`() {
        for ((theme, colors) in themes) {
            assertWithMessage("$theme: the danger tint colour is opaque")
                .that(colors.danger.alpha).isEqualTo(1f)
            val tint = composite(colors.danger, DANGER_TINT_ALPHA, colors.background)
            assertReadable(
                theme,
                TextPair("error block text on the 12% danger tint (ChatScreen.kt:415, MessageViews.kt:277)", colors.text, tint),
            )
        }
    }

    @Test
    fun `the reconnect banner text reaches 4_5 to 1 in both themes`() {
        for ((theme, colors) in themes) {
            assertReadable(theme, TextPair("reconnect banner (ChatScreen.kt:322)", colors.onWarning, colors.warning))
        }
    }

    @Test
    fun `primary stays stronger than secondary, and secondary stronger than faint`() {
        for ((theme, colors) in themes) {
            for ((surface, background) in listOf(
                "background" to colors.background,
                "user bubble" to colors.userBubble,
                "sidebar" to colors.sidebar,
            )) {
                val primary = contrastRatio(colors.text, background)
                val secondary = contrastRatio(colors.secondaryText, background)
                val faint = contrastRatio(colors.faintText, background)
                assertWithMessage("$theme: secondary is quieter than primary on $surface")
                    .that(secondary).isLessThan(primary)
                assertWithMessage("$theme: faint is quieter than secondary on $surface")
                    .that(faint).isLessThan(secondary)
            }
        }
    }

    /** Every text pair is opaque, or its background is an explicitly composited opaque tint. */
    private fun assertReadable(theme: String, pair: TextPair) {
        assertWithMessage("$theme: ${pair.where} draws opaque text")
            .that(pair.foreground.alpha).isEqualTo(1f)
        assertWithMessage("$theme: ${pair.where} has an opaque background")
            .that(pair.background.alpha).isEqualTo(1f)
        assertWithMessage("$theme: ${pair.where}")
            .that(contrastRatio(pair.foreground, pair.background))
            .isAtLeast(MIN_NORMAL_TEXT)
    }

    /** WCAG relative luminance of an opaque sRGB colour, as SC 1.4.3 defines the ratio. */
    private fun relativeLuminance(color: Color): Double =
        0.2126 * linearChannel(color.red) + 0.7152 * linearChannel(color.green) + 0.0722 * linearChannel(color.blue)

    private fun linearChannel(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) / (min(firstLuminance, secondLuminance) + 0.05)
    }

    /** What [Color.copy] followed by drawing on [background] produces: an opaque result. */
    private fun composite(foreground: Color, alpha: Float, background: Color): Color = Color(
        red = foreground.red * alpha + background.red * (1f - alpha),
        green = foreground.green * alpha + background.green * (1f - alpha),
        blue = foreground.blue * alpha + background.blue * (1f - alpha),
        alpha = 1f,
    )

    private companion object {
        const val MIN_NORMAL_TEXT = 4.5
        const val DANGER_TINT_ALPHA = 0.12f
    }
}
