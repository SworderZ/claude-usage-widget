package space.megaworld.claudeusage.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.megaworld.claudeusage.R
import space.megaworld.claudeusage.data.*

@Composable
fun MainScreen(
    state: UsageState,
    busy: Boolean,
    message: String?,
    onRefresh: () -> Unit,
    onLogin: () -> Unit,
    onManualLogin: () -> Unit,
    onLogout: () -> Unit,
    onDismissMessage: () -> Unit,
    onSelectProvider: (UsageProvider) -> Unit,
    onOpenAiLogin: () -> Unit,
) {
    Scaffold(topBar = { AppTopBar(stringResource(R.string.app_name), "Лимиты всегда под рукой") }) { padding ->
        ScreenColumn(padding) {
            ProviderPicker(state.provider, !busy, onSelectProvider)
            BusyLine(busy)
            val needsLogin = state.status == UsageStatus.NOT_AUTHORIZED || state.status == UsageStatus.SESSION_EXPIRED
            if (needsLogin) {
                SectionCard {
                    Text(if (state.status == UsageStatus.SESSION_EXPIRED) "Подключите аккаунт заново" else "Подключите ${state.provider.label}",
                        style = MaterialTheme.typography.titleMedium)
                    SupportingText(if (state.status == UsageStatus.SESSION_EXPIRED)
                        "Сессия истекла или сервер запросил проверку. Войдите снова, чтобы обновлять лимиты."
                        else "Следите за расходом лимитов в приложении, виджете и на подсветке Glyph.")
                    Button(onClick = if (state.provider == UsageProvider.CODEX) onOpenAiLogin else onLogin,
                        enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Подключить аккаунт") }
                    if (state.provider == UsageProvider.CLAUDE) {
                        OutlinedButton(onClick = onManualLogin, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Войти по ключу сессии") }
                    }
                }
            }
            if (state.status == UsageStatus.NETWORK_ERROR) {
                MessageCard(if (state.hasData) "Не удалось обновить лимиты. Показаны последние сохранённые данные."
                    else "Не удалось загрузить лимиты. Проверьте подключение и попробуйте ещё раз.", error = true)
            }
            if (!message.isNullOrBlank()) MessageCard(message, onDismiss = onDismissMessage)
            if (state.hasData) {
                state.snapshot?.windows?.forEach { window ->
                    UsageCard(window, state.isStale, state.provider, window.key == UsageSnapshot.KEY_FIVE_HOUR)
                }
                SupportingText("Обновлено: " + UsageFormat.updatedAt(state.snapshot?.fetchedAtMillis ?: 0L))
            } else if (!needsLogin && state.status != UsageStatus.NETWORK_ERROR) {
                SectionCard {
                    Text("Лимиты ещё не загружены", style = MaterialTheme.typography.titleMedium)
                    SupportingText("Обновите данные, чтобы увидеть расход текущего окна и недели.")
                }
            }
            if (!needsLogin) {
                Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Обновить лимиты") }
            }
            if (state.provider == UsageProvider.CODEX) ChatGptAvailability()
            if (state.status != UsageStatus.NOT_AUTHORIZED) {
                TextButton(onClick = onLogout, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Выйти из ${state.provider.label}") }
            }
        }
    }
}

@Composable
private fun UsageCard(window: UsageWindow, stale: Boolean, provider: UsageProvider, primary: Boolean) {
    val color = UsageColors.forLevel(UsageFormat.level(window.utilization), provider).let { if (stale) it.copy(alpha = 0.5f) else it }
    SectionCard {
        Text(UsageFormat.windowLabel(window.key), style = MaterialTheme.typography.titleSmall)
        Text(UsageFormat.percent(window.utilization),
            style = if (primary) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium, color = color)
        LinearProgressIndicator(progress = { UsageFormat.fraction(window.utilization) },
            modifier = Modifier.fillMaxWidth().height(if (primary) 10.dp else 8.dp), color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        UsageFormat.resetHint(window.resetsAtMillis)?.let { SupportingText(it) }
        if (stale) SupportingText("Сохранённые данные")
    }
}
