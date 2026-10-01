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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.R
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt

/**
 * Держит полосу C зажжённой, пока приложение свёрнуто.
 *
 * Glyph SDK разрешает работу «только приложению на переднем плане», но проверку делает
 * системный сервис Nothing OS, а не сам AAR — в байткоде её нет. Foreground service
 * поднимает важность процесса, и есть шанс, что этого достаточно. Если системный сервис
 * всё равно откажет, причина будет видна в тексте уведомления.
 */
class GlyphForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val glyph by lazy { GlyphController(this) }
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !glyph.isSupportedDevice) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification("Подключение к Glyph…"))
        glyph.connect()

        if (collectJob == null) {
            collectJob = scope.launch {
                AppGraph.get(applicationContext).usageRepository.state
                    .map {
                        Triple(it.glyphEnabled, it.snapshot?.fiveHour?.utilization, it.glyphRenderMode)
                    }
                    .distinctUntilChanged()
                    .collect { (enabled, utilization, mode) ->
                        if (!enabled) {
                            stopSelf()
                            return@collect
                        }
                        if (utilization == null) {
                            glyph.turnOff()
                            notify(buildNotification("Нет данных о лимите"))
                        } else {
                            val percent = utilization.roundToInt()
                            glyph.showProgress(percent, mode)
                            val error = glyph.lastError
                            notify(
                                buildNotification(
                                    if (error == null) {
                                        "5-часовое окно: $percent% · ${mode.label}"
                                    } else {
                                        error
                                    }
                                )
                            )
                        }
                    }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        collectJob = null
        scope.cancel()
        glyph.disconnect()
        super.onDestroy()
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
            .setContentTitle("Claude Usage · Glyph")
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
            "Индикация Glyph",
            // MIN: уведомление здесь техническое, требование foreground service.
            NotificationManager.IMPORTANCE_MIN,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "glyph_indicator"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "space.megaworld.claudeusage.STOP_GLYPH"

        fun start(context: Context) {
            if (!GlyphSupport.isAvailable) return
            val intent = Intent(context, GlyphForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GlyphForegroundService::class.java)
                .setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
            context.stopService(Intent(context, GlyphForegroundService::class.java))
        }
    }
}
