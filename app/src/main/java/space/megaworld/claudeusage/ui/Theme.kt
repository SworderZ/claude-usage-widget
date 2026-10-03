package space.megaworld.claudeusage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    ), shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp),
    ), typography = Typography(
        displaySmall = TextStyle(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    ), content = content)
}

object UsageColors {
    val Warning = Color(0xFFE0A400)
    val Critical = Color(0xFFFF807A)
    fun forLevel(level: UsageLevel, provider: UsageProvider = UsageProvider.CLAUDE): Color = when (level) {
        UsageLevel.NORMAL -> ProviderColors.accent(provider)
        UsageLevel.WARNING -> Warning
        UsageLevel.CRITICAL -> Critical
    }
}
