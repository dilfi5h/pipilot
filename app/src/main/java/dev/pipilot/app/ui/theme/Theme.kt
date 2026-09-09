package dev.pipilot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 品牌青,与 launcher 图标一致
val PiCyan = Color(0xFF22D3EE)
private val PiCyanDim = Color(0xFF0E7490)

private val LightColors = lightColorScheme(
    primary = PiCyanDim,
    secondary = Color(0xFF546E7A),
    surface = Color(0xFFFAFAFA),
)
private val DarkColors = darkColorScheme(
    primary = PiCyan,
    secondary = Color(0xFF90A4AE),
)

@Composable
fun PiPilotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
