package space.megaworld.claudeusage.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(primary = Color(0xFF3F7DE0))
private val DarkColors = darkColorScheme(primary = Color(0xFF9CB9F5))

@Composable
fun ClaudeUsageTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Цвета уровней заполнения, общие с виджетом. */
object UsageColors {
    val Normal = Color(0xFF3F7DE0)
    val Warning = Color(0xFFE0A400)
    val Critical = Color(0xFFD93025)

    fun forLevel(level: UsageLevel): Color = when (level) {
        UsageLevel.NORMAL -> Normal
        UsageLevel.WARNING -> Warning
        UsageLevel.CRITICAL -> Critical
    }
}
