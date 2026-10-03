package space.megaworld.claudeusage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import space.megaworld.claudeusage.data.UsageProvider

@Composable
fun ProviderPicker(selected: UsageProvider, enabled: Boolean, onSelect: (UsageProvider) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        UsageProvider.entries.forEach { provider ->
            val chosen = provider == selected
            val accent = ProviderColors.accent(provider)
            val shape = RoundedCornerShape(16.dp)
            Row(
                modifier = Modifier.weight(1f).clip(shape)
                    .background(if (chosen) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface)
                    .border(1.dp, accent.copy(alpha = if (chosen) 0.8f else 0.25f), shape)
                    .selectable(selected = chosen, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(provider) })
                    .padding(horizontal = 10.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen, onClick = null, enabled = enabled,
                    colors = RadioButtonDefaults.colors(selectedColor = accent, unselectedColor = accent.copy(alpha = 0.5f)))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(if (provider == UsageProvider.CLAUDE) "Claude" else "GPT", color = accent,
                        style = MaterialTheme.typography.titleMedium)
                    if (provider == UsageProvider.CODEX) Text("Лимиты Codex", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
