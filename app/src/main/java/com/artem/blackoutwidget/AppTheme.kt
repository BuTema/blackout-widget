package com.artem.blackoutwidget

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app looks like the widget: neutral grays, with orange as the only colour. Orange means "power on"
 * wherever a schedule is drawn, and it is also what draws attention to a problem. No red, no green.
 */
object AppColors {
    val orangeDark = Color(0xFFFF9F0A)
    val orangeLight = Color(0xFFE08600)
    val grayDark = Color(0xFFB0B0B5)
    val grayLight = Color(0xFF6B6B70)

    // The one exception to the orange-and-gray look: an emergency outage is red, so it is not missed.
    val emergencyDark = Color(0xFFFF5A4F)
    val emergencyLight = Color(0xFFD32F2F)

    fun emergency(dark: Boolean) = if (dark) emergencyDark else emergencyLight
    fun on(dark: Boolean) = if (dark) orangeDark else orangeLight
    fun off(dark: Boolean) = if (dark) grayDark else grayLight
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE5E5EA),
    onPrimary = Color(0xFF1C1C1E),
    primaryContainer = Color(0xFF3A3A3C),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFAEAEB2),
    onSecondary = Color(0xFF1C1C1E),
    secondaryContainer = Color(0xFF3A3A3C),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFFF9F0A),
    onTertiary = Color(0xFF1C1C1E),
    tertiaryContainer = Color(0xFF3A3A3C),
    onTertiaryContainer = Color(0xFFFF9F0A),
    background = Color(0xFF0F0F10),
    onBackground = Color.White,
    surface = Color(0xFF0F0F10),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF9A9AA0),
    surfaceTint = Color(0xFFE5E5EA),
    outline = Color(0xFF6C6C70),
    outlineVariant = Color(0xFF5C5C61),
    error = Color(0xFFFF9F0A),
    onError = Color(0xFF1C1C1E),
    errorContainer = Color(0xFF3A3A3C),
    onErrorContainer = Color(0xFFFF9F0A),
    surfaceContainerLowest = Color(0xFF0F0F10),
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF1C1C1E),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1C1C1E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E5EA),
    onPrimaryContainer = Color(0xFF1C1C1E),
    secondary = Color(0xFF6B6B70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E5EA),
    onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = Color(0xFFE08600),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE5E5EA),
    onTertiaryContainer = Color(0xFFB36A00),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFF2F2F7),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF6B6B70),
    surfaceTint = Color(0xFF1C1C1E),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFC4C4C9),
    error = Color(0xFFB36A00),
    onError = Color.White,
    errorContainer = Color(0xFFE5E5EA),
    onErrorContainer = Color(0xFFB36A00),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFE5E5EA),
    surfaceContainerHighest = Color.White,
)

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
