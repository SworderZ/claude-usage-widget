package space.megaworld.claudeusage.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
fun ChatGptAvailability() {
    val context = LocalContext.current
    SectionCard {
        Text("ChatGPT · переписки", style = MaterialTheme.typography.titleSmall)
        SupportingText("Этот источник показывает лимиты Codex. Остаток сообщений в обычных переписках ChatGPT пока недоступен.")
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/#settings/Usage"))) },
            modifier = Modifier.fillMaxWidth()) { Text("Открыть ChatGPT") }
    }
}
