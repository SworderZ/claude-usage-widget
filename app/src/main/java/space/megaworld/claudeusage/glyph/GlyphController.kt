package space.megaworld.claudeusage.glyph

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphManager

/**
 * Полоса C на Nothing Phone (2a) как индикатор 5-часового окна лимита.
 *
 * Обёртка над Glyph Developer Kit (`com.nothing.ketchum`, AAR лежит в `app/libs`).
 * Держит весь SDK в одном месте: на любом не-Nothing устройстве [isSupportedDevice]
 * вернёт false и наружу ничего не утечёт.
 *
 * Ограничения самого SDK, а не этого кода:
 * - работает только на устройствах Nothing с Android 14+;
 * - **только пока приложение на переднем плане**, поэтому индикацию ведёт Activity,
 *   а не воркер;
 * - с ключом `NothingKey=test` требуется однократно включить отладку на телефоне:
 *   `adb shell settings put global nt_glyph_interface_debug_enable 1` (сбрасывается
 *   через 48 часов).
 */
class GlyphController(context: Context) {

    private val appContext = context.applicationContext
    private var manager: GlyphManager? = null
    private var sessionOpen = false

    /** Прогресс, пришедший до готовности сессии: покажем, как только она откроется. */
    private var pendingProgress: Int? = null

    /** Последняя ошибка — чтобы UI мог объяснить, почему полоса не горит. */
    @Volatile var lastError: String? = null
        private set

    val isSupportedDevice: Boolean get() = GlyphSupport.isAvailable

    private val callback = object : GlyphManager.Callback {
        override fun onServiceConnected(componentName: ComponentName?) {
            val gm = manager ?: return
            val device = deviceCode()
            if (device == null) {
                lastError = "Устройство не поддерживается"
                return
            }
            if (!gm.register(device)) {
                // Чаще всего это отсутствующий ключ или невключённая отладка Glyph.
                lastError = "SDK отклонил регистрацию: проверьте NothingKey и отладку Glyph"
                return
            }
            try {
                gm.openSession()
                sessionOpen = true
                lastError = null
                pendingProgress?.let { showProgress(it) }
            } catch (e: GlyphException) {
                sessionOpen = false
                lastError = "Не удалось открыть сессию: ${e.message}"
                Log.w(TAG, "openSession failed", e)
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            sessionOpen = false
        }
    }

    /** Вызывать из onStart: SDK разрешает работу только приложению на переднем плане. */
    fun connect() {
        if (!isSupportedDevice || manager != null) return
        try {
            manager = GlyphManager.getInstance(appContext).also { it.init(callback) }
        } catch (e: Exception) {
            manager = null
            lastError = "Glyph недоступен: ${e.message}"
            Log.w(TAG, "init failed", e)
        }
    }

    /** Вызывать из onStop: гасим полосу и отпускаем сессию. */
    fun disconnect() {
        val gm = manager ?: return
        try {
            if (sessionOpen) {
                gm.turnOff()
                gm.closeSession()
            }
        } catch (e: GlyphException) {
            Log.w(TAG, "closeSession failed", e)
        } finally {
            sessionOpen = false
            runCatching { gm.unInit() }
            manager = null
            pendingProgress = null
        }
    }

    /** [percent] 0..100 — заполнение полосы C. */
    fun showProgress(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val gm = manager
        if (gm == null || !sessionOpen) {
            pendingProgress = clamped
            return
        }
        pendingProgress = null
        try {
            val frame = gm.glyphFrameBuilder.buildChannelC().build()
            gm.displayProgress(frame, clamped)
        } catch (e: GlyphException) {
            lastError = "Не удалось показать прогресс: ${e.message}"
            Log.w(TAG, "displayProgress failed", e)
        }
    }

    fun turnOff() {
        pendingProgress = null
        val gm = manager ?: return
        if (!sessionOpen) return
        runCatching { gm.turnOff() }
    }

    private fun deviceCode(): String? = GlyphSupport.deviceCode()

    private companion object {
        const val TAG = "GlyphController"
    }
}

/**
 * Дешёвая проверка модели без создания менеджера — нужна UI, чтобы решить,
 * показывать ли настройку вообще.
 */
object GlyphSupport {

    /** Полоса C есть и у Phone (2a), и у (2a) Plus — коды устройств разные. */
    fun deviceCode(): String? = runCatching {
        when {
            Common.is23111() -> Glyph.DEVICE_23111
            Common.is23113() -> Glyph.DEVICE_23113
            else -> null
        }
    }.getOrNull()

    val isAvailable: Boolean get() = deviceCode() != null
}
