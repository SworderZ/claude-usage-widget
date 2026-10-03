package space.megaworld.claudeusage.data

import kotlinx.serialization.Serializable

/** Организация claude.ai. Поля nullable: ответ неофициального API может измениться. */
@Serializable
data class Organization(
    val uuid: String,
    val name: String? = null,
)

/**
 * Одно окно лимита. [key] — сырой ключ из ответа API (`five_hour`, `seven_day`,
 * `seven_day_opus`, ...), незнакомые ключи тоже сюда попадают.
 */
@Serializable
data class UsageWindow(
    val key: String,
    val utilization: Double,
    val resetsAtMillis: Long? = null,
)

@Serializable
data class UsageSnapshot(
    val organizationUuid: String? = null,
    val windows: List<UsageWindow> = emptyList(),
    val fetchedAtMillis: Long = 0L,
) {
    fun window(key: String): UsageWindow? = windows.firstOrNull { it.key == key }

    val fiveHour: UsageWindow? get() = window(KEY_FIVE_HOUR)
    val sevenDay: UsageWindow? get() = window(KEY_SEVEN_DAY)

    companion object {
        const val KEY_FIVE_HOUR = "five_hour"
        const val KEY_SEVEN_DAY = "seven_day"
    }
}

/**
 * Как рисовать заполнение полосы C. Документация обещает прогресс «на C1/D1», но
 * Phone (2a) описан в ней отдельной таблицей, поэтому вариант выбирается опытным путём.
 */
enum class GlyphRenderMode {
    /** displayProgress SDK, направление по умолчанию. */
    PROGRESS,

    /** То же, но с флагом reverse — если заливка идёт не с той стороны. */
    PROGRESS_REVERSED,

    /** Зажигаем C_1..C_24 сами: обход на случай, если displayProgress лёг криво. */
    SEGMENTS;

    val label: String
        get() = when (this) {
            PROGRESS -> "Прогресс SDK"
            PROGRESS_REVERSED -> "Прогресс SDK, наоборот"
            SEGMENTS -> "Сегменты вручную"
        }
}

/** Статус последней попытки обновления — из него виджет выбирает, что рисовать. */
enum class UsageStatus {
    /** Ни разу не логинились (нет cookie). */
    NOT_AUTHORIZED,

    /** Cookie есть, но данных ещё нет. */
    NEVER_LOADED,

    /** Последнее обновление успешно. */
    OK,

    /** 401/403 — сессия истекла либо прилетел челлендж Cloudflare. */
    SESSION_EXPIRED,

    /** Сеть/таймаут/нераспознанный ответ. Показываем кеш приглушённо. */
    NETWORK_ERROR,
}

/** Полное состояние, которым питаются и виджет, и главный экран. */
data class UsageState(
    val status: UsageStatus = UsageStatus.NOT_AUTHORIZED,
    val snapshot: UsageSnapshot? = null,
    val errorMessage: String? = null,
    val organizations: List<Organization> = emptyList(),
    val organizationUuid: String? = null,
    val refreshIntervalMinutes: Int = SettingsStore.DEFAULT_INTERVAL_MINUTES,
    val provider: UsageProvider = UsageProvider.CLAUDE,
    val glyphEnabled: Boolean = false,
    val glyphRenderMode: GlyphRenderMode = GlyphRenderMode.PROGRESS,
    val ambient: AmbientSettings = AmbientSettings(),
    val rainForecast: RainForecast? = null,
) {
    val hasData: Boolean get() = snapshot != null && snapshot.windows.isNotEmpty()
    val isStale: Boolean get() = status == UsageStatus.NETWORK_ERROR || status == UsageStatus.SESSION_EXPIRED
}

/** Результат одного вызова [UsageRepository.refresh]. */
sealed interface RefreshResult {
    data class Success(val snapshot: UsageSnapshot) : RefreshResult
    data object NotAuthorized : RefreshResult
    data object SessionExpired : RefreshResult
    data class Failure(val message: String) : RefreshResult
}
