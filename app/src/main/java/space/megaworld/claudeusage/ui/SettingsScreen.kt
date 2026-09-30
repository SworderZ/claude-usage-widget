package space.megaworld.claudeusage.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import space.megaworld.claudeusage.R
import space.megaworld.claudeusage.data.SettingsStore
import space.megaworld.claudeusage.data.UsageState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UsageState,
    busy: Boolean,
    onBack: () -> Unit,
    onSelectOrganization: (String) -> Unit,
    onSelectInterval: (Int) -> Unit,
    onReloadOrganizations: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = "Назад",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(text = "Организация", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (state.organizations.isEmpty()) {
                Text(
                    text = "Список пуст — войдите в claude.ai или обновите список.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                state.organizations.forEach { organization ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = organization.uuid == state.organizationUuid,
                                enabled = !busy,
                                onClick = { onSelectOrganization(organization.uuid) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = organization.uuid == state.organizationUuid,
                            onClick = { onSelectOrganization(organization.uuid) },
                            enabled = !busy,
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = organization.name ?: organization.uuid,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = organization.uuid,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onReloadOrganizations, enabled = !busy) {
                Text(text = "Обновить список")
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Интервал обновления", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Меньше 15 минут WorkManager не позволяет.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsStore.ALLOWED_INTERVALS.forEach { minutes ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { onSelectInterval(minutes) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = minutes == state.refreshIntervalMinutes,
                        onClick = { onSelectInterval(minutes) },
                        enabled = !busy,
                    )
                    Text(
                        text = "$minutes мин",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
