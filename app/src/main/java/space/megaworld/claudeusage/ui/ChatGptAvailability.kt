package space.megaworld.claudeusage.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*

@Composable
fun ChatGptAvailability() {
    val context = LocalContext.current
    Text("ChatGPT · переписки", style = MaterialTheme.typography.titleMedium)
    Text("Остаток сообщений недоступен: подключённый источник возвращает лимиты Codex. Эти значения не показывают расход обычных переписок.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/#settings/Usage"))) }) {
        Text("Открыть лимиты ChatGPT")
    }
}
