package space.megaworld.claudeusage.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.data.RefreshResult
import space.megaworld.claudeusage.widget.UsageWidget
import space.megaworld.claudeusage.worker.UsageRefreshWorker

/**
 * Вход без WebView: пользователь логинится в своём браузере и приносит `sessionKey`
 * руками.
 *
 * Прочитать cookie чужого браузера приложение не может — они в его песочнице, и ни
 * CookieManager, ни Custom Tabs доступа не дают. Поэтому внешняя ссылка здесь только
 * открывает сайт, а сессию переносит копипаст.
 */
class ManualLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            ClaudeUsageTheme {
                var input by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }

                ManualLoginContent(input, busy, error,
                    onInput = { input = it; error = null },
                    onSave = {
                        busy = true
                        error = null
                        save(input) { failure -> busy = false; error = failure }
                    }, onBrowser = { openInBrowser() }, onBack = { finish() })
            }
        }
    }

    /**
     * Сохраняет ключ и сразу дёргает API: при ручном вводе без проверки непонятно,
     * принят ключ или нет.
     */
    private fun save(raw: String, onError: (String) -> Unit) {
        lifecycleScope.launch {
            val graph = AppGraph.get(this@ManualLoginActivity)
            val result = graph.usageRepository.onLoggedIn(
                cookieHeader = normalizeCookie(raw),
                userAgent = fallbackUserAgent(this@ManualLoginActivity),
            )
            when (result) {
                is RefreshResult.Success -> {
                    UsageRefreshWorker.ensureScheduled(
                        context = this@ManualLoginActivity,
                        intervalMinutes = graph.settingsStore.currentIntervalMinutes(),
                    )
                    UsageWidget().updateAll(this@ManualLoginActivity)
                    setResult(RESULT_OK)
                    finish()
                }
                RefreshResult.SessionExpired, RefreshResult.NotAuthorized ->
                    onError(
                        "Ключ не принят: сервер ответил 401/403. Проверьте, что скопирован " +
                            "именно sessionKey и что он ещё живой."
                    )
                is RefreshResult.Failure -> onError("Не удалось проверить ключ: ${result.message}")
            }
        }
    }

    private fun openInBrowser() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(CLAUDE_URL))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        // Через выбор приложения, чтобы можно было указать конкретный браузер,
        // а не только тот, что стоит по умолчанию.
        startActivity(Intent.createChooser(intent, "Открыть claude.ai"))
    }

    private companion object {
        const val CLAUDE_URL = "https://claude.ai"

        /**
         * Ключ пришёл из чужого браузера, его UA нам неизвестен. Берём UA системного
         * WebView без маркеров — для запросов с одним sessionKey этого достаточно.
         */
        fun fallbackUserAgent(context: android.content.Context): String =
            android.webkit.WebSettings.getDefaultUserAgent(context)
                .replace("; wv)", ")")
                .replace(Regex("Version/[0-9.]+ "), "")
    }
}

/**
 * Принимает и голое значение ключа, и целиком строку cookie: у расширений вроде
 * Cookie-Editor удобно копируется и то, и другое.
 */
internal fun normalizeCookie(raw: String): String {
    val trimmed = raw.trim().trim('"', '\'')
    return if (trimmed.contains("sessionKey=")) trimmed else "sessionKey=$trimmed"
}

@Composable
internal fun ManualLoginContent(input: String, busy: Boolean, error: String?, onInput: (String) -> Unit,
    onSave: () -> Unit, onBrowser: () -> Unit, onBack: () -> Unit) {
    androidx.compose.material3.Scaffold(topBar = { AppTopBar("Вход по ключу", "Аккаунт Claude", onBack) }) { padding ->
        ScreenColumn(padding) {
            BusyLine(busy)
            SectionCard {
                Text("Вставьте ключ сессии", style = MaterialTheme.typography.titleMedium)
                SupportingText("Войдите в Claude в браузере и скопируйте sessionKey. Можно вставить всю строку cookie.")
                OutlinedButton(onClick = onBrowser, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Открыть claude.ai")
                }
                OutlinedTextField(value = input, onValueChange = onInput, label = { Text("sessionKey") },
                    placeholder = { Text("sk-ant-sid01-…") }, isError = error != null, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), maxLines = 5,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false))
                Button(onClick = onSave, enabled = !busy && input.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Сохранить и проверить") }
            }
            error?.let { MessageCard(it, error = true) }
            ExpandableSection("Как найти sessionKey", initiallyExpanded = true) {
                Text("На компьютере", style = MaterialTheme.typography.titleSmall)
                SupportingText("Войдите на claude.ai. Нажмите F12 → Storage / Application → Cookies → claude.ai. Скопируйте значение sessionKey.")
                Text("На телефоне", style = MaterialTheme.typography.titleSmall)
                SupportingText("В Firefox установите расширение Cookie-Editor. Откройте claude.ai и скопируйте sessionKey из расширения.")
                SupportingText("Ключ закрыт для JavaScript: через document.cookie он не виден.")
            }
        }
    }
}
