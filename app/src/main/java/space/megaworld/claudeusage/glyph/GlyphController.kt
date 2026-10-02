package space.megaworld.claudeusage.glyph

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphManager
import space.megaworld.claudeusage.data.GlyphLight
import space.megaworld.claudeusage.data.GlyphRenderMode
import kotlin.math.roundToInt

/**
 * Полоса C на Nothing Phone (2a) как индикатор 5-часового окна лимита, плюс
 * короткие каналы A и B под амбиентные состояния (простой телефона, дождь).
 *
 * Обёртка над Glyph Developer Kit (`com.nothing.ketchum`, AAR лежит в `app/libs`).
 * Держит весь SDK в одном месте: на любом не-Nothing устройстве [isSupportedDevice]
 * вернёт false и наружу ничего не утечёт.
 *
 * Ограничения самого SDK, а не этого кода:
 * - работает только на устройствах Nothing с Android 14+;
 * - документация обещает «только приложение на переднем плане», но проверку делает
 *   системный сервис, а не AAR, и foreground service её проходит (проверено на 2a);
 * - с ключом `NothingKey=test` требуется однократно включить отладку на телефоне:
 *   `adb shell settings put global nt_glyph_interface_debug_enable 1` (сбрасывается
 *   через 48 часов).
 */
class GlyphController(context: Context) {

    private val appContext = context.applicationContext
    private var manager: GlyphManager? = null
    private var sessionOpen = false

    /** Кадр, запрошенный до готовности сессии: покажем, как только она откроется. */
    private var pending: Request? = null
    private var lastMode: GlyphRenderMode? = null

    /** Последняя ошибка — чтобы UI мог объяснить, почему полоса не горит. */
    @Volatile var lastError: String? = null
        private set

    val isSupportedDevice: Boolean get() = GlyphSupport.isAvailable

    /**
     * Что показать за один раз. Все каналы едут вместе, потому что SDK принимает
     * кадр целиком: отдельно «подсветить A», не затронув C, нельзя.
     */
    data class Request(
        /** Заполнение полосы C, 0..100; null — полосу не трогаем. */
        val cPercent: Int?,
        val mode: GlyphRenderMode = GlyphRenderMode.PROGRESS,
        val aLight: Int = GlyphLight.OFF,
        val bLight: Int = GlyphLight.OFF,
    ) {
        val hasAmbient: Boolean get() = aLight > GlyphLight.OFF || bLight > GlyphLight.OFF
        val isBlank: Boolean get() = cPercent == null && !hasAmbient
    }

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
                pending?.let { show(it) }
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

    /** Гасим Glyph и отпускаем сессию. */
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
            pending = null
        }
    }

    /**
     * Показать кадр.
     *
     * Пока A и B погашены, полоса C рисуется родным `displayProgress` — именно он
     * проверен на устройстве, и ломать его незачем. Как только появляется
     * амбиентный канал, переходим на кадр, собранный вручную: подмешать A и B в
     * `displayProgress` нельзя, он занимает кадр целиком.
     */
    fun show(request: Request) {
        val gm = manager
        if (gm == null || !sessionOpen) {
            pending = request
            return
        }
        pending = null
        if (request.isBlank) {
            runCatching { gm.turnOff() }
            lastMode = null
            return
        }
        // Режимы используют разные механизмы SDK, поэтому перед сменой гасим прошлый кадр.
        val effectiveMode = if (request.hasAmbient) GlyphRenderMode.SEGMENTS else request.mode
        if (effectiveMode != lastMode) {
            runCatching { gm.turnOff() }
            lastMode = effectiveMode
        }
        try {
            if (request.hasAmbient) {
                showComposite(gm, request)
            } else {
                showProgressOnly(gm, request.cPercent ?: 0, request.mode)
            }
            lastError = null
        } catch (e: GlyphException) {
            lastError = "Не удалось показать кадр: ${e.message}"
            Log.w(TAG, "render failed", e)
        }
    }

    private fun showProgressOnly(gm: GlyphManager, percent: Int, mode: GlyphRenderMode) {
        val clamped = percent.coerceIn(0, 100)
        when (mode) {
            GlyphRenderMode.PROGRESS ->
                gm.displayProgress(gm.glyphFrameBuilder.buildChannelC().build(), clamped)
            GlyphRenderMode.PROGRESS_REVERSED ->
                gm.displayProgress(gm.glyphFrameBuilder.buildChannelC().build(), clamped, true)
            GlyphRenderMode.SEGMENTS -> {
                val builder = gm.glyphFrameBuilder
                litSegments(clamped, mode).forEach { builder.buildChannel(it, GlyphLight.MAX) }
                gm.toggle(builder.build())
            }
        }
    }

    /**
     * Один кадр на все каналы: первые N сегментов полосы C под расход лимита,
     * A и B — своей яркостью.
     *
     * Анимации тут нет намеренно. `animate()` задаёт период на весь кадр, а не на
     * канал, поэтому «C горит ровно, а B пульсирует» одним кадром недостижимо —
     * пульсировало бы всё сразу.
     */
    private fun showComposite(gm: GlyphManager, request: Request) {
        val builder = gm.glyphFrameBuilder
        request.cPercent?.let { percent ->
            litSegments(percent.coerceIn(0, 100), request.mode)
                .forEach { builder.buildChannel(it, GlyphLight.MAX) }
        }
        val codes = ambientCodes() ?: return
        if (request.aLight > GlyphLight.OFF) builder.buildChannel(codes.first, request.aLight)
        if (request.bLight > GlyphLight.OFF) builder.buildChannel(codes.second, request.bLight)
        gm.toggle(builder.build())
    }

    /** Коды сегментов, которые нужно зажечь под [percent]; порядок зависит от режима. */
    private fun litSegments(percent: Int, mode: GlyphRenderMode): List<Int> {
        val lit = (percent * SEGMENT_COUNT / 100.0).roundToInt().coerceIn(0, SEGMENT_COUNT)
        if (lit == 0) return emptyList()
        val codes = segmentCodes()
        val ordered = if (mode == GlyphRenderMode.PROGRESS_REVERSED) codes.reversed() else codes
        return ordered.take(lit)
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

    /** Коды A и B. Оба канала есть и у (2a), и у (2a) Plus — таблица кодов общая. */
    private fun ambientCodes(): Pair<Int, Int>? = runCatching {
        Glyph.Code_23111.A to Glyph.Code_23111.B
    }.getOrNull()

    fun turnOff() {
        pending = null
        val gm = manager ?: return
        if (!sessionOpen) return
        runCatching { gm.turnOff() }
        lastMode = null
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
