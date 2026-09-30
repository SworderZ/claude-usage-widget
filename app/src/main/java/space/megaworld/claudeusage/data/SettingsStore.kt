package space.megaworld.claudeusage.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Несекретные настройки: выбранная организация и интервал обновления. */
class SettingsStore(private val context: Context) {

    val organizationUuid: Flow<String?> =
        context.appDataStore.data.map { it[KEY_ORG_UUID] }

    val organizations: Flow<List<Organization>> = context.appDataStore.data.map { prefs ->
        prefs[KEY_ORGS]?.let { raw ->
            runCatching { json.decodeFromString<List<Organization>>(raw) }.getOrDefault(emptyList())
        }.orEmpty()
    }

    val refreshIntervalMinutes: Flow<Int> = context.appDataStore.data.map { prefs ->
        prefs[KEY_INTERVAL]?.takeIf { it in ALLOWED_INTERVALS } ?: DEFAULT_INTERVAL_MINUTES
    }

    suspend fun currentIntervalMinutes(): Int = refreshIntervalMinutes.first()

    suspend fun setOrganizationUuid(uuid: String?) {
        context.appDataStore.edit { prefs ->
            if (uuid == null) prefs.remove(KEY_ORG_UUID) else prefs[KEY_ORG_UUID] = uuid
        }
    }

    suspend fun setOrganizations(list: List<Organization>) {
        context.appDataStore.edit { prefs ->
            prefs[KEY_ORGS] = json.encodeToString(ListSerializer(Organization.serializer()), list)
        }
    }

    suspend fun setRefreshIntervalMinutes(minutes: Int) {
        val safe = if (minutes in ALLOWED_INTERVALS) minutes else DEFAULT_INTERVAL_MINUTES
        context.appDataStore.edit { it[KEY_INTERVAL] = safe }
    }

    suspend fun clear() {
        context.appDataStore.edit { prefs ->
            prefs.remove(KEY_ORG_UUID)
            prefs.remove(KEY_ORGS)
        }
    }

    companion object {
        /** Минимум WorkManager для периодической работы — 15 минут. */
        val ALLOWED_INTERVALS = listOf(15, 30, 60)
        const val DEFAULT_INTERVAL_MINUTES = 30

        private val KEY_ORG_UUID = stringPreferencesKey("organization_uuid")
        private val KEY_ORGS = stringPreferencesKey("organizations")
        private val KEY_INTERVAL = intPreferencesKey("refresh_interval_minutes")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
