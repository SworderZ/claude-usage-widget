package space.megaworld.claudeusage.data

import kotlinx.serialization.Serializable

/** Физические короткие каналы Glyph; C остаётся полосой лимита. */
enum class AmbientChannel { A, B }

enum class GlyphChannelMode(val label: String) {
    OFF("Выкл."), RAIN("Дождь"), IDLE("Простой"),
}

/** Назначение A и B хранится отдельно; город и порог общие для выбранных функций. */
data class AmbientSettings(
    val channelA: GlyphChannelMode = GlyphChannelMode.OFF,
    val channelB: GlyphChannelMode = GlyphChannelMode.OFF,
    val idleThresholdMinutes: Int = DEFAULT_IDLE_MINUTES,
    val place: WeatherPlace? = null,
) {
    val idleEnabled: Boolean get() = channelA == GlyphChannelMode.IDLE || channelB == GlyphChannelMode.IDLE
    val rainEnabled: Boolean get() = channelA == GlyphChannelMode.RAIN || channelB == GlyphChannelMode.RAIN
    val wantsWeather: Boolean get() = rainEnabled && place != null

    fun modeFor(channel: AmbientChannel): GlyphChannelMode = if (channel == AmbientChannel.A) channelA else channelB

    fun lightFor(channel: AmbientChannel, idleMillis: Long, rainProbability: Int?): Int = when (modeFor(channel)) {
        GlyphChannelMode.OFF -> GlyphLight.OFF
        GlyphChannelMode.RAIN -> GlyphLight.forRain(rainProbability)
        GlyphChannelMode.IDLE -> GlyphLight.forIdle(idleMillis, idleThresholdMinutes)
    }

    companion object {
        val ALLOWED_IDLE_MINUTES = listOf(15, 30, 60, 120)
        const val DEFAULT_IDLE_MINUTES = 30
    }
}

/**
 * Точка для прогноза. Храним координаты, а не название: геокодер нужен один раз,
 * при выборе места, а дальше прогноз берётся по широте и долготе.
 */
@Serializable
data class WeatherPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

/** Последний удачно полученный прогноз дождя. */
@Serializable
data class RainForecast(
    /** Максимальная вероятность осадков на ближайшие часы, 0..100. */
    val probabilityPercent: Int,
    val fetchedAtMillis: Long,
)

/**
 * Яркость канала Glyph. SDK кладёт в кадр 12-битное значение, а
 * `buildChannel(code)` без яркости подставляет свой DEFAULT_LIGHT = 4000 —
 * его и берём за максимум, чтобы A и B не выбивались из полосы C.
 */
object GlyphLight {
    const val OFF = 0
    const val MAX = 4000

    /** Ниже этого канал на (2a) уже не разглядеть, поэтому это «тлеет». */
    const val FAINT = 600

    /**
     * Яркость функции простоя [idleMillis] против порога [thresholdMinutes].
     *
     * До порога канал погашен, на пороге начинает тлеть и разгорается до
     * максимума к четырёхкратному порогу — так «лежит полчаса» и «лежит два
     * часа» отличаются на глаз, без всякой шкалы.
     */
    fun forIdle(idleMillis: Long, thresholdMinutes: Int): Int {
        val threshold = thresholdMinutes.toLong() * 60_000L
        if (threshold <= 0L || idleMillis < threshold) return OFF
        val span = threshold * 3L
        val over = (idleMillis - threshold).coerceAtMost(span)
        val ramp = ((MAX - FAINT) * over / span).toInt()
        return (FAINT + ramp).coerceIn(FAINT, MAX)
    }

    /**
     * Яркость функции дождя.
     *
     * Ступенями, а не линейно: абсолютную яркость одиночного светодиода глаз без
     * эталона не читает, а вот четыре различимых уровня — вполне.
     */
    fun forRain(probabilityPercent: Int?): Int = when {
        probabilityPercent == null -> OFF
        probabilityPercent < 30 -> OFF
        probabilityPercent < 50 -> FAINT
        probabilityPercent < 70 -> 1800
        else -> MAX
    }
}
