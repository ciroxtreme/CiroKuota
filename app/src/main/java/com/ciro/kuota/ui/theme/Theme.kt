package com.ciro.kuota.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
