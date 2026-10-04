package space.megaworld.claudeusage.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.flow.first
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.worker.UsageRefreshWorker

/** Тап по виджету — немедленное обновление через WorkManager. */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        val provider = AppGraph.get(context).settingsStore.widgetProvider(appWidgetId).first()
        UsageRefreshWorker.refreshNow(context, provider)
    }
}
