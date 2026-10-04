package space.megaworld.claudeusage.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.data.UsageProvider
import space.megaworld.claudeusage.widget.UsageWidget
import space.megaworld.claudeusage.widget.UsageWidgetReceiver
import space.megaworld.claudeusage.worker.UsageRefreshWorker

class WidgetConfigurationActivity : ComponentActivity() {
    private var selected by mutableStateOf<UsageProvider?>(null)
    private var saving by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(this)
        if (appWidgetId <= 0 || manager.getAppWidgetInfo(appWidgetId)?.provider !=
            ComponentName(this, UsageWidgetReceiver::class.java)) {
            finish()
            return
        }
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        lifecycleScope.launch {
            selected = savedInstanceState?.getString("selected_provider")?.let {
                runCatching { UsageProvider.valueOf(it) }.getOrNull()
            } ?: AppGraph.get(this@WidgetConfigurationActivity).settingsStore.widgetProvider(appWidgetId).first()
        }
        setContent {
            ClaudeUsageTheme(selected ?: UsageProvider.CLAUDE) {
                BackHandler(enabled = saving) { }
                WidgetConfigurationContent(selected, saving, error,
                    onSelect = { selected = it; error = null },
                    onSave = { save() }, onBack = { if (!saving) finish() })
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selected?.let { outState.putString("selected_provider", it.name) }
        super.onSaveInstanceState(outState)
    }

    private fun save() {
        val provider = selected ?: return
        if (saving) return
        saving = true
        error = null
        lifecycleScope.launch {
            try {
                val graph = AppGraph.get(this@WidgetConfigurationActivity)
                val manager = AppWidgetManager.getInstance(this@WidgetConfigurationActivity)
                graph.settingsStore.initializeWidgetProviders(manager.getAppWidgetIds(
                    ComponentName(this@WidgetConfigurationActivity, UsageWidgetReceiver::class.java)))
                graph.settingsStore.setWidgetProvider(appWidgetId, provider)
                UsageWidget().update(this@WidgetConfigurationActivity,
                    GlanceAppWidgetManager(this@WidgetConfigurationActivity).getGlanceIdBy(appWidgetId))
                UsageRefreshWorker.ensureScheduled(this@WidgetConfigurationActivity, graph.settingsStore.currentIntervalMinutes())
                UsageRefreshWorker.refreshNow(this@WidgetConfigurationActivity, provider)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Не удалось сохранить настройку. Попробуйте ещё раз."
            } finally {
                saving = false
            }
        }
    }
}

@Composable
internal fun WidgetConfigurationContent(selected: UsageProvider?, saving: Boolean, error: String?,
    onSelect: (UsageProvider) -> Unit, onSave: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { AppTopBar("Настройка виджета", "Лимиты на рабочем столе", onBack) }) { padding ->
        ScreenColumn(padding) {
            BusyLine(selected == null || saving)
            SectionCard {
                Text("Какие лимиты показывать?", style = MaterialTheme.typography.titleMedium)
                SupportingText("Выбор действует только на этот виджет. На другом можно оставить другой ИИ.")
                ProviderPicker(selected ?: UsageProvider.CLAUDE, selected != null && !saving, onSelect)
                SupportingText("GPT показывает лимиты Codex. Подключить аккаунты можно на вкладке «Лимиты».")
            }
            error?.let { MessageCard(it, error = true) }
            Button(onClick = onSave, enabled = selected != null && !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Сохранить") }
        }
    }
}
