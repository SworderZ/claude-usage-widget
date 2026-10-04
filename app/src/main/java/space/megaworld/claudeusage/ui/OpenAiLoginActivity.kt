package space.megaworld.claudeusage.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import space.megaworld.claudeusage.data.DeviceAuthorization
import space.megaworld.claudeusage.data.OPENAI_DEVICE_URL
import space.megaworld.claudeusage.data.UsageProvider

class OpenAiLoginActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[OpenAiLoginViewModel::class.java] }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            ClaudeUsageTheme(UsageProvider.CODEX) {
                LaunchedEffect(model.connected) { if (model.connected) finish() }
                BackHandler { model.cancelLogin(); finish() }
                OpenAiLoginContent(
                    busy = model.busy, error = model.error, networkStatus = model.networkStatus,
                    onPickFile = { pickFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    onCheckNetwork = model::checkNetwork,
                    onBack = { model.cancelLogin(); finish() },
                    authorization = model.authorization, onStartLogin = model::startLogin,
                    onCancelLogin = model::cancelLogin,
                    onOpenBrowser = {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OPENAI_DEVICE_URL))) }
                        catch (e: ActivityNotFoundException) { model.browserUnavailable() }
                    },
                    onCopyCode = { code ->
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Код входа ChatGPT", code))
                    },
                )
            }
        }
    }
}

@Composable
internal fun OpenAiLoginContent(
    busy: Boolean, error: String?, networkStatus: String?,
    onPickFile: () -> Unit, onCheckNetwork: () -> Unit, onBack: () -> Unit,
    authorization: DeviceAuthorization? = null, onStartLogin: () -> Unit = {},
    onCancelLogin: () -> Unit = {}, onOpenBrowser: () -> Unit = {}, onCopyCode: (String) -> Unit = {},
) {
    Scaffold(topBar = { AppTopBar("Подключить GPT", "Вход с подпиской ChatGPT", onBack) }) { padding ->
        ScreenColumn(padding) {
            BusyLine(busy)
            if (authorization == null) {
                SectionCard {
                    Text("Войти через ChatGPT", style = MaterialTheme.typography.titleMedium)
                    SupportingText("Получите одноразовый код и подтвердите вход в браузере. Компьютер и перенос файлов не нужны.")
                    Button(onClick = onStartLogin, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Войти через ChatGPT") }
                    SupportingText("Перед первым входом включите авторизацию по коду устройства в ChatGPT → Настройки → Безопасность.")
                }
            } else {
                DeviceLoginCard(authorization, onOpenBrowser, onCopyCode, onCancelLogin)
            }
            error?.let { MessageCard(it, error = true) }
            if (authorization == null) {
                ExpandableSection("Другой способ входа") {
                    SupportingText("На компьютере войдите в Codex, перенесите .codex/auth.json на телефон и выберите файл.")
                    OutlinedButton(onClick = onPickFile, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Импортировать auth.json") }
                    SupportingText("Импортированный токен не обновляется автоматически. Удалите перенесённую копию файла после импорта.")
                }
                ExpandableSection("Подключение через VPN") {
                    SupportingText("Если VPN работает по списку приложений, включите tinyGlyph и браузер. Они оба нужны для входа.")
                    OutlinedButton(onClick = onCheckNetwork, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Проверить доступ к OpenAI") }
                    networkStatus?.let { SupportingText(it) }
                }
            }
            SupportingText("Сессия хранится зашифрованной. При входе через браузер tinyGlyph обновляет её автоматически.")
            SupportingText("Здесь доступны лимиты Codex. Остаток сообщений в обычных переписках ChatGPT сервис не предоставляет.")
        }
    }
}

@Composable
private fun DeviceLoginCard(session: DeviceAuthorization, onOpenBrowser: () -> Unit,
    onCopyCode: (String) -> Unit, onCancelLogin: () -> Unit) {
    var remainingSeconds by remember(session) { mutableLongStateOf(0L) }
    var copied by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session) {
        while (true) {
            remainingSeconds = ((session.expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    SectionCard {
        Text("Подтвердите вход", style = MaterialTheme.typography.titleMedium)
        SupportingText("Скопируйте код, откройте ChatGPT и введите его на странице входа. После подтверждения вернитесь в tinyGlyph.")
        SelectionContainer {
            Text(session.userCode, style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        OutlinedButton(onClick = { onCopyCode(session.userCode); copied = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (copied) "Код скопирован" else "Скопировать код")
        }
        Button(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Открыть ChatGPT") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            SupportingText("Ждём подтверждения · ${remainingSeconds / 60}:${(remainingSeconds % 60).toString().padStart(2, '0')}", Modifier.weight(1f))
        }
        TextButton(onClick = onCancelLogin, modifier = Modifier.fillMaxWidth()) { Text("Отменить вход") }
    }
}
