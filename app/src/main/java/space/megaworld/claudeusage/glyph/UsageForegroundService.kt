package space.megaworld.claudeusage.glyph

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.R
import space.megaworld.claudeusage.data.SettingsStore
import space.megaworld.claudeusage.data.UsageState
import space.megaworld.claudeusage.widget.UsageWidget
import kotlin.math.roundToInt

/**
 * Фоновая служба: держит полосу Glyph зажжённой и, если выбран интервал короче
 * пятнадцати минут, сама тикает обновлением.
 *
 * Зачем она для обновления: минимум `PeriodicWorkRequest` — пятнадцать минут, короче
 * WorkManager не принимает. Это ограничение WorkManager, а не системы, и foreground
 * service под него не попадает: пока он жив, можно опрашивать сервер хоть каждую минуту,
 * и сеть ему доступна даже в Doze. Платой идёт постоянное уведомление в шторке.
 *
 * Про Glyph: SDK Nothing разрешает работу «только приложению на переднем плане», но
 * проверку делает системный сервис, а не AAR — в его байткоде её нет. На Phone (2a)
 * foreground service эту проверку проходит, проверено на устройстве.
 */
class UsageForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val glyph by lazy { GlyphController(this) }
    private var started = false
    private var glyphConnected = false
    private var lastState: UsageState? = null
    private var lastInterval: Int = SettingsStore.DEFAULT_INTERVAL_MINUTES
    private var jobs: MutableList<Job> = mutableListOf()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(buildNotification("Запуск…"))
        if (!started) {
            started = true
            observeState()
            observeTicker()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
        if (glyphConnected) glyph.disconnect()
        super.onDestroy()
    }

    /** Следит за настройками: ведёт полосу и решает, нужна ли служба вообще. */
    private fun observeState() {
        jobs += scope.launch {
            AppGraph.get(applicationContext).usageRepository.state.collect { state ->
                lastState = state
                lastInterval = state.refreshIntervalMinutes

                val wantsGlyph = state.glyphEnabled && glyph.isSupportedDevice
                val wantsFastRefresh = state.refreshIntervalMinutes < WORKMANAGER_FLOOR_MINUTES
                if (!wantsGlyph && !wantsFastRefresh) {
                    // Ни полоса, ни частое обновление больше не нужны — незачем
                    // держать уведомление в шторке.
                    stopSelf()
                    return@collect
                }

                if (wantsGlyph) {
                    if (!glyphConnected) {
                        glyph.connect()
                        glyphConnected = true
                    }
                    val utilization = state.snapshot?.fiveHour?.utilization
                    if (utilization == null) {
                        glyph.turnOff()
                    } else {
                        glyph.showProgress(utilization.roundToInt(), state.glyphRenderMode)
                    }
                } else if (glyphConnected) {
                    glyph.disconnect()
                    glyphConnected = false
                }

                notify(buildNotification(statusText(state)))
            }
        }
    }

    /**
     * Тикает обновлением с выбранным интервалом. collectLatest перезапускает цикл при
     * смене интервала, поэтому отдельного управления джобом не нужно.
     */
    private fun observeTicker() {
        jobs += scope.launch {
            AppGraph.get(applicationContext).settingsStore.refreshIntervalMinutes
                .distinctUntilChanged()
                .collectLatest { minutes ->
                    val repository = AppGraph.get(applicationContext).usageRepository
                    while (isActive) {
                        delay(minutes.toLong() * 60_000L)
                        repository.refresh()
                        UsageWidget().updateAll(applicationContext)
                    }
                }
        }
    }

    private fun statusText(state: UsageState): String {
        glyph.lastError?.let { if (state.glyphEnabled) return it }
        val percent = state.snapshot?.fiveHour?.utilization?.roundToInt()
        val head = if (percent == null) "Нет данных о лимите" else "5-часовое окно: $percent%"
        val tail = when {
            state.refreshIntervalMinutes < WORKMANAGER_FLOOR_MINUTES ->
                "обновление раз в ${state.refreshIntervalMinutes} мин"
            state.glyphEnabled -> "полоса Glyph"
            else -> null
        }
        return if (tail == null) head else "$head · $tail"
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Claude Usage")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_claude)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Фоновое обновление и Glyph",
            // MIN: уведомление техническое, это требование foreground service.
            NotificationManager.IMPORTANCE_MIN,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        /** Короче этого WorkManager не умеет, дальше только своя служба. */
        const val WORKMANAGER_FLOOR_MINUTES = 15

        private const val CHANNEL_ID = "glyph_indicator"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "space.megaworld.claudeusage.STOP_SERVICE"

        /** Нужна ли служба при таких настройках. */
        fun isNeeded(glyphEnabled: Boolean, intervalMinutes: Int): Boolean =
            (glyphEnabled && GlyphSupport.isAvailable) || intervalMinutes < WORKMANAGER_FLOOR_MINUTES

        fun start(context: Context) {
            context.startForegroundService(Intent(context, UsageForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsageForegroundService::class.java))
        }
    }
}
