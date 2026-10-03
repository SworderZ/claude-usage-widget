package space.megaworld.claudeusage.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.*
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.data.RefreshResult
import space.megaworld.claudeusage.data.OpenAiImportException
import space.megaworld.claudeusage.data.OpenAiUsageClient
import space.megaworld.claudeusage.widget.UsageWidget
import space.megaworld.claudeusage.worker.UsageRefreshWorker

class OpenAiLoginActivity : ComponentActivity() {
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var networkStatus by mutableStateOf<String?>(null)
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        busy = true
        error = null
        lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readBytesLimited()
                        bytes.toString(Charsets.UTF_8)
                    } ?: throw IllegalArgumentException("Файл не прочитан")
                }
                val graph = AppGraph.get(this@OpenAiLoginActivity)
                when (val result = graph.usageRepository.importOpenAi(raw)) {
                    is RefreshResult.Success -> {
                        UsageRefreshWorker.ensureScheduled(this@OpenAiLoginActivity, graph.settingsStore.currentIntervalMinutes())
                        UsageWidget().updateAll(this@OpenAiLoginActivity)
                        finish()
                    }
                    RefreshResult.NotAuthorized, RefreshResult.SessionExpired -> error = "OpenAI отклонил токен (401 или явная ошибка токена). Импортируйте свежий auth.json после входа Codex."
                    is RefreshResult.Failure -> error = result.message
                }
            } catch (e: CancellationException) { throw e
            } catch (e: OpenAiImportException) { error = e.message
            } catch (e: Exception) { error = "Не удалось подключить аккаунт. Нужен JSON-файл с access_token из Codex."
            } finally { busy = false }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            ClaudeUsageTheme(provider = space.megaworld.claudeusage.data.UsageProvider.CODEX) {
                OpenAiLoginContent(busy, error, networkStatus,
                    onPickFile = { pickFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    onCheckNetwork = {
                        busy = true
                        lifecycleScope.launch {
                            try { networkStatus = OpenAiUsageClient().checkConnectivity() }
                            finally { busy = false }
                        }
                    }, onBack = { finish() })
            }
        }
    }
}

private fun java.io.InputStream.readBytesLimited(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(out.size() + count <= 128 * 1024) { "Файл слишком большой" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

@Composable
internal fun OpenAiLoginContent(busy: Boolean, error: String?, networkStatus: String?,
    onPickFile: () -> Unit, onCheckNetwork: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { AppTopBar("Подключить Codex", "Лимиты вашей подписки GPT", onBack) }) { padding ->
        ScreenColumn(padding) {
            BusyLine(busy)
            SectionCard {
                Text("Перенесите файл входа", style = MaterialTheme.typography.titleMedium)
                SupportingText("1. На компьютере войдите в Codex.")
                SupportingText("2. Найдите .codex/auth.json в папке пользователя.")
                SupportingText("3. Перенесите файл на телефон и выберите его ниже.")
                Button(onClick = onPickFile, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Выбрать auth.json")
                }
            }
            error?.let { MessageCard(it, error = true) }
            SectionCard {
                Text("Подключение через VPN", style = MaterialTheme.typography.titleSmall)
                SupportingText("Если VPN работает по списку приложений, добавьте tinyGlyph в маршрут. Доступ в браузере не гарантирует доступ в приложении.")
                OutlinedButton(onClick = onCheckNetwork, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Проверить доступ к OpenAI") }
                networkStatus?.let { SupportingText(it) }
            }
            ExpandableSection("О файле входа и лимитах") {
                SupportingText("Файл содержит доступ к аккаунту. После импорта удалите перенесённую копию и не отправляйте её в чаты.")
                SupportingText("Токен хранится зашифрованным. Когда он истечёт, импортируйте свежий файл. Refresh token компьютера не используется.")
                SupportingText("Подключение показывает лимиты Codex. Количество оставшихся сообщений в обычных переписках ChatGPT недоступно.")
            }
        }
    }
}
