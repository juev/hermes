package org.evsyukov.hermes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Neutral greys without an accent, the way the ChatGPT app looks: the contrast colour (black on light,
 * white on dark) marks the one action that matters, such as the send button.
 */
@Immutable
data class ChatColors(
    val background: Color,
    val sidebar: Color,
    val userBubble: Color,
    val composer: Color,
    val composerBorder: Color,
    val text: Color,
    val secondaryText: Color,
    val faintText: Color,
    val divider: Color,
    val selected: Color,
    val contrast: Color,
    val onContrast: Color,
    val codeBackground: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    /** Text drawn on [warning], as the reconnect banner is: role-specific, not a shared on-color. */
    val onWarning: Color,
    /** Status text on informational surfaces; the base status colours stay for fills, dots and icons. */
    val warningText: Color,
    val successText: Color,
    val dangerText: Color,
)

private val Dark = ChatColors(
    background = Color(0xFF212121),
    sidebar = Color(0xFF171717),
    userBubble = Color(0xFF303030),
    composer = Color(0xFF303030),
    composerBorder = Color(0xFF424242),
    text = Color(0xFFECECEC),
    secondaryText = Color(0xFFB4B4B4),
    faintText = Color(0xFF9E9E9E),
    divider = Color(0xFF2F2F2F),
    selected = Color(0xFF2F2F2F),
    contrast = Color(0xFFFFFFFF),
    onContrast = Color(0xFF0D0D0D),
    codeBackground = Color(0xFF0D0D0D),
    danger = Color(0xFFF87171),
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFBBF24),
    onWarning = Color(0xFF0D0D0D),
    warningText = Color(0xFFFBBF24),
    successText = Color(0xFF4ADE80),
    dangerText = Color(0xFFF87171),
)

private val Light = ChatColors(
    background = Color(0xFFFFFFFF),
    sidebar = Color(0xFFF9F9F9),
    userBubble = Color(0xFFF4F4F4),
    composer = Color(0xFFFFFFFF),
    composerBorder = Color(0xFFE3E3E3),
    text = Color(0xFF0D0D0D),
    secondaryText = Color(0xFF5D5D5D),
    faintText = Color(0xFF696969),
    divider = Color(0xFFECECEC),
    selected = Color(0xFFECECEC),
    contrast = Color(0xFF0D0D0D),
    onContrast = Color(0xFFFFFFFF),
    codeBackground = Color(0xFFF4F4F4),
    danger = Color(0xFFDC2626),
    success = Color(0xFF16A34A),
    warning = Color(0xFFD97706),
    onWarning = Color(0xFF0D0D0D),
    warningText = Color(0xFF9A3412),
    successText = Color(0xFF166534),
    dangerText = Color(0xFFB91C1C),
)

val LocalChatColors = staticCompositionLocalOf { Dark }

private fun ChatColors.material(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = contrast,
        onPrimary = onContrast,
        secondary = secondaryText,
        primaryContainer = selected,
        onPrimaryContainer = text,
        secondaryContainer = selected,
        onSecondaryContainer = text,
        tertiary = secondaryText,
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = userBubble,
        onSurfaceVariant = secondaryText,
        surfaceContainer = sidebar,
        surfaceContainerLow = sidebar,
        surfaceContainerHigh = userBubble,
        surfaceContainerHighest = userBubble,
        outline = composerBorder,
        outlineVariant = divider,
        error = danger,
    )
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The palette [HermesTheme] provides; exposed to tests that check the colors text is actually drawn with. */
internal fun chatColors(dark: Boolean): ChatColors = if (dark) Dark else Light

@Composable
fun HermesTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = chatColors(dark)
    CompositionLocalProvider(LocalChatColors provides colors) {
        MaterialTheme(colorScheme = colors.material(dark), content = content)
    }
}

object ChatTheme {
    val colors: ChatColors
        @Composable get() = LocalChatColors.current
}
