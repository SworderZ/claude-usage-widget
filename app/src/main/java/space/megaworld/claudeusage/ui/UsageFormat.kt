package space.megaworld.claudeusage.ui

import space.megaworld.claudeusage.data.UsageSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Уровень заполнения окна: до 70 % обычный, 70–90 % жёлтый, свыше 90 % красный. */
enum class UsageLevel { NORMAL, WARNING, CRITICAL }

object UsageFormat {

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())

    fun level(utilization: Double): UsageLevel = when {
        utilization > 90.0 -> UsageLevel.CRITICAL
        utilization >= 70.0 -> UsageLevel.WARNING
        else -> UsageLevel.NORMAL
    }

    fun percent(utilization: Double): String = "${utilization.roundToInt()}%"

    /** Доля 0..1 для прогресс-бара. */
    fun fraction(utilization: Double): Float = (utilization / 100.0).coerceIn(0.0, 1.0).toFloat()

    /**
     * Длительность окна по его ключу. Сервер её не присылает, но для известных окон она
     * задана названием: five_hour — пять часов, любое seven_day* — семь суток.
     */
    fun windowDurationMillis(key: String): Long? = when {
        key == UsageSnapshot.KEY_FIVE_HOUR -> 5L * 60 * 60 * 1000
        key.startsWith(UsageSnapshot.KEY_SEVEN_DAY) -> 7L * 24 * 60 * 60 * 1000
        else -> null
    }

    /**
     * Сколько процентов самого окна уже прошло. Рядом с расходом показывает, опережаешь
     * ты часы или отстаёшь: расход 62% при истекших 7% времени — это очень быстро.
     */
    fun elapsedPercent(
        key: String,
        resetsAtMillis: Long?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int? {
        val duration = windowDurationMillis(key) ?: return null
        if (resetsAtMillis == null) return null
        val remaining = (resetsAtMillis - nowMillis).coerceIn(0, duration)
        val elapsed = duration - remaining
        return ((elapsed.toDouble() / duration) * 100).roundToInt().coerceIn(0, 100)
    }

    /**
     * «Сброс через 4 ч. 37 мин.» для близкого сброса и «Сброс ср 22:00» для далёкого:
     * обратный отсчёт в днях читается хуже, чем конкретный день недели и время.
     */
    fun resetTextLong(
        resetsAtMillis: Long?,
        nowMillis: Long = System.currentTimeMillis(),
    ): String? {
        if (resetsAtMillis == null) return null
        val deltaMinutes = ((resetsAtMillis - nowMillis).toDouble() / 60_000.0).roundToLong()
        if (deltaMinutes <= 0) return "Сброс вот-вот"
        if (deltaMinutes >= 24 * 60) {
            val moment = Instant.ofEpochMilli(resetsAtMillis).atZone(ZoneId.systemDefault())
            return "Сброс ${weekday(moment.dayOfWeek)} ${timeFormatter.format(moment)}"
        }
        val hours = deltaMinutes / 60
        val minutes = deltaMinutes % 60
        val body = if (hours > 0) "$hours ч. $minutes мин." else "$minutes мин."
        return "Сброс через $body"
    }

    private fun weekday(day: java.time.DayOfWeek): String = when (day) {
        java.time.DayOfWeek.MONDAY -> "пн"
        java.time.DayOfWeek.TUESDAY -> "вт"
        java.time.DayOfWeek.WEDNESDAY -> "ср"
        java.time.DayOfWeek.THURSDAY -> "чт"
        java.time.DayOfWeek.FRIDAY -> "пт"
        java.time.DayOfWeek.SATURDAY -> "сб"
        java.time.DayOfWeek.SUNDAY -> "вс"
    }

    /** Человекочитаемое название окна; незнакомые ключи показываем как есть. */
    fun windowLabel(key: String): String = when (key) {
        UsageSnapshot.KEY_FIVE_HOUR -> "5ч"
        UsageSnapshot.KEY_SEVEN_DAY -> "7д"
        "seven_day_opus" -> "7д Opus"
        "seven_day_sonnet" -> "7д Sonnet"
        "seven_day_haiku" -> "7д Haiku"
        else -> key.replace('_', ' ')
    }

    /** Развёрнутое название окна для крупного виджета. */
    fun windowTitle(key: String): String = when (key) {
        UsageSnapshot.KEY_FIVE_HOUR -> "Текущая сессия"
        UsageSnapshot.KEY_SEVEN_DAY -> "Еженедельные лимиты"
        "seven_day_opus" -> "Неделя, Opus"
        "seven_day_sonnet" -> "Неделя, Sonnet"
        else -> key.replace('_', ' ')
    }

    /** «сброс через 2ч 14м». null, если сервер не прислал время сброса. */
    fun resetHint(resetsAtMillis: Long?, nowMillis: Long = System.currentTimeMillis()): String? {
        val remaining = remaining(resetsAtMillis, nowMillis) ?: return null
        return "сброс через $remaining"
    }

    /** «2ч 14м», «43м», «< 1м». */
    fun remaining(resetsAtMillis: Long?, nowMillis: Long = System.currentTimeMillis()): String? {
        if (resetsAtMillis == null) return null
        val deltaMinutes = ((resetsAtMillis - nowMillis).toDouble() / 60_000.0).roundToLong()
        if (deltaMinutes <= 0) return "< 1м"
        val days = deltaMinutes / (24 * 60)
        val hours = (deltaMinutes % (24 * 60)) / 60
        val minutes = deltaMinutes % 60
        return when {
            days > 0 -> "${days}д ${hours}ч"
            hours > 0 -> "${hours}ч ${minutes}м"
            else -> "${minutes}м"
        }
    }

    /** Время последнего успешного обновления: сегодня — только часы, иначе с датой. */
    fun updatedAt(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        if (millis <= 0L) return "—"
        val zone = ZoneId.systemDefault()
        val then = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val formatter = if (then == now) timeFormatter else dateTimeFormatter
        return formatter.format(Instant.ofEpochMilli(millis))
    }
}
