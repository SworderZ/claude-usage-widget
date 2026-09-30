package space.megaworld.claudeusage.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
        setContent {
            ClaudeUsageTheme {
                var input by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        Text(
                            text = "Вход по ключу сессии",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Instructions()

                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { openInBrowser() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = "Открыть claude.ai в браузере")
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = input,
                            onValueChange = {
                                input = it
                                error = null
                            },
                            label = { Text(text = "sessionKey") },
                            placeholder = { Text(text = "sk-ant-sid01-…") },
                            supportingText = {
                                Text(text = "Можно вставить и всю строку cookie целиком")
                            },
                            isError = error != null,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        error?.let { text ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Row {
                            Button(
                                onClick = {
                                    busy = true
                                    error = null
                                    save(input) { failure ->
                                        busy = false
                                        error = failure
                                    }
                                },
                                enabled = !busy && input.isNotBlank(),
                            ) {
                                Text(text = "Сохранить и проверить")
                            }
                            OutlinedButton(
                                onClick = { finish() },
                                enabled = !busy,
                                modifier = Modifier.padding(start = 12.dp),
                            ) {
                                Text(text = "Отмена")
                            }
                        }

                        if (busy) {
                            Spacer(modifier = Modifier.height(16.dp))
                            CircularProgressIndicator()
                        }
                    }
                }
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
private fun Instructions() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Откуда взять ключ",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "На компьютере: войти на claude.ai, F12 → Storage / Application → " +
                    "Cookies → https://claude.ai → скопировать значение sessionKey.\n\n" +
                    "В Firefox на телефоне: поставить расширение Cookie-Editor, открыть " +
                    "claude.ai, найти в нём sessionKey и скопировать.\n\n" +
                    "Через консоль не получится: cookie помечен HttpOnly и в " +
                    "document.cookie не виден.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
