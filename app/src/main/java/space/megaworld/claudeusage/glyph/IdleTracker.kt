package space.megaworld.claudeusage.glyph

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager

/**
 * Сколько телефон лежит нетронутым — источник для канала A.
 *
 * Простой считается от момента, когда экран **погас**: пока он горит, телефон у
 * тебя в руках, и никакого простоя нет. Заодно это снимает вопрос видимости —
 * Glyph на задней крышке, при включённом экране его всё равно не видно.
 *
 * Разрешений не нужно: `ACTION_SCREEN_ON/OFF` рассылаются всем, а подписаться на
 * них можно только из кода — в манифесте эти два действия не работают.
 */
class IdleTracker(
    context: Context,
    private val onChanged: () -> Unit,
) {

    private val appContext = context.applicationContext
    private var registered = false

    /**
     * Момент, с которого идёт отсчёт. При старте — «только что потрогали», даже
     * если экран уже погас: служба могла подняться заново (START_STICKY), и
     * честное значение взять негде. Канал просто загорится на порог позже.
     */
    private var lastTouchMillis = System.currentTimeMillis()

    private var screenOn = powerManager()?.isInteractive ?: true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    lastTouchMillis = System.currentTimeMillis()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    lastTouchMillis = System.currentTimeMillis()
                }
                else -> return
            }
            onChanged()
        }
    }

    /** Простой в миллисекундах; при включённом экране — ноль. */
    val idleMillis: Long
        get() = if (screenOn) 0L else
            (System.currentTimeMillis() - lastTouchMillis).coerceAtLeast(0L)

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        appContext.registerReceiver(receiver, filter)
        registered = true
        // Состояние экрана могло измениться до подписки.
        screenOn = powerManager()?.isInteractive ?: screenOn
    }

    fun stop() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }

    private fun powerManager(): PowerManager? =
        appContext.getSystemService(PowerManager::class.java)
}
