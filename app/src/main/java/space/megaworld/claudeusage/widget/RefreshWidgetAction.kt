package space.megaworld.claudeusage.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import space.megaworld.claudeusage.worker.UsageRefreshWorker

/** Тап по виджету — немедленное обновление через WorkManager. */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        UsageRefreshWorker.refreshNow(context)
    }
}
