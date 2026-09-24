package com.josebaperu.aautoradio.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF8B3DFF),
    secondary = Color(0xFF00897B),
    tertiary = Color(0xFFFF6D3F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCDB0FF),
    secondary = Color(0xFF6ED8C8),
    tertiary = Color(0xFFFFB59B),
)

/** Material 3 with Material You dynamic color on Android 12+, a violet brand palette otherwise. */
@Composable
fun AAutoRadioTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
