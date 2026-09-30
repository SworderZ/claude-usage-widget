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
        val json = Json { ignoreUnknownKeys = true }
    }
}
