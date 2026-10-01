package space.megaworld.claudeusage.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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

    /** Индикация на полосе C Glyph. По умолчанию выключена: нужна и модель, и включённая отладка SDK. */
    val glyphEnabled: Flow<Boolean> =
        context.appDataStore.data.map { it[KEY_GLYPH_ENABLED] ?: false }

    suspend fun setGlyphEnabled(enabled: Boolean) {
        context.appDataStore.edit { it[KEY_GLYPH_ENABLED] = enabled }
    }

    val glyphRenderMode: Flow<GlyphRenderMode> = context.appDataStore.data.map { prefs ->
        prefs[KEY_GLYPH_MODE]
            ?.let { raw -> runCatching { GlyphRenderMode.valueOf(raw) }.getOrNull() }
            ?: GlyphRenderMode.PROGRESS
    }

    suspend fun setGlyphRenderMode(mode: GlyphRenderMode) {
        context.appDataStore.edit { it[KEY_GLYPH_MODE] = mode.name }
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
        /**
         * Короче 15 минут WorkManager не умеет — такие интервалы обслуживает
         * UsageForegroundService, и за них платится постоянным уведомлением.
         */
        val ALLOWED_INTERVALS = listOf(5, 10, 15, 30, 60)
        const val DEFAULT_INTERVAL_MINUTES = 30

        private val KEY_ORG_UUID = stringPreferencesKey("organization_uuid")
        private val KEY_ORGS = stringPreferencesKey("organizations")
        private val KEY_INTERVAL = intPreferencesKey("refresh_interval_minutes")
        private val KEY_GLYPH_ENABLED = booleanPreferencesKey("glyph_enabled")
        private val KEY_GLYPH_MODE = stringPreferencesKey("glyph_render_mode")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
