package space.megaworld.claudeusage.worker

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.data.RefreshResult
import space.megaworld.claudeusage.widget.UsageWidget
import java.util.concurrent.TimeUnit

/** Тянет свежие данные и пинает виджет. Одна и та же работа для периодики и для тапа. */
class UsageRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = AppGraph.get(applicationContext).usageRepository
        val results = repository.refreshDisplayedSources()
        repository.refreshWeatherIfStale()
        // Виджет перерисовываем в любом случае: ошибка тоже меняет его вид.
        UsageWidget().updateAll(applicationContext)

        return if (results.any { it is RefreshResult.Failure } && runAttemptCount < MAX_ATTEMPTS) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "claude_usage_refresh_periodic"
        private const val ONE_TIME_WORK_NAME = "claude_usage_refresh_now"
        private const val MAX_ATTEMPTS = 3

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Ставит периодику, не трогая уже запланированную (KEEP). */
        fun ensureScheduled(context: Context, intervalMinutes: Int) {
            enqueuePeriodic(context, intervalMinutes, ExistingPeriodicWorkPolicy.KEEP)
        }

        /** Пересоздаёт периодику под новый интервал из настроек. */
        fun reschedule(context: Context, intervalMinutes: Int) {
            enqueuePeriodic(context, intervalMinutes, ExistingPeriodicWorkPolicy.UPDATE)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }

        /** Немедленное обновление: кнопка «Обновить» и тап по виджету. */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UsageRefreshWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        private fun enqueuePeriodic(
            context: Context,
            intervalMinutes: Int,
            policy: ExistingPeriodicWorkPolicy,
        ) {
            val safeMinutes = intervalMinutes.coerceAtLeast(15).toLong()
            val request = PeriodicWorkRequestBuilder<UsageRefreshWorker>(
                safeMinutes, TimeUnit.MINUTES,
            ).setConstraints(constraints).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, policy, request)
        }
    }
}
