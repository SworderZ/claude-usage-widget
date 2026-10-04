package space.megaworld.claudeusage.ui

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.R

/** Keeps the callback listener and authentication alive while the system browser is in front. */
open class OpenAiLoginService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Вход в аккаунт GPT", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 61, Intent(this, OpenAiLoginActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setContentTitle("tinyGlyph · Вход GPT")
            .setContentText("Завершите вход в браузере и вернитесь в tinyGlyph.")
            .setSmallIcon(R.drawable.ic_notification_glyph).setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notification)
        val coordinator = loginCoordinator()
        if (!observing) {
            observing = true
            scope.launch {
                coordinator.state.collect { state ->
                    if (!state.busy) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    internal open fun loginCoordinator() = AppGraph.get(this).openAiLoginCoordinator

    companion object {
        private const val CHANNEL = "openai_login"
        private const val ID = 62
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OpenAiLoginService::class.java))
        }
    }
}

/** Carries no token or authorization code; only brings the private login screen to the front. */
class OpenAiLoginReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.data?.scheme == "tinyglyph" && intent.data?.host == "openai-login") {
            startActivity(Intent(this, OpenAiLoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
