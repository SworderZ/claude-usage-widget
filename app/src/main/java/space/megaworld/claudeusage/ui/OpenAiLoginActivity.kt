package space.megaworld.claudeusage.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
        setContent {
            ClaudeUsageTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Подключить Codex", style = MaterialTheme.typography.headlineSmall)
                        Text("На компьютере с выполненным входом Codex найдите файл .codex/auth.json в папке пользователя. Перенесите его на телефон и выберите ниже.")
                        Text("Файл содержит секреты аккаунта. Не отправляйте его в чаты. После импорта удалите перенесённую копию. Приложение хранит access token зашифрованным и не использует refresh token компьютера.", style = MaterialTheme.typography.bodySmall)
                        Text("Если VPN работает по списку приложений, включите в маршрут AI Usage (space.megaworld.claudeusage). Открывающийся браузер не подтверждает, что это приложение идёт тем же маршрутом.", style = MaterialTheme.typography.bodySmall)
                        Text("Когда access token истечёт, потребуется импорт свежего файла. Это подключение показывает лимиты Codex, а не количество оставшихся сообщений ChatGPT.")
                        Button(onClick = { pickFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) { Text("Выбрать файл входа") }
                        OutlinedButton(onClick = {
                            busy = true
                            lifecycleScope.launch {
                                try { networkStatus = OpenAiUsageClient().checkConnectivity() }
                                finally { busy = false }
                            }
                        }, enabled = !busy) { Text("Проверить доступ к OpenAI") }
                        networkStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (busy) CircularProgressIndicator()
                        OutlinedButton(onClick = { finish() }, enabled = !busy) { Text("Назад") }
                    }
                }
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
