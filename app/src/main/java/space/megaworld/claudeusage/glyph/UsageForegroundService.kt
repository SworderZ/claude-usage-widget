package space.megaworld.claudeusage.glyph

import android.app.AlarmManager
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
import kotlinx.coroutines.launch
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.R
import space.megaworld.claudeusage.data.SettingsStore
import space.megaworld.claudeusage.data.UsageState
import space.megaworld.claudeusage.ui.UsageFormat
import space.megaworld.claudeusage.widget.UsageWidget
import kotlin.math.roundToInt

/**
 * Фоновая служба: держит полосу Glyph зажжённой и, если выбран интервал короче
 * пятнадцати минут, сама тикает обновлением.
 *
 * Зачем она для обновления: минимум `PeriodicWorkRequest` — пятнадцать минут, короче
 * WorkManager не принимает. Это ограничение WorkManager, а не системы, и foreground
 * service под него не попадает. Платой идёт постоянное уведомление в шторке.
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
    private var lastInterval = SettingsStore.DEFAULT_INTERVAL_MINUTES

    /** Последнее известное состояние: из него строится текст уведомления. */
    private var lastState: UsageState? = null
    private val jobs = mutableListOf<Job>()

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
        // Текст берём из последнего известного состояния: «Запуск…» на каждый
        // onStartCommand затирал бы живой статус при каждом тике будильника.
        startForegroundCompat(buildNotification(lastState?.let(::statusText) ?: "Запуск…"))
        if (!started) {
            started = true
            observeState()
        }
        if (intent?.action == ACTION_TICK) runTick() else scheduleNextTick()
        return START_STICKY
    }

    override fun onDestroy() {
        cancelTick()
        jobs.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
        if (glyphConnected) glyph.disconnect()
        super.onDestroy()
    }

    /** Следит за настройками: ведёт полосу и решает, нужна ли служба вообще. */
    private fun observeState() {
        jobs += scope.launch {
            val repository = AppGraph.get(applicationContext).usageRepository
            // Сразу показываем настоящий текст, не дожидаясь первого события потока:
            // иначе при любой заминке в нём уведомление висит с «Запуск…».
            runCatching {
                lastState = repository.currentState()
                notify(buildNotification(statusText(repository.currentState())))
            }
            repository.state.collect { state ->
                lastState = state

                val wantsGlyph = state.glyphEnabled && glyph.isSupportedDevice
                val wantsFastRefresh = state.refreshIntervalMinutes < WORKMANAGER_FLOOR_MINUTES
                if (!wantsGlyph && !wantsFastRefresh) {
                    // Ни полоса, ни частое обновление больше не нужны — незачем
                    // держать уведомление в шторке.
                    stopSelf()
                    return@collect
                }

                // Работа с Glyph отделена от уведомления: если SDK бросит что-то
                // неожиданное, сборщик состояния не должен умереть вместе с ним,
                // иначе уведомление навсегда застынет на последнем тексте.
                val glyphError = runCatching { driveGlyph(state, wantsGlyph) }
                    .exceptionOrNull()
                    ?.let { "Glyph: ${it.message ?: it::class.java.simpleName}" }

                if (state.refreshIntervalMinutes != lastInterval) {
                    lastInterval = state.refreshIntervalMinutes
                    scheduleNextTick()
                }
                notify(buildNotification(glyphError ?: statusText(state)))
            }
        }
    }

    private fun driveGlyph(state: UsageState, wantsGlyph: Boolean) {
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
    }

    /** Один тик по будильнику: обновиться, перерисовать виджет и завести следующий. */
    private fun runTick() {
        jobs += scope.launch {
            val graph = AppGraph.get(applicationContext)
            runCatching { graph.usageRepository.refresh() }
            runCatching { UsageWidget().updateAll(applicationContext) }
            // Состояние могло не измениться (например, сервер вернул те же цифры),
            // тогда сборщик молчит — обновляем текст сами, чтобы по нему было видно,
            // что тик действительно произошёл.
            runCatching { lastState = graph.usageRepository.currentState() }
            notify(buildNotification(lastState?.let(::statusText) ?: "Обновление…"))
            scheduleNextTick()
        }
    }

    /**
     * Будильник вместо delay(): корутинная задержка висит на Handler и спящий телефон
     * не будит — в Doze она откладывается, и при выключенном экране обновление просто
     * не происходит. RTC_WAKEUP будит.
     *
     * Точный будильник требует разрешения «Будильники и напоминания» (Android 12+).
     * Без него остаётся setAndAllowWhileIdle: он работает без разрешения, но в Doze
     * система прореживает его примерно до одного срабатывания в 9–15 минут, то есть
     * пятиминутный интервал при спящем экране соблюдаться не будет.
     */
    private fun scheduleNextTick() {
        jobs += scope.launch {
            val minutes = AppGraph.get(applicationContext).settingsStore.currentIntervalMinutes()
            val manager = getSystemService(AlarmManager::class.java) ?: return@launch
            val at = System.currentTimeMillis() + minutes.toLong() * 60_000L
            val pending = tickIntent(this@UsageForegroundService)
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                manager.canScheduleExactAlarms()
            try {
                if (exact) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                } else {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                }
            } catch (e: SecurityException) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
    }

    private fun cancelTick() {
        getSystemService(AlarmManager::class.java)?.cancel(tickIntent(this))
    }

    private fun statusText(state: UsageState): String {
        glyph.lastError?.let { if (state.glyphEnabled) return it }
        val percent = state.snapshot?.fiveHour?.utilization?.roundToInt()
        val head = if (percent == null) "Нет данных" else "5ч: $percent%"
        // Время последнего обновления здесь не для красоты: по нему видно, тикает
        // ли служба вообще.
        val updated = UsageFormat.updatedAt(state.snapshot?.fetchedAtMillis ?: 0L)
        return head + " · обновлено " + updated +
            " · раз в " + state.refreshIntervalMinutes + " мин"
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
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
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
        private const val ACTION_TICK = "space.megaworld.claudeusage.TICK"
        private const val TICK_REQUEST = 7

        private fun tickIntent(context: Context): PendingIntent {
            val intent = Intent(context, UsageForegroundService::class.java).setAction(ACTION_TICK)
            return PendingIntent.getService(
                context,
                TICK_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Нужна ли служба при таких настройках. */
        fun isNeeded(glyphEnabled: Boolean, intervalMinutes: Int): Boolean =
            (glyphEnabled && GlyphSupport.isAvailable) || intervalMinutes < WORKMANAGER_FLOOR_MINUTES

        fun start(context: Context) {
            context.startForegroundService(Intent(context, UsageForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsageForegroundService::class.java))
        }

        /** Точные будильники на Android 12+ включает пользователь вручную. */
        fun canScheduleExact(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val manager = context.getSystemService(AlarmManager::class.java) ?: return false
            return manager.canScheduleExactAlarms()
        }
    }
}
