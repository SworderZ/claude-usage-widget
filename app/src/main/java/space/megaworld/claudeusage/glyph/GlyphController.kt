package space.megaworld.claudeusage.glyph

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphManager
import space.megaworld.claudeusage.data.GlyphRenderMode
import kotlin.math.roundToInt

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
    private var pendingMode: GlyphRenderMode = GlyphRenderMode.PROGRESS
    private var lastMode: GlyphRenderMode? = null

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
                pendingProgress?.let { showProgress(it, pendingMode) }
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
    fun showProgress(percent: Int, mode: GlyphRenderMode = GlyphRenderMode.PROGRESS) {
        val clamped = percent.coerceIn(0, 100)
        val gm = manager
        if (gm == null || !sessionOpen) {
            pendingProgress = clamped
            pendingMode = mode
            return
        }
        pendingProgress = null
        // Режимы используют разные механизмы SDK, поэтому перед сменой гасим прошлый кадр.
        if (mode != lastMode) {
            runCatching { gm.turnOff() }
            lastMode = mode
        }
        try {
            when (mode) {
                GlyphRenderMode.PROGRESS ->
                    gm.displayProgress(gm.glyphFrameBuilder.buildChannelC().build(), clamped)
                GlyphRenderMode.PROGRESS_REVERSED ->
                    gm.displayProgress(gm.glyphFrameBuilder.buildChannelC().build(), clamped, true)
                GlyphRenderMode.SEGMENTS -> showSegments(gm, clamped)
            }
            lastError = null
        } catch (e: GlyphException) {
            lastError = "Не удалось показать прогресс: ${e.message}"
            Log.w(TAG, "displayProgress failed", e)
        }
    }

    /**
     * Ручная отрисовка: зажигаем первые N из 24 сегментов полосы C.
     * Нужна на случай, если displayProgress на (2a) ложится не так, как обещает документация.
     */
    private fun showSegments(gm: GlyphManager, percent: Int) {
        val lit = (percent * SEGMENT_COUNT / 100.0).roundToInt().coerceIn(0, SEGMENT_COUNT)
        if (lit == 0) {
            gm.turnOff()
            return
        }
        val builder = gm.glyphFrameBuilder
        segmentCodes().take(lit).forEach { builder.buildChannel(it) }
        gm.toggle(builder.build())
    }

    /** C_1 внизу, C_24 наверху — порядок из документации Nothing для Phone (2a). */
    private fun segmentCodes(): List<Int> = listOf(
        Glyph.Code_23111.C_1, Glyph.Code_23111.C_2, Glyph.Code_23111.C_3,
        Glyph.Code_23111.C_4, Glyph.Code_23111.C_5, Glyph.Code_23111.C_6,
        Glyph.Code_23111.C_7, Glyph.Code_23111.C_8, Glyph.Code_23111.C_9,
        Glyph.Code_23111.C_10, Glyph.Code_23111.C_11, Glyph.Code_23111.C_12,
        Glyph.Code_23111.C_13, Glyph.Code_23111.C_14, Glyph.Code_23111.C_15,
        Glyph.Code_23111.C_16, Glyph.Code_23111.C_17, Glyph.Code_23111.C_18,
        Glyph.Code_23111.C_19, Glyph.Code_23111.C_20, Glyph.Code_23111.C_21,
        Glyph.Code_23111.C_22, Glyph.Code_23111.C_23, Glyph.Code_23111.C_24,
    )

    fun turnOff() {
        pendingProgress = null
        val gm = manager ?: return
        if (!sessionOpen) return
        runCatching { gm.turnOff() }
    }

    private fun deviceCode(): String? = GlyphSupport.deviceCode()

    private companion object {
        const val TAG = "GlyphController"
        const val SEGMENT_COUNT = 24
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
