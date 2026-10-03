package space.megaworld.claudeusage.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import space.megaworld.claudeusage.data.SettingsStore
import space.megaworld.claudeusage.data.UsageProvider
import space.megaworld.claudeusage.data.UsageState
import space.megaworld.claudeusage.glyph.UsageForegroundService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UsageState,
    busy: Boolean,
    onSelectWidgetProvider: (UsageProvider) -> Unit,
    onSelectOrganization: (String) -> Unit,
    onSelectInterval: (Int) -> Unit,
    onReloadOrganizations: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Настройки") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(text = "ИИ в виджете", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            ProviderPicker(selected = state.widgetProvider, enabled = !busy, onSelect = onSelectWidgetProvider)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Выберите, чьи лимиты показывать на рабочем столе. Переключение ИИ на вкладке «Лимиты» этот выбор не меняет.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            if (state.provider == UsageProvider.CLAUDE) {
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

            }

            Text(text = "Интервал обновления", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = """
                    15 минут и реже — обычное фоновое обновление. Интервалы 5 и 10
                    минут используют службу с постоянным уведомлением и расходуют
                    больше батареи. Обновляются источники приложения и виджета.
                """.trimIndent(),
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

            ExactAlarmNotice(intervalMinutes = state.refreshIntervalMinutes)

        }
    }
}

/**
 * Без разрешения на точные будильники система в Doze прореживает их примерно до
 * одного срабатывания в 9–15 минут, и короткий интервал при спящем экране не
 * соблюдается. Показываем, только когда это действительно мешает.
 */
@Composable
private fun ExactAlarmNotice(intervalMinutes: Int) {
    val context = LocalContext.current
    if (intervalMinutes >= UsageForegroundService.WORKMANAGER_FLOOR_MINUTES) return
    if (UsageForegroundService.canScheduleExact(context)) return

    Spacer(modifier = Modifier.height(16.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Интервал будет соблюдаться не всегда",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "При выключенном экране Android растягивает обновление примерно " +
                    "до 9–15 минут. Чтобы интервал соблюдался, разрешите приложению " +
                    "точные будильники.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:" + context.packageName))
                        )
                    }
                },
            ) {
                Text(text = "Разрешить")
            }
        }
    }
}
