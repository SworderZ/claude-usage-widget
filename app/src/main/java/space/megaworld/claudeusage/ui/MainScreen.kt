package space.megaworld.claudeusage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import space.megaworld.claudeusage.R
import space.megaworld.claudeusage.data.UsageState
import space.megaworld.claudeusage.data.UsageStatus
import space.megaworld.claudeusage.data.UsageWindow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UsageState,
    busy: Boolean,
    message: String?,
    onRefresh: () -> Unit,
    onLogin: () -> Unit,
    onManualLogin: () -> Unit,
    onLogout: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissMessage: () -> Unit,
    onSelectProvider: (space.megaworld.claudeusage.data.UsageProvider) -> Unit,
    onOpenAiLogin: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "AI Usage") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = "Настройки",
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                space.megaworld.claudeusage.data.UsageProvider.entries.forEach { provider ->
                    OutlinedButton(onClick = { onSelectProvider(provider) }, enabled = !busy && state.provider != provider) {
                        Text(provider.label)
                    }
                }
            }
            Text("Показан расход ${state.provider.label}", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            ChatGptAvailability()
            StatusBanner(state = state, message = message, onDismissMessage = onDismissMessage)

            if (state.hasData) {
                state.snapshot?.windows?.forEach { window ->
                    UsageRow(window = window, stale = state.isStale)
                    Spacer(modifier = Modifier.height(16.dp))
                }
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Обновлено: " + UsageFormat.updatedAt(state.snapshot?.fetchedAtMillis ?: 0L),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state.status != UsageStatus.NOT_AUTHORIZED) {
                Text(
                    text = "Данных пока нет.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (busy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.status == UsageStatus.NOT_AUTHORIZED ||
                    state.status == UsageStatus.SESSION_EXPIRED
                ) {
                    Button(onClick = if (state.provider == space.megaworld.claudeusage.data.UsageProvider.CODEX) onOpenAiLogin else onLogin, enabled = !busy) { Text(text = "Подключить") }
                    if (state.provider == space.megaworld.claudeusage.data.UsageProvider.CLAUDE) OutlinedButton(onClick = onManualLogin, enabled = !busy) {
                        Text(text = "Ключ вручную")
                    }
                }
                Button(
                    onClick = onRefresh,
                    enabled = !busy && state.status != UsageStatus.NOT_AUTHORIZED,
                ) {
                    Text(text = "Обновить")
                }
                if (state.status != UsageStatus.NOT_AUTHORIZED) {
                    OutlinedButton(onClick = onLogout, enabled = !busy) { Text(text = "Выйти") }
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(state: UsageState, message: String?, onDismissMessage: () -> Unit) {
    val banner = when (state.status) {
        UsageStatus.NOT_AUTHORIZED -> "Аккаунт ${state.provider.label} не подключён."
        UsageStatus.SESSION_EXPIRED ->
            "Сессия ${state.provider.label} истекла или сервер требует проверку. Подключите аккаунт заново."
        UsageStatus.NETWORK_ERROR ->
            "Не удалось обновить данные" + (message?.let { ": $it" } ?: "") + ". Показан кеш."
        UsageStatus.NEVER_LOADED -> "Данные ещё не загружались."
        UsageStatus.OK -> message
    }
    if (banner.isNullOrBlank()) return
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = banner, style = MaterialTheme.typography.bodyMedium)
            if (state.status == UsageStatus.OK && message != null) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(onClick = onDismissMessage) { Text(text = "Скрыть") }
            }
        }
    }
}

@Composable
private fun UsageRow(window: UsageWindow, stale: Boolean) {
    val level = UsageFormat.level(window.utilization)
    val color = UsageColors.forLevel(level).let { if (stale) it.copy(alpha = 0.45f) else it }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = UsageFormat.windowLabel(window.key),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = UsageFormat.percent(window.utilization),
                style = MaterialTheme.typography.titleMedium,
                color = color,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { UsageFormat.fraction(window.utilization) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = Color(0x33808080),
        )
        val hint = UsageFormat.resetHint(window.resetsAtMillis)
        if (hint != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
