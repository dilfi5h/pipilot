package dev.pipilot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.pipilot.app.chat.ToolFamily

// Brand cyan, matching the launcher icon
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

/** Chrome for thinking / tool / bash cards. Matches tools/chat-preview/index.html. */
data class ToolChrome(val background: Color, val accent: Color)

object ChatPalette {
    val bashBg = Color(0xFF1E1E1E)
    val bashCmd = Color(0xFF9CCC65)
    val bashFg = Color(0xFFD4D4D4)
    val bashMuted = Color(0xFFBDBDBD)
    val ok = Color(0xFF43A047)

    fun tool(family: ToolFamily, dark: Boolean): ToolChrome = when (family) {
        ToolFamily.Bash -> ToolChrome(bashBg, bashCmd)
        ToolFamily.Read -> if (dark) ToolChrome(Color(0xFF1A2A30), Color(0xFF22D3EE))
        else ToolChrome(Color(0xFFE8F1F4), Color(0xFF0E7490))
        ToolFamily.Write -> if (dark) ToolChrome(Color(0xFF2A2318), Color(0xFFFBBF24))
        else ToolChrome(Color(0xFFF3EDE4), Color(0xFFB45309))
        ToolFamily.Edit -> if (dark) ToolChrome(Color(0xFF241C30), Color(0xFFC4B5FD))
        else ToolChrome(Color(0xFFEEE8F4), Color(0xFF6D28D9))
        ToolFamily.Generic -> if (dark) ToolChrome(Color(0xFF2A3134), Color(0xFF90A4AE))
        else ToolChrome(Color(0xFFECEFF1), Color(0xFF546E7A))
    }
}

@Composable
fun PiPilotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
