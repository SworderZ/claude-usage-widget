package space.megaworld.claudeusage.ui

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.data.UsageProvider
import space.megaworld.claudeusage.widget.UsageWidget
import space.megaworld.claudeusage.widget.UsageWidgetReceiver

/** A real AppWidgetHost for emulator checks. No accounts or usage data are injected. */
class WidgetHostPreviewActivity : ComponentActivity() {
    private lateinit var host: AppWidgetHost
    private var ids by mutableStateOf(emptyList<Int>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        host = AppWidgetHost(this, 1500)
        val manager = AppWidgetManager.getInstance(this)
        val component = ComponentName(this, UsageWidgetReceiver::class.java)
        val settings = AppGraph.get(this).settingsStore
        lifecycleScope.launch {
            val preferences = getSharedPreferences("widget_host_preview", MODE_PRIVATE)
            val reset = intent.getBooleanExtra("reset", false)
            val next = (0..1).map { index ->
                val old = preferences.getInt("widget_$index", 0)
                if (!reset && manager.getAppWidgetInfo(old)?.provider == component) old
                else {
                    if (old > 0) host.deleteAppWidgetId(old)
                    val id = host.allocateAppWidgetId()
                    check(manager.bindAppWidgetIdIfAllowed(id, component)) { "Grant widget binding to the debug app first" }
                    settings.setWidgetProvider(id, if (index == 0) UsageProvider.CLAUDE else UsageProvider.CODEX)
                    preferences.edit().putInt("widget_$index", id).apply()
                    id
                }
            }
            if (reset) {
                settings.setProvider(UsageProvider.CLAUDE)
                settings.setWidgetProvider(UsageProvider.CLAUDE)
            }
            ids = next
            UsageWidget().updateAll(this@WidgetHostPreviewActivity)
        }
        setContent {
            ClaudeUsageTheme(UsageProvider.CODEX) {
                Scaffold(topBar = { AppTopBar("Виджеты", "Два независимых экземпляра") }) { padding ->
                    ScreenColumn(padding) {
                        if (ids.size != 2) CircularProgressIndicator()
                        else {
                            val sources by remember(ids) {
                                combine(settings.widgetProvider(ids[0]), settings.widgetProvider(ids[1]), settings.provider) {
                                    first, second, app -> listOf(first, second, app)
                                }
                            }.collectAsState(initial = emptyList())
                            if (sources.size == 3) Text("${sources[0].displayLabel()} · ${sources[1].displayLabel()}",
                                modifier = Modifier.semantics {
                                    contentDescription = "widget-providers:${sources[0].name},${sources[1].name};app:${sources[2].name}"
                                })
                            ids.forEach { id ->
                                AndroidView(modifier = Modifier.width(280.dp).height(170.dp), factory = { context ->
                                    host.createView(context, id, manager.getAppWidgetInfo(id)).apply {
                                        updateAppWidgetSize(null, 280, 170, 280, 170)
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        host.startListening()
    }

    override fun onStop() {
        host.stopListening()
        super.onStop()
    }
}
