package space.megaworld.claudeusage.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import space.megaworld.claudeusage.data.SettingsStore
import space.megaworld.claudeusage.data.UsageProvider
import space.megaworld.claudeusage.data.UsageState
import space.megaworld.claudeusage.glyph.UsageForegroundService
import space.megaworld.claudeusage.R

@Composable
fun SettingsScreen(state: UsageState, busy: Boolean,
    onSelectWidgetProvider: (UsageProvider) -> Unit, onSelectOrganization: (String) -> Unit,
    onSelectInterval: (Int) -> Unit, onReloadOrganizations: () -> Unit,
) {
    Scaffold(topBar = { AppTopBar("Настройки", "Виджет и обновление данных") }) { padding ->
        ScreenColumn(padding) {
            BusyLine(busy)
            SectionCard {
                Text("ИИ для новых виджетов", style = MaterialTheme.typography.titleMedium)
                SupportingText("Выбор по умолчанию. У каждого виджета свои лимиты: источник можно изменить через его шестерёнку или удержанием на рабочем столе.")
                ProviderPicker(state.widgetProvider, !busy, onSelectWidgetProvider)
            }
            SectionCard {
                Text("Обновление лимитов", style = MaterialTheme.typography.titleMedium)
                ChoiceChips(SettingsStore.ALLOWED_INTERVALS.toList(), state.refreshIntervalMinutes, !busy,
                    label = { "$it мин" }, onSelect = onSelectInterval)
                SupportingText(if (state.refreshIntervalMinutes < 15)
                    "Частое обновление использует постоянное уведомление и расходует больше батареи."
                    else "Фоновое обновление без постоянного уведомления. Android может сдвинуть время запуска.")
                SupportingText("Обновляются лимиты в приложении и во всех ваших виджетах.")
                ExactAlarmNotice(state.refreshIntervalMinutes)
            }
            if (state.provider == UsageProvider.CLAUDE) {
                SectionCard {
                    Text("Организация Claude", style = MaterialTheme.typography.titleMedium)
                    if (state.organizations.isEmpty()) SupportingText("Войдите в Claude или обновите список организаций.")
                    state.organizations.forEach { org ->
                        Row(Modifier.fillMaxWidth().selectable(selected = org.uuid == state.organizationUuid,
                            enabled = !busy, role = Role.RadioButton, onClick = { onSelectOrganization(org.uuid) })
                            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = org.uuid == state.organizationUuid, onClick = null, enabled = !busy)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(org.name ?: "Организация", style = MaterialTheme.typography.bodyMedium)
                                SupportingText(org.uuid)
                            }
                        }
                    }
                    OutlinedButton(onClick = onReloadOrganizations, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Обновить список") }
                }
            }
            AboutSettingsCard()
        }
    }
}

@Composable
private fun AboutSettingsCard() {
    val context = LocalContext.current
    val version = remember(context) { installedVersionName(context) }
    Card(onClick = { context.startActivity(Intent(context, AboutActivity::class.java)) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("О приложении", style = MaterialTheme.typography.titleSmall)
                SupportingText("tinyGlyph · $version")
            }
            Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null,
                modifier = Modifier.rotate(-90f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExactAlarmNotice(intervalMinutes: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(UsageForegroundService.canScheduleExact(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = UsageForegroundService.canScheduleExact(context) }
    if (intervalMinutes >= UsageForegroundService.WORKMANAGER_FLOOR_MINUTES || allowed) return
    HorizontalDivider()
    SupportingText("При выключенном экране Android может растянуть интервал до 9–15 минут. Разрешите точные будильники для выбранной частоты.")
    OutlinedButton(onClick = {
        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:" + context.packageName)))
    }, modifier = Modifier.fillMaxWidth()) { Text("Разрешить будильники") }
}
