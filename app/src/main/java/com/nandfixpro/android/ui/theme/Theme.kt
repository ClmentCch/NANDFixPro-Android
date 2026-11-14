package com.nandfixpro.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NandDarkScheme = darkColorScheme(
    primary = NandBlue,
    onPrimary = Color(0xFF06111F),
    primaryContainer = NandBlueContainer,
    onPrimaryContainer = NandOnBlueContainer,
    secondary = NandTeal,
    onSecondary = Color(0xFF00211A),
    secondaryContainer = NandTealContainer,
    onSecondaryContainer = NandOnTealContainer,
    tertiary = NandAmber,
    onTertiary = Color(0xFF291800),
    tertiaryContainer = NandAmberContainer,
    onTertiaryContainer = NandOnAmberContainer,
    error = NandRed,
    onError = Color(0xFF2A0503),
    errorContainer = NandRedContainer,
    onErrorContainer = NandOnRedContainer,
    background = NandBackground,
    onBackground = NandText,
    surface = NandSurface,
    onSurface = NandText,
    surfaceVariant = NandSurfaceHigh,
    onSurfaceVariant = NandTextMuted,
    surfaceContainerLowest = NandBackground,
    surfaceContainerLow = NandSurface,
    surfaceContainer = NandSurfaceHigh,
    surfaceContainerHigh = NandSurfaceHighest,
    surfaceContainerHighest = NandSurfaceHighest,
    outline = NandOutline,
    outlineVariant = NandOutlineSoft,
    inverseSurface = NandText,
    inverseOnSurface = NandBackground,
    scrim = Color.Black,
)

/**
 * The app is intentionally dark-only: it mirrors the original NANDFixPro tool and
 * keeps the screen readable next to a console connected over USB.
 */
@Composable
fun NandFixProTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NandDarkScheme,
        typography = NandTypography,
        content = content,
    )
}
