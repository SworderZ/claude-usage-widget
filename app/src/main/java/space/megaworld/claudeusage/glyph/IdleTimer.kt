package space.megaworld.claudeusage.glyph

/** Отсчёт A и абсолютные сроки перерисовки; часы должны включать время сна. */
class IdleTimer(private val elapsedMillis: () -> Long, initiallyInteractive: Boolean = true) {
    var screenOn: Boolean = initiallyInteractive
        private set
    private var screenOffAt: Long? = if (initiallyInteractive) null else elapsedMillis()

    fun setInteractive(interactive: Boolean) {
        if (screenOn == interactive) return
        screenOn = interactive
        screenOffAt = if (interactive) null else elapsedMillis()
    }

    val idleMillis: Long
        get() = screenOffAt?.let { (elapsedMillis() - it).coerceAtLeast(0L) } ?: 0L

    /**
     * Нулевой простой сразу после SCREEN_OFF уже требует будильника.
     * Срок привязан к выключению экрана: обновления лимитов не отодвигают его.
     * До порога телефон не будим; после него яркость меняем не чаще раза в 15 минут.
     */
    fun nextUpdateAt(thresholdMinutes: Int): Long? {
        val offAt = screenOffAt ?: return null
        if (thresholdMinutes <= 0) return null
        val threshold = thresholdMinutes.toLong() * 60_000L
        val idle = idleMillis
        if (idle < threshold) return offAt + threshold
        val maximumAt = offAt + threshold * 4L
        if (idle >= threshold * 4L) return null
        val step = (threshold / 4L).coerceAtLeast(15L * 60_000L)
        return (offAt + threshold + ((idle - threshold) / step + 1L) * step).coerceAtMost(maximumAt)
    }
}
