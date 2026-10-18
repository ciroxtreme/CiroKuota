package com.ciro.kuota.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val MonochromeDarkColors = darkColorScheme(
    primary = PureWhite,
    onPrimary = PureBlack,
    primaryContainer = DarkCard,
    onPrimaryContainer = DarkTextPrimary,
    background = DarkBg,
    surface = DarkCard,
    surfaceVariant = DarkSubtaskBg,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    outline = DarkCardBorder
)

private val MonochromeLightColors = lightColorScheme(
    primary = PureBlack,
    onPrimary = PureWhite,
    primaryContainer = LightCard,
    onPrimaryContainer = LightTextPrimary,
    background = LightBg,
    surface = LightCard,
    surfaceVariant = LightSubtaskBg,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary,
    outline = LightCardBorder
)

@Composable
fun CiroKuotaTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) MonochromeDarkColors else MonochromeLightColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
