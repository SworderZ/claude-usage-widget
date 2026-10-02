package space.megaworld.claudeusage.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Единственная точка входа к данным о расходе лимитов: сеть + кеш + статус.
 *
 * Слой намеренно ничего не знает про Compose/Glance — этот же репозиторий потом
 * сможет кормить индикацию Glyph на Nothing Phone (2a).
 */
class UsageRepository(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val settingsStore: SettingsStore,
    private val apiClient: ApiClient,
    private val weatherClient: WeatherClient = WeatherClient(),
) {

    private val refreshMutex = Mutex()

    private val cache: Flow<CachedUsage> = context.appDataStore.data.map { prefs ->
        val snapshot = prefs[KEY_SNAPSHOT]?.let { raw ->
            runCatching { json.decodeFromString<UsageSnapshot>(raw) }.getOrNull()
        }
        val status = prefs[KEY_STATUS]?.let { raw ->
            runCatching { UsageStatus.valueOf(raw) }.getOrNull()
        }
        CachedUsage(snapshot, status, prefs[KEY_ERROR])
    }

    val state: Flow<UsageState> = combine(
        credentialStore.hasCredentials,
        settingsStore.organizations,
        settingsStore.organizationUuid,
        settingsStore.refreshIntervalMinutes,
        cache,
    ) { hasCredentials, organizations, organizationUuid, interval, cached ->
        val status = when {
            !hasCredentials -> UsageStatus.NOT_AUTHORIZED
            cached.status == null -> UsageStatus.NEVER_LOADED
            cached.status == UsageStatus.NOT_AUTHORIZED -> UsageStatus.NEVER_LOADED
            else -> cached.status
        }
        UsageState(
            status = status,
            snapshot = cached.snapshot,
            errorMessage = cached.error,
            organizations = organizations,
            organizationUuid = organizationUuid,
            refreshIntervalMinutes = interval,
        )
    // combine с типами ограничен пятью потоками, шестой подмешиваем отдельно.
    }.combine(settingsStore.glyphEnabled) { state, glyphEnabled ->
        state.copy(glyphEnabled = glyphEnabled)
    }.combine(settingsStore.glyphRenderMode) { state, mode ->
        state.copy(glyphRenderMode = mode)
    }.combine(settingsStore.ambient) { state, ambient ->
        state.copy(ambient = ambient)
    }.combine(settingsStore.rainForecast) { state, forecast ->
        state.copy(rainForecast = forecast)
    }

    suspend fun currentState(): UsageState = state.first()

    /**
     * Сохраняет cookie/UA, снятые с WebView, и сразу подтягивает организации.
     * Если организация ровно одна — выбирает её, иначе выбор остаётся за настройками.
     */
    suspend fun onLoggedIn(cookieHeader: String, userAgent: String): RefreshResult {
        credentialStore.save(
            Credentials(
                cookieHeader = cookieHeader,
                userAgent = userAgent,
                savedAtMillis = System.currentTimeMillis(),
            )
        )
        writeStatus(UsageStatus.NEVER_LOADED, null)
        return when (val orgs = loadOrganizations()) {
            is ApiResult.Success -> refresh()
            ApiResult.Unauthorized -> {
                writeStatus(UsageStatus.SESSION_EXPIRED, null)
                RefreshResult.SessionExpired
            }
            is ApiResult.Failure -> {
                writeStatus(UsageStatus.NETWORK_ERROR, orgs.message)
                RefreshResult.Failure(orgs.message)
            }
        }
    }

    /** Перечитывает список организаций (кнопка в настройках). */
    suspend fun loadOrganizations(): ApiResult<List<Organization>> {
        val credentials = credentialStore.load() ?: return ApiResult.Unauthorized
        val result = apiClient.fetchOrganizations(credentials)
        if (result is ApiResult.Success) {
            settingsStore.setOrganizations(result.value)
            val selected = settingsStore.organizationUuid.first()
            if (selected == null || result.value.none { it.uuid == selected }) {
                settingsStore.setOrganizationUuid(result.value.firstOrNull()?.uuid)
            }
        }
        return result
    }

    /** Один цикл обновления. Вызывается воркером, кнопкой «Обновить» и тапом по виджету. */
    suspend fun refresh(): RefreshResult = refreshMutex.withLock {
        val credentials = credentialStore.load()
        if (credentials == null) {
            writeStatus(UsageStatus.NOT_AUTHORIZED, null)
            return@withLock RefreshResult.NotAuthorized
        }

        val organizationUuid = settingsStore.organizationUuid.first()
            ?: when (val orgs = apiClient.fetchOrganizations(credentials)) {
                is ApiResult.Success -> {
                    settingsStore.setOrganizations(orgs.value)
                    orgs.value.firstOrNull()?.uuid?.also { settingsStore.setOrganizationUuid(it) }
                }
                ApiResult.Unauthorized -> {
                    writeStatus(UsageStatus.SESSION_EXPIRED, null)
                    return@withLock RefreshResult.SessionExpired
                }
                is ApiResult.Failure -> {
                    writeStatus(UsageStatus.NETWORK_ERROR, orgs.message)
                    return@withLock RefreshResult.Failure(orgs.message)
                }
            } ?: run {
                val message = "Не найдено ни одной организации"
                writeStatus(UsageStatus.NETWORK_ERROR, message)
                return@withLock RefreshResult.Failure(message)
            }

        when (val usage = apiClient.fetchUsage(credentials, organizationUuid)) {
            is ApiResult.Success -> {
                val snapshot = UsageSnapshot(
                    organizationUuid = organizationUuid,
                    windows = usage.value,
                    fetchedAtMillis = System.currentTimeMillis(),
                )
                context.appDataStore.edit { prefs ->
                    prefs[KEY_SNAPSHOT] = json.encodeToString(UsageSnapshot.serializer(), snapshot)
                    prefs[KEY_STATUS] = UsageStatus.OK.name
                    prefs.remove(KEY_ERROR)
                }
                RefreshResult.Success(snapshot)
            }
            ApiResult.Unauthorized -> {
                writeStatus(UsageStatus.SESSION_EXPIRED, null)
                RefreshResult.SessionExpired
            }
            is ApiResult.Failure -> {
                writeStatus(UsageStatus.NETWORK_ERROR, usage.message)
                RefreshResult.Failure(usage.message)
            }
        }
    }

    /** Полный выход: токены, настройки и кеш. Cookies WebView чистит вызывающая сторона. */
    suspend fun logout() {
        credentialStore.clear()
        settingsStore.clear()
        context.appDataStore.edit { prefs ->
            prefs.remove(KEY_SNAPSHOT)
            prefs.remove(KEY_ERROR)
            prefs[KEY_STATUS] = UsageStatus.NOT_AUTHORIZED.name
        }
    }

    suspend fun selectOrganization(uuid: String) {
        settingsStore.setOrganizationUuid(uuid)
    }

    suspend fun setRefreshIntervalMinutes(minutes: Int) {
        settingsStore.setRefreshIntervalMinutes(minutes)
    }

    suspend fun setGlyphEnabled(enabled: Boolean) {
        settingsStore.setGlyphEnabled(enabled)
    }

    suspend fun setGlyphRenderMode(mode: GlyphRenderMode) {
        settingsStore.setGlyphRenderMode(mode)
    }

    suspend fun setIdleEnabled(enabled: Boolean) {
        settingsStore.setIdleEnabled(enabled)
    }

    suspend fun setIdleThresholdMinutes(minutes: Int) {
        settingsStore.setIdleThresholdMinutes(minutes)
    }

    suspend fun setRainEnabled(enabled: Boolean) {
        settingsStore.setRainEnabled(enabled)
    }

    /**
     * Название места → координаты, и сразу первый прогноз, чтобы канал B зажёгся
     * не дожидаясь следующего тика.
     */
    suspend fun selectWeatherPlace(query: String): ApiResult<WeatherPlace> {
        return when (val result = weatherClient.geocode(query)) {
            is ApiResult.Success -> {
                settingsStore.setWeatherPlace(result.value)
                refreshWeather(result.value)
                result
            }
            ApiResult.Unauthorized -> ApiResult.Failure("Сервис погоды отказал")
            is ApiResult.Failure -> result
        }
    }

    suspend fun clearWeatherPlace() {
        settingsStore.setWeatherPlace(null)
    }

    /**
     * Обновляет прогноз, если прежний устарел.
     *
     * Свой срок годности, а не общий интервал обновления: лимиты claude меняются
     * ежеминутно, а осадки на три часа вперёд — нет, и дёргать чужой бесплатный
     * сервис каждые пять минут незачем.
     */
    suspend fun refreshWeatherIfStale() {
        val ambient = settingsStore.currentAmbient()
        val place = ambient.place?.takeIf { ambient.rainEnabled } ?: return
        val previous = settingsStore.currentRainForecast()
        val age = System.currentTimeMillis() - (previous?.fetchedAtMillis ?: 0L)
        if (previous != null && age < WEATHER_TTL_MILLIS) return
        refreshWeather(place)
    }

    /** Неудача намеренно тихая: канал B доживёт на прежнем прогнозе. */
    private suspend fun refreshWeather(place: WeatherPlace) {
        val result = weatherClient.fetchRain(place)
        if (result is ApiResult.Success) settingsStore.setRainForecast(result.value)
    }

    private suspend fun writeStatus(status: UsageStatus, error: String?) {
        context.appDataStore.edit { prefs ->
            prefs[KEY_STATUS] = status.name
            if (error == null) prefs.remove(KEY_ERROR) else prefs[KEY_ERROR] = error
        }
    }

    private data class CachedUsage(
        val snapshot: UsageSnapshot?,
        val status: UsageStatus?,
        val error: String?,
    )

    private companion object {
        val KEY_SNAPSHOT = stringPreferencesKey("usage_snapshot")
        val KEY_STATUS = stringPreferencesKey("usage_status")
        val KEY_ERROR = stringPreferencesKey("usage_error")

        /** Срок годности прогноза: осадки на три часа вперёд чаще не пересматривают. */
        const val WEATHER_TTL_MILLIS = 30L * 60_000L

        val json = Json { ignoreUnknownKeys = true }
    }
}
