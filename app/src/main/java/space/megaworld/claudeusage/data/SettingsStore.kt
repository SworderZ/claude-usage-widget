package space.megaworld.claudeusage.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Несекретные настройки: выбранная организация и интервал обновления. */
class SettingsStore internal constructor(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.appDataStore)

    val provider: Flow<UsageProvider> = dataStore.data.map {
        runCatching { UsageProvider.valueOf(it[KEY_PROVIDER] ?: "CLAUDE") }.getOrDefault(UsageProvider.CLAUDE)
    }

    suspend fun setProvider(provider: UsageProvider) {
        dataStore.edit {
            // При первом переключении сохраняем прежний источник виджета.
            if (it[KEY_WIDGET_PROVIDER] == null) it[KEY_WIDGET_PROVIDER] = it[KEY_PROVIDER] ?: UsageProvider.CLAUDE.name
            it[KEY_PROVIDER] = provider.name
        }
    }

    val widgetProvider: Flow<UsageProvider> = dataStore.data.map {
        runCatching { UsageProvider.valueOf(it[KEY_WIDGET_PROVIDER] ?: it[KEY_PROVIDER] ?: "CLAUDE") }
            .getOrDefault(UsageProvider.CLAUDE)
    }

    suspend fun setWidgetProvider(provider: UsageProvider) {
        dataStore.edit { it[KEY_WIDGET_PROVIDER] = provider.name }
    }

    val organizationUuid: Flow<String?> =
        dataStore.data.map { it[KEY_ORG_UUID] }

    val organizations: Flow<List<Organization>> = dataStore.data.map { prefs ->
        prefs[KEY_ORGS]?.let { raw ->
            runCatching { json.decodeFromString<List<Organization>>(raw) }.getOrDefault(emptyList())
        }.orEmpty()
    }

    val refreshIntervalMinutes: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_INTERVAL]?.takeIf { it in ALLOWED_INTERVALS } ?: DEFAULT_INTERVAL_MINUTES
    }

    /** Индикация на полосе C Glyph. По умолчанию выключена: нужна и модель, и включённая отладка SDK. */
    val glyphEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_GLYPH_ENABLED] ?: false }

    suspend fun setGlyphEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_GLYPH_ENABLED] = enabled }
    }

    val glyphRenderMode: Flow<GlyphRenderMode> = dataStore.data.map { prefs ->
        prefs[KEY_GLYPH_MODE]
            ?.let { raw -> runCatching { GlyphRenderMode.valueOf(raw) }.getOrNull() }
            ?: GlyphRenderMode.PROGRESS
    }

    suspend fun setGlyphRenderMode(mode: GlyphRenderMode) {
        dataStore.edit { it[KEY_GLYPH_MODE] = mode.name }
    }

    /**
     * Настройки каналов A/B/C. Сложены в один поток:
     * combine в [UsageRepository] уже упёрся в предел по числу источников.
     */
    val ambientState: Flow<AmbientState> = dataStore.data.map { prefs ->
        val places = readWeatherPlaces(prefs)
        val selected = selectedWeatherPlace(prefs, places)
        val settings = AmbientSettings(
            // Включённые прежние функции меняем местами, выключенные оставляем выключенными.
            channelA = prefs[KEY_CHANNEL_A_MODE]?.let { runCatching { GlyphChannelMode.valueOf(it) }.getOrNull() }
                ?: if (prefs[KEY_RAIN_ENABLED] == true) GlyphChannelMode.RAIN else GlyphChannelMode.OFF,
            channelB = prefs[KEY_CHANNEL_B_MODE]?.let { runCatching { GlyphChannelMode.valueOf(it) }.getOrNull() }
                ?: if (prefs[KEY_IDLE_ENABLED] == true) GlyphChannelMode.IDLE else GlyphChannelMode.OFF,
            stripMode = prefs[KEY_STRIP_MODE]?.let { runCatching { GlyphStripMode.valueOf(it) }.getOrNull() }
                ?: GlyphStripMode.USAGE,
            idleThresholdMinutes = prefs[KEY_IDLE_MINUTES]
                ?.takeIf { it in AmbientSettings.ALLOWED_IDLE_MINUTES }
                ?: AmbientSettings.DEFAULT_IDLE_MINUTES,
            place = selected,
            places = places,
        )
        AmbientState(settings, selected?.let { readRainForecasts(prefs)[it.id] })
    }

    val ambient: Flow<AmbientSettings> = ambientState.map { it.settings }
    val rainForecast: Flow<RainForecast?> = ambientState.map { it.forecast }

    suspend fun currentAmbientState(): AmbientState = ambientState.first()
    suspend fun currentAmbient(): AmbientSettings = ambient.first()
    suspend fun currentRainForecast(): RainForecast? = rainForecast.first()

    suspend fun setChannelMode(channel: AmbientChannel, mode: GlyphChannelMode) {
        val key = if (channel == AmbientChannel.A) KEY_CHANNEL_A_MODE else KEY_CHANNEL_B_MODE
        dataStore.edit { it[key] = mode.name }
    }

    suspend fun setStripMode(mode: GlyphStripMode) {
        dataStore.edit { it[KEY_STRIP_MODE] = mode.name }
    }

    suspend fun setIdleThresholdMinutes(minutes: Int) {
        val safe = if (minutes in AmbientSettings.ALLOWED_IDLE_MINUTES) {
            minutes
        } else {
            AmbientSettings.DEFAULT_IDLE_MINUTES
        }
        dataStore.edit { it[KEY_IDLE_MINUTES] = safe }
    }

    /** Сохраняет город и выбирает его; одинаковые координаты не создают дубликат. */
    suspend fun addWeatherPlace(place: WeatherPlace) {
        dataStore.edit { prefs ->
            val places = readWeatherPlaces(prefs)
            val saved = places.firstOrNull { it.id == place.id } ?: place
            val next = if (saved in places) places else places + saved
            saveWeather(prefs, next, saved, readRainForecasts(prefs))
        }
    }

    suspend fun selectWeatherPlace(place: WeatherPlace) {
        dataStore.edit { prefs ->
            val places = readWeatherPlaces(prefs)
            val saved = places.firstOrNull { it.id == place.id } ?: return@edit
            saveWeather(prefs, places, saved, readRainForecasts(prefs))
        }
    }

    /** При удалении выбранного города выбирается первый оставшийся. */
    suspend fun removeWeatherPlace(place: WeatherPlace) {
        dataStore.edit { prefs ->
            val places = readWeatherPlaces(prefs)
            if (places.none { it.id == place.id }) return@edit
            val selected = selectedWeatherPlace(prefs, places)
            val next = places.filterNot { it.id == place.id }
            val nextSelected = selected?.takeIf { it.id != place.id } ?: next.firstOrNull()
            saveWeather(prefs, next, nextSelected, readRainForecasts(prefs) - place.id)
        }
    }

    /** Ответ привязан к запросившему его городу, даже если выбор уже изменился. */
    suspend fun setRainForecast(place: WeatherPlace, forecast: RainForecast) {
        dataStore.edit { prefs ->
            val places = readWeatherPlaces(prefs)
            if (places.none { it.id == place.id }) return@edit
            saveWeather(prefs, places, selectedWeatherPlace(prefs, places),
                readRainForecasts(prefs) + (place.id to forecast))
        }
    }

    private fun readSelectedWeatherPlace(prefs: Preferences): WeatherPlace? =
        prefs[KEY_WEATHER_PLACE]?.let { raw ->
            runCatching { json.decodeFromString<WeatherPlace>(raw) }.getOrNull()
        }

    private fun readWeatherPlaces(prefs: Preferences): List<WeatherPlace> =
        (prefs[KEY_WEATHER_PLACES]?.let { raw ->
            runCatching { json.decodeFromString<List<WeatherPlace>>(raw) }.getOrNull()
        } ?: listOfNotNull(readSelectedWeatherPlace(prefs))).distinctBy { it.id }

    private fun selectedWeatherPlace(prefs: Preferences, places: List<WeatherPlace>): WeatherPlace? {
        val selected = readSelectedWeatherPlace(prefs)
        return places.firstOrNull { it.id == selected?.id } ?: places.firstOrNull()
    }

    private fun readRainForecasts(prefs: Preferences): Map<String, RainForecast> {
        prefs[KEY_RAIN_FORECASTS]?.let { raw ->
            return runCatching { json.decodeFromString<Map<String, RainForecast>>(raw) }
                .getOrDefault(emptyMap())
        }
        // До списка городов прогноз относился только к единственному сохранённому месту.
        val place = readSelectedWeatherPlace(prefs) ?: return emptyMap()
        val legacy = prefs[KEY_RAIN_FORECAST]?.let { raw ->
            runCatching { json.decodeFromString<RainForecast>(raw) }.getOrNull()
        } ?: return emptyMap()
        return mapOf(place.id to legacy)
    }

    private fun saveWeather(
        prefs: MutablePreferences,
        places: List<WeatherPlace>,
        selected: WeatherPlace?,
        forecasts: Map<String, RainForecast>,
    ) {
        prefs[KEY_WEATHER_PLACES] = json.encodeToString(places)
        if (selected == null) prefs.remove(KEY_WEATHER_PLACE)
        else prefs[KEY_WEATHER_PLACE] = json.encodeToString(selected)
        prefs[KEY_RAIN_FORECASTS] = json.encodeToString(forecasts)
        prefs.remove(KEY_RAIN_FORECAST)
    }

    suspend fun currentIntervalMinutes(): Int = refreshIntervalMinutes.first()

    suspend fun setOrganizationUuid(uuid: String?) {
        dataStore.edit { prefs ->
            if (uuid == null) prefs.remove(KEY_ORG_UUID) else prefs[KEY_ORG_UUID] = uuid
        }
    }

    suspend fun setOrganizations(list: List<Organization>) {
        dataStore.edit { prefs ->
            prefs[KEY_ORGS] = json.encodeToString(ListSerializer(Organization.serializer()), list)
        }
    }

    suspend fun setRefreshIntervalMinutes(minutes: Int) {
        val safe = if (minutes in ALLOWED_INTERVALS) minutes else DEFAULT_INTERVAL_MINUTES
        dataStore.edit { it[KEY_INTERVAL] = safe }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
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

        private val KEY_WIDGET_PROVIDER = stringPreferencesKey("widget_provider")
        private val KEY_PROVIDER = stringPreferencesKey("usage_provider")
        private val KEY_ORG_UUID = stringPreferencesKey("organization_uuid")
        private val KEY_ORGS = stringPreferencesKey("organizations")
        private val KEY_INTERVAL = intPreferencesKey("refresh_interval_minutes")
        private val KEY_GLYPH_ENABLED = booleanPreferencesKey("glyph_enabled")
        private val KEY_GLYPH_MODE = stringPreferencesKey("glyph_render_mode")
        private val KEY_STRIP_MODE = stringPreferencesKey("glyph_strip_mode")
        private val KEY_CHANNEL_A_MODE = stringPreferencesKey("glyph_channel_a_mode")
        private val KEY_CHANNEL_B_MODE = stringPreferencesKey("glyph_channel_b_mode")
        // Старые флаги читаем только при миграции; явный выбор канала имеет приоритет.
        private val KEY_IDLE_ENABLED = booleanPreferencesKey("glyph_idle_enabled")
        private val KEY_IDLE_MINUTES = intPreferencesKey("glyph_idle_minutes")
        private val KEY_RAIN_ENABLED = booleanPreferencesKey("glyph_rain_enabled")
        private val KEY_WEATHER_PLACE = stringPreferencesKey("weather_place")
        private val KEY_WEATHER_PLACES = stringPreferencesKey("weather_places")
        private val KEY_RAIN_FORECASTS = stringPreferencesKey("rain_forecasts_by_place")
        private val KEY_RAIN_FORECAST = stringPreferencesKey("rain_forecast")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
