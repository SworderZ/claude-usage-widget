package space.megaworld.claudeusage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import space.megaworld.claudeusage.R
import space.megaworld.claudeusage.data.UsageProvider

@Composable
fun ProviderPicker(selected: UsageProvider, enabled: Boolean, onSelect: (UsageProvider) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        UsageProvider.entries.forEach { provider ->
            val chosen = provider == selected
            val accent = ProviderColors.accent(provider)
            val shape = RoundedCornerShape(18.dp)
            Column(
                modifier = Modifier.weight(1f).clip(shape)
                    .background(if (chosen) accent.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surface)
                    .border(1.dp, if (chosen) accent.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outlineVariant, shape)
                    .selectable(selected = chosen, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(provider) })
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(if (provider == UsageProvider.CLAUDE) R.drawable.ic_claude_mark else R.drawable.ic_gpt_mark),
                        contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(7.dp).background(if (chosen) accent else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                }
                Column {
                    Text(if (provider == UsageProvider.CLAUDE) "Claude" else "GPT", color = accent,
                        style = MaterialTheme.typography.titleMedium)
                    SupportingText(if (provider == UsageProvider.CLAUDE) "Claude.ai" else "Codex")
                }
            }
        }
    }
}
