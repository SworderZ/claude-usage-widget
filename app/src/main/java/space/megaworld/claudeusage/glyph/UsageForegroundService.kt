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
import android.os.PowerManager
import android.os.SystemClock
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.R
import space.megaworld.claudeusage.data.AmbientChannel
import space.megaworld.claudeusage.data.GlyphChannelMode
import space.megaworld.claudeusage.data.GlyphStripMode
import space.megaworld.claudeusage.data.GlyphLight
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

    /**
     * Функция простоя питается временем, поэтому у неё свой источник
     * событий: гашение экрана начинает отсчёт и сразу просит перерисовать кадр.
     */
    private val idleTracker by lazy {
        IdleTracker(this) {
            renderGlyph()
            scheduleAmbientTick()
            lastState?.let { notify(buildNotification(statusText(it))) }
        }
    }
    private var idleTracking = false
    private var started = false
    private var glyphConnected = false
    private var channelUnderTest: AmbientChannel? = null
    private var channelPreviewUntil = 0L
    private var channelPreviewGeneration = 0L
    private var channelPreviewJob: Job? = null
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
        when (intent?.action) {
            ACTION_TICK -> runTick()
            ACTION_TEST_CHANNEL -> intent.getStringExtra(EXTRA_TEST_CHANNEL)?.let { raw ->
                runCatching { AmbientChannel.valueOf(raw) }.getOrNull()?.let(::previewChannel)
            }
            // Простой вырос — перерисовываем кадр и заводим следующую проверку.
            ACTION_AMBIENT_TICK -> {
                renderGlyph()
                scheduleAmbientTick()
                lastState?.let { notify(buildNotification(statusText(it))) }
            }
            else -> scheduleNextTick()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        channelPreviewGeneration++
        channelPreviewUntil = 0L
        channelUnderTest = null
        channelPreviewJob?.cancel()
        cancelTick()
        cancelAmbientTick()
        if (idleTracking) {
            idleTracker.stop()
            idleTracking = false
        }
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
                scheduleAmbientTick()
                notify(buildNotification(glyphError ?: statusText(state)))
            }
        }
    }

    private fun driveGlyph(state: UsageState, wantsGlyph: Boolean) {
        if (!wantsGlyph) {
            if (glyphConnected) {
                glyph.disconnect()
                glyphConnected = false
            }
            if (idleTracking) {
                idleTracker.stop()
                idleTracking = false
            }
            return
        }
        if (!glyphConnected) {
            glyph.connect()
            glyphConnected = true
        }
        // Подписку держим вместе с сессией: ловить гашение экрана без Glyph незачем.
        if (!idleTracking) {
            idleTracker.start()
            idleTracking = true
        }
        glyph.show(frameFor(state))
    }

    /**
     * Кадр по текущему состоянию: C — выбранный процент, A/B — выбранные функции.
     * Выключенные каналы просто остаются погашенными, и тогда
     * контроллер сам вернётся к родному displayProgress для полосы C.
     */
    private fun frameFor(state: UsageState): GlyphController.Request {
        fun light(channel: AmbientChannel): Int =
            if (channelUnderTest == channel && SystemClock.elapsedRealtime() < channelPreviewUntil) {
                GlyphLight.MAX
            } else {
                state.ambient.lightFor(channel, idleTracker.idleMillis, state.rainForecast?.probabilityPercent)
            }
        return GlyphController.Request(
            cPercent = state.ambient.stripPercent(state.snapshot?.fiveHour?.utilization, state.rainForecast?.probabilityPercent),
            mode = state.glyphRenderMode,
            aLight = light(AmbientChannel.A),
            bLight = light(AmbientChannel.B),
        )
    }

    /**
     * Перерисовка без нового состояния — для событий, которые меняют не данные, а
     * время: гашение экрана и собственный тик простоя.
     */
    private fun renderGlyph() {
        val state = lastState ?: return
        if (!glyphConnected || !state.glyphEnabled) return
        runCatching { glyph.show(frameFor(state)) }
    }

    /** Один тик по будильнику: обновиться, перерисовать виджет и завести следующий. */
    private fun runTick() {
        jobs += scope.launch {
            val graph = AppGraph.get(applicationContext)
            runCatching { graph.usageRepository.refreshDisplayedSources() }
            // Прогноз едет на том же тике, но со своим сроком годности — внутрь
            // сети он сходит далеко не каждый раз.
            runCatching { graph.usageRepository.refreshWeatherIfStale() }
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

    /** Первый будильник — на пороге простоя. WAKEUP доставляет его при спящем экране. */
    private fun scheduleAmbientTick() {
        val state = lastState
        val ambient = state?.ambient
        if (ambient == null || !ambient.idleEnabled || !state.glyphEnabled || !glyph.isSupportedDevice) {
            cancelAmbientTick()
            return
        }
        val at = idleTracker.nextUpdateAt(ambient.idleThresholdMinutes) ?: run {
            cancelAmbientTick()
            return
        }
        val manager = getSystemService(AlarmManager::class.java) ?: return
        val pending = ambientTickIntent(this)
        try {
            if (canScheduleExact(this)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            }
        } catch (_: SecurityException) {
            // Разрешение могли отозвать между проверкой и постановкой будильника.
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
        }
    }

    /** Проверка выбранного физического канала на полной яркости без ожидания и изменения настроек. */
    private fun previewChannel(channel: AmbientChannel) {
        val generation = ++channelPreviewGeneration
        channelUnderTest = channel
        channelPreviewUntil = SystemClock.elapsedRealtime() + CHANNEL_PREVIEW_MILLIS
        channelPreviewJob?.cancel()
        channelPreviewJob = scope.launch {
            val wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "AIUsage:channel-preview",
            )
            try {
                // Даём тесту закончиться через 5 секунд, даже если пользователь заблокирует экран.
                wakeLock?.acquire(CHANNEL_PREVIEW_MILLIS + 5_000L)
                renderGlyph()
                lastState?.let { notify(buildNotification(statusText(it))) }
                delay(CHANNEL_PREVIEW_MILLIS)
            } finally {
                if (generation == channelPreviewGeneration) {
                    channelPreviewUntil = 0L
                    channelUnderTest = null
                    renderGlyph()
                    lastState?.let { notify(buildNotification(statusText(it))) }
                }
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    private fun cancelAmbientTick() {
        getSystemService(AlarmManager::class.java)?.cancel(ambientTickIntent(this))
    }

    private fun statusText(state: UsageState): String {
        glyph.lastError?.let { if (state.glyphEnabled) return it }
        val percent = state.snapshot?.fiveHour?.utilization?.roundToInt()
        val head = when {
            !state.glyphEnabled || state.ambient.stripMode == GlyphStripMode.USAGE ->
                state.provider.displayLabel() + " · " + if (percent == null) "Нет данных" else "5ч: $percent%"
            state.ambient.stripMode == GlyphStripMode.RAIN -> state.rainForecast?.let {
                "C: осадки ${it.probabilityPercent}% · " + (state.ambient.place?.name ?: "город не выбран")
            } ?: "C: осадки · нет прогноза"
            else -> "C: выключена"
        }
        // Время последнего обновления здесь не для красоты: по нему видно, тикает
        // ли служба вообще.
        val updated = UsageFormat.updatedAt(
            if (state.glyphEnabled && state.ambient.stripMode == GlyphStripMode.RAIN) state.rainForecast?.fetchedAtMillis ?: 0L
            else state.snapshot?.fetchedAtMillis ?: 0L,
        )
        val channels = if (!state.glyphEnabled) "" else AmbientChannel.entries.joinToString("") { channel ->
            val detail = if (channelUnderTest == channel && SystemClock.elapsedRealtime() < channelPreviewUntil) {
                "проверка 5 секунд"
            } else when (state.ambient.modeFor(channel)) {
                GlyphChannelMode.OFF -> "выключен"
                GlyphChannelMode.RAIN -> state.rainForecast?.let { "дождь: ${it.probabilityPercent}%" } ?: "нет прогноза"
                GlyphChannelMode.IDLE -> if (idleTracker.screenOn) "экран включён" else {
                    val minutes = idleTracker.idleMillis / 60_000L
                    "$minutes/${state.ambient.idleThresholdMinutes} мин" +
                        if (minutes >= state.ambient.idleThresholdMinutes) " · порог достигнут" else ""
                }
            }
            " · $channel: $detail"
        }
        return head + " · обновлено " + updated +
            " · раз в " + state.refreshIntervalMinutes + " мин" + channels
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
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_notification_glyph)
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
        private const val ACTION_AMBIENT_TICK = "space.megaworld.claudeusage.AMBIENT_TICK"
        private const val ACTION_TEST_CHANNEL = "space.megaworld.claudeusage.TEST_CHANNEL"
        private const val EXTRA_TEST_CHANNEL = "glyph_test_channel"
        private const val CHANNEL_PREVIEW_MILLIS = 5_000L
        private const val TICK_REQUEST = 7
        private const val AMBIENT_REQUEST = 8

        private fun ambientTickIntent(context: Context): PendingIntent {
            val intent = Intent(context, UsageForegroundService::class.java)
                .setAction(ACTION_AMBIENT_TICK)
            return PendingIntent.getService(
                context,
                AMBIENT_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

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

        fun testChannel(context: Context, channel: AmbientChannel) {
            context.startForegroundService(Intent(context, UsageForegroundService::class.java)
                .setAction(ACTION_TEST_CHANNEL).putExtra(EXTRA_TEST_CHANNEL, channel.name))
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
