package space.megaworld.claudeusage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import space.megaworld.claudeusage.data.UsageProvider

object ProviderColors {
    val Claude = Color(0xFFFFA36C)
    val Gpt = Color(0xFFF5F5F5)
    fun accent(provider: UsageProvider): Color = if (provider == UsageProvider.CLAUDE) Claude else Gpt
}

@Composable
fun ClaudeUsageTheme(
    provider: UsageProvider = UsageProvider.CLAUDE,
    content: @Composable () -> Unit,
) {
    val accent = ProviderColors.accent(provider)
    MaterialTheme(colorScheme = darkColorScheme(
        primary = accent, onPrimary = Color(0xFF171719),
        primaryContainer = accent.copy(alpha = 0.14f), onPrimaryContainer = accent,
        secondary = accent, onSecondary = Color(0xFF171719),
        secondaryContainer = Color(0xFF303034), onSecondaryContainer = accent,
        tertiary = accent, surfaceTint = accent,
        background = Color(0xFF101012), onBackground = Color(0xFFF5F5F5),
        surface = Color(0xFF171719), onSurface = Color(0xFFF5F5F5),
        surfaceVariant = Color(0xFF242427), onSurfaceVariant = Color(0xFFA1A1AA),
        surfaceDim = Color(0xFF101012), surfaceBright = Color(0xFF303034),
        surfaceContainerLowest = Color(0xFF0C0C0E), surfaceContainerLow = Color(0xFF171719),
        surfaceContainer = Color(0xFF1C1C1F), surfaceContainerHigh = Color(0xFF242427),
        surfaceContainerHighest = Color(0xFF2B2B2F),
        outlineVariant = Color(0xFF3A3A40),
        outline = Color(0xFF65656E), error = Color(0xFFFF807A),
    ), content = content)
}

object UsageColors {
    val Warning = Color(0xFFE0A400)
    val Critical = Color(0xFFD93025)
    fun forLevel(level: UsageLevel, provider: UsageProvider = UsageProvider.CLAUDE): Color = when (level) {
        UsageLevel.NORMAL -> ProviderColors.accent(provider)
        UsageLevel.WARNING -> Warning
        UsageLevel.CRITICAL -> Critical
    }
}
