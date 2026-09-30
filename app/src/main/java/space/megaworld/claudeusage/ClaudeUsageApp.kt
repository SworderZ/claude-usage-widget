package space.megaworld.claudeusage

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import space.megaworld.claudeusage.worker.UsageRefreshWorker

class ClaudeUsageApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val graph = AppGraph.get(this)
        // Периодическая работа переживает перезагрузку сама (WorkManager восстанавливает
        // её из своей БД), здесь только страхуемся на случай первого запуска и
        // переустановки: KEEP не тронет уже стоящую задачу.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            if (graph.credentialStore.hasCredentials.first()) {
                UsageRefreshWorker.ensureScheduled(
                    context = this@ClaudeUsageApp,
                    intervalMinutes = graph.settingsStore.currentIntervalMinutes(),
                )
            }
        }
    }
}
