package space.megaworld.claudeusage.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@OptIn(ExperimentalCoroutinesApi::class)
class UsageRepository(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val settingsStore: SettingsStore,
    private val apiClient: ApiClient,
    private val openAiCredentialStore: CredentialStore = CredentialStore(context, "openai"),
    private val weatherClient: WeatherClient = WeatherClient(),
) {

    private val refreshMutex = Mutex()
    private val weatherMutex = Mutex()

    private fun cache(provider: UsageProvider): Flow<CachedUsage> = context.appDataStore.data.map { prefs ->
        val snapshot = prefs[snapshotKey(provider)]?.let { raw ->
            runCatching { json.decodeFromString<UsageSnapshot>(raw) }.getOrNull()
        }
        val status = prefs[statusKey(provider)]?.let { raw ->
            runCatching { UsageStatus.valueOf(raw) }.getOrNull()
        }
        CachedUsage(snapshot, status, prefs[errorKey(provider)])
    }

    val state: Flow<UsageState> = observeState(settingsStore.provider)
    fun widgetState(appWidgetId: Int): Flow<UsageState> = observeState(settingsStore.widgetProvider(appWidgetId))

    private fun observeState(source: Flow<UsageProvider>): Flow<UsageState> =
        source.flatMapLatest { provider ->
            combine(
                credentialsFor(provider).hasCredentials,
                settingsStore.organizations,
                settingsStore.organizationUuid,
                settingsStore.refreshIntervalMinutes,
                cache(provider),
            ) { hasCredentials, organizations, organizationUuid, interval, cached ->
                val status = when {
                    !hasCredentials -> UsageStatus.NOT_AUTHORIZED
                    cached.status == null -> UsageStatus.NEVER_LOADED
                    cached.status == UsageStatus.NOT_AUTHORIZED -> UsageStatus.NEVER_LOADED
                    else -> cached.status
                }
                UsageState(
                    provider = provider,
                    status = status,
                    snapshot = if (hasCredentials) cached.snapshot else null,
                    errorMessage = cached.error,
                    organizations = organizations,
                    organizationUuid = organizationUuid,
                    refreshIntervalMinutes = interval,
                )
                // Остальные настройки подмешиваем после основных пяти потоков.
            }.combine(settingsStore.glyphEnabled) { state, glyphEnabled ->
                state.copy(glyphEnabled = glyphEnabled)
            }.combine(settingsStore.glyphRenderMode) { state, mode ->
                state.copy(glyphRenderMode = mode)
            }.combine(settingsStore.ambientState) { state, ambient ->
                state.copy(ambient = ambient.settings, rainForecast = ambient.forecast)
            }.combine(settingsStore.widgetProvider) { state, widgetProvider ->
                state.copy(widgetProvider = widgetProvider)
            }
        }

    suspend fun currentState(): UsageState = state.first()

    /**
     * Сохраняет cookie/UA, снятые с WebView, и сразу подтягивает организации.
     * Если организация ровно одна — выбирает её, иначе выбор остаётся за настройками.
     */
    suspend fun onLoggedIn(cookieHeader: String, userAgent: String): RefreshResult {
        settingsStore.setProvider(UsageProvider.CLAUDE)
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
    suspend fun refresh(): RefreshResult = refresh(settingsStore.provider.first())

    suspend fun refreshDisplayedSources(): List<RefreshResult> {
        val targets = settingsStore.displayedProviders()
        return targets.map { refresh(it) }
    }

    suspend fun refresh(provider: UsageProvider): RefreshResult = refreshMutex.withLock {
        if (provider == UsageProvider.CODEX) {
            val credentials = openAiCredentialStore.load() ?: run {
                writeStatus(UsageStatus.NOT_AUTHORIZED, null, provider)
                return@withLock RefreshResult.NotAuthorized
            }
            return@withLock when (val result = OpenAiSessionClient().fetch(credentials, openAiCredentialStore::save)) {
                is ApiResult.Success -> {
                    val snapshot = UsageSnapshot(windows = result.value, fetchedAtMillis = System.currentTimeMillis())
                    context.appDataStore.edit {
                        it[snapshotKey(provider)] = json.encodeToString(UsageSnapshot.serializer(), snapshot)
                        it[statusKey(provider)] = UsageStatus.OK.name
                        it.remove(errorKey(provider))
                    }
                    RefreshResult.Success(snapshot)
                }
                ApiResult.Unauthorized -> {
                    writeStatus(UsageStatus.SESSION_EXPIRED, null, provider)
                    RefreshResult.SessionExpired
                }
                is ApiResult.Failure -> {
                    writeStatus(UsageStatus.NETWORK_ERROR, result.message, provider)
                    RefreshResult.Failure(result.message)
                }
            }
        }
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
                    prefs[snapshotKey(provider)] = json.encodeToString(UsageSnapshot.serializer(), snapshot)
                    prefs[statusKey(provider)] = UsageStatus.OK.name
                    prefs.remove(errorKey(provider))
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
    suspend fun logout() = refreshMutex.withLock {
        val provider = settingsStore.provider.first()
        credentialsFor(provider).clear()
        if (provider == UsageProvider.CLAUDE) settingsStore.clear()
        context.appDataStore.edit { prefs ->
            prefs.remove(snapshotKey(provider))
            prefs.remove(errorKey(provider))
            prefs[statusKey(provider)] = UsageStatus.NOT_AUTHORIZED.name
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

    suspend fun setChannelMode(channel: AmbientChannel, mode: GlyphChannelMode) {
        settingsStore.setChannelMode(channel, mode)
        if (mode == GlyphChannelMode.RAIN) refreshWeatherIfStale()
    }

    suspend fun setStripMode(mode: GlyphStripMode) {
        settingsStore.setStripMode(mode)
        if (mode == GlyphStripMode.RAIN) refreshWeatherIfStale()
    }

    suspend fun setIdleThresholdMinutes(minutes: Int) {
        settingsStore.setIdleThresholdMinutes(minutes)
    }

    /**
     * Название места → координаты, и сразу первый прогноз, чтобы индикация дождя появилась
     * не дожидаясь следующего тика.
     */
    suspend fun addWeatherPlace(query: String): ApiResult<WeatherPlace> {
        return when (val result = weatherClient.geocode(query)) {
            is ApiResult.Success -> {
                weatherMutex.withLock {
                    settingsStore.addWeatherPlace(result.value)
                }
                refreshWeatherIfStale()
                result
            }
            ApiResult.Unauthorized -> ApiResult.Failure("Сервис погоды отказал")
            is ApiResult.Failure -> result
        }
    }

    suspend fun selectWeatherPlace(place: WeatherPlace) {
        weatherMutex.withLock { settingsStore.selectWeatherPlace(place) }
        refreshWeatherIfStale()
    }

    suspend fun removeWeatherPlace(place: WeatherPlace) {
        weatherMutex.withLock { settingsStore.removeWeatherPlace(place) }
        refreshWeatherIfStale()
    }

    /**
     * Обновляет прогноз, если прежний устарел.
     *
     * Свой срок годности, а не общий интервал обновления: лимиты claude меняются
     * ежеминутно, а осадки на три часа вперёд — нет, и дёргать чужой бесплатный
     * сервис каждые пять минут незачем.
     */
    suspend fun refreshWeatherIfStale() = weatherMutex.withLock {
        val ambient = settingsStore.currentAmbientState()
        val place = ambient.settings.place?.takeIf { ambient.settings.rainEnabled } ?: return@withLock
        val previous = ambient.forecast
        val age = System.currentTimeMillis() - (previous?.fetchedAtMillis ?: 0L)
        if (previous != null && age < WEATHER_TTL_MILLIS) return@withLock
        refreshWeather(place)
    }

    /** Неудача намеренно тихая: индикация дождя доживёт на прежнем прогнозе. */
    private suspend fun refreshWeather(place: WeatherPlace) {
        val result = weatherClient.fetchRain(place)
        if (result is ApiResult.Success) settingsStore.setRainForecast(place, result.value)
    }

    suspend fun setWidgetProvider(provider: UsageProvider) {
        settingsStore.setWidgetProvider(provider)
    }

    suspend fun selectProvider(provider: UsageProvider) = refreshMutex.withLock {
        settingsStore.setProvider(provider)
    }

    suspend fun importOpenAi(raw: String): RefreshResult = refreshMutex.withLock {
        val credentials = parseOpenAiCredentials(raw)
        when (val result = OpenAiUsageClient().fetch(credentials)) {
            ApiResult.Unauthorized -> RefreshResult.SessionExpired
            is ApiResult.Failure -> RefreshResult.Failure(result.message)
            is ApiResult.Success -> {
                val snapshot = UsageSnapshot(windows = result.value, fetchedAtMillis = System.currentTimeMillis())
                openAiCredentialStore.save(credentials)
                context.appDataStore.edit {
                    it[snapshotKey(UsageProvider.CODEX)] = json.encodeToString(UsageSnapshot.serializer(), snapshot)
                    it[statusKey(UsageProvider.CODEX)] = UsageStatus.OK.name
                    it.remove(errorKey(UsageProvider.CODEX))
                }
                settingsStore.setProvider(UsageProvider.CODEX)
                RefreshResult.Success(snapshot)
            }
        }
    }

    /** A successful device login creates an independent, renewable phone session. */
    internal suspend fun connectOpenAi(credentials: Credentials): RefreshResult {
        refreshMutex.withLock {
            openAiCredentialStore.save(credentials)
            settingsStore.setProvider(UsageProvider.CODEX)
            context.appDataStore.edit {
                it.remove(snapshotKey(UsageProvider.CODEX))
                it.remove(errorKey(UsageProvider.CODEX))
                it[statusKey(UsageProvider.CODEX)] = UsageStatus.NEVER_LOADED.name
            }
        }
        return refresh(UsageProvider.CODEX)
    }

    private fun credentialsFor(provider: UsageProvider) = if (provider == UsageProvider.CODEX) openAiCredentialStore else credentialStore
    private fun snapshotKey(provider: UsageProvider) = if (provider == UsageProvider.CLAUDE) KEY_SNAPSHOT else stringPreferencesKey("openai_usage_snapshot")
    private fun statusKey(provider: UsageProvider) = if (provider == UsageProvider.CLAUDE) KEY_STATUS else stringPreferencesKey("openai_usage_status")
    private fun errorKey(provider: UsageProvider) = if (provider == UsageProvider.CLAUDE) KEY_ERROR else stringPreferencesKey("openai_usage_error")

    private suspend fun writeStatus(status: UsageStatus, error: String?, provider: UsageProvider = UsageProvider.CLAUDE) {
        context.appDataStore.edit { prefs ->
            prefs[statusKey(provider)] = status.name
            if (error == null) prefs.remove(errorKey(provider)) else prefs[errorKey(provider)] = error
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
