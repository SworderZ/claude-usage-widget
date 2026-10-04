package space.megaworld.claudeusage.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import space.megaworld.claudeusage.R
import space.megaworld.claudeusage.data.UsageProvider

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val version = installedVersionName(this)
        setContent {
            ClaudeUsageTheme(UsageProvider.CODEX) {
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                AboutContent(version, onBack = { finish() }, snackbar = snackbar, onOpenLink = { url ->
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    catch (e: ActivityNotFoundException) {
                        scope.launch { snackbar.showSnackbar("Не удалось открыть браузер.") }
                    }
                })
            }
        }
    }
}

internal fun installedVersionName(context: Context): String {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    else context.packageManager.getPackageInfo(context.packageName, 0)
    return info.versionName.orEmpty()
}

@Composable
internal fun AboutContent(version: String, onBack: () -> Unit, onOpenLink: (String) -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() }) {
    Scaffold(topBar = { AppTopBar("О приложении", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        ScreenColumn(padding) {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                    modifier = Modifier.size(112.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp)))
                Text("tinyGlyph", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                SupportingText("Версия $version")
                Text("Лимиты Claude и Codex — в виджетах. Вероятность дождя и время с выключенным экраном — на подсветке Glyph.",
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard {
                Text("Авторы", style = MaterialTheme.typography.titleMedium)
                Text("SworderZ", style = MaterialTheme.typography.bodyLarge)
                SupportingText("Разработано с участием Codex.")
            }
            SectionCard {
                Text("Проект", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { onOpenLink("https://github.com/SworderZ/claude-usage-widget/releases/latest") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Релизы и обновления", textAlign = TextAlign.Center)
                }
                OutlinedButton(onClick = { onOpenLink("https://github.com/SworderZ/claude-usage-widget") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Исходный код на GitHub", textAlign = TextAlign.Center)
                }
            }
        }
    }
}
