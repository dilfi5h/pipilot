package dev.pipilot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PiOrange = Color(0xFFFF7043)
private val PiOrangeDim = Color(0xFFBF360C)

private val LightColors = lightColorScheme(
    primary = PiOrangeDim,
    secondary = Color(0xFF546E7A),
    surface = Color(0xFFFAFAFA),
)
private val DarkColors = darkColorScheme(
    primary = PiOrange,
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
