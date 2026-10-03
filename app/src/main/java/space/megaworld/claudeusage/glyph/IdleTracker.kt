package space.megaworld.claudeusage.glyph

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** События экрана для A; elapsedRealtime учитывает глубокий сон и не зависит от даты. */
class IdleTracker(context: Context, private val onChanged: () -> Unit) {
    private val appContext = context.applicationContext
    private var registered = false
    private val timer = IdleTimer(SystemClock::elapsedRealtime, powerManager()?.isInteractive ?: true)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> timer.setInteractive(false)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> timer.setInteractive(true)
                else -> return
            }
            onChanged()
        }
    }

    val idleMillis: Long get() = timer.idleMillis
    val screenOn: Boolean get() = timer.screenOn
    fun nextUpdateAt(thresholdMinutes: Int): Long? = timer.nextUpdateAt(thresholdMinutes)

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        timer.setInteractive(powerManager()?.isInteractive ?: timer.screenOn)
    }

    fun stop() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }

    private fun powerManager(): PowerManager? = appContext.getSystemService(PowerManager::class.java)
}
