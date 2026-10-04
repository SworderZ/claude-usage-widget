package space.megaworld.claudeusage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.megaworld.claudeusage.data.AmbientChannel
import space.megaworld.claudeusage.data.GlyphChannelMode
import space.megaworld.claudeusage.data.GlyphStripMode
import space.megaworld.claudeusage.data.AmbientState
import space.megaworld.claudeusage.data.RainForecast
import space.megaworld.claudeusage.data.WeatherPlace
import space.megaworld.claudeusage.data.SettingsStore
import space.megaworld.claudeusage.data.UsageProvider

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `two widgets retain independent providers when the app and default change`() = runTest {
        withStore { settings, _ ->
            settings.setWidgetProvider(11, UsageProvider.CLAUDE)
            settings.setWidgetProvider(22, UsageProvider.CODEX)
            settings.setProvider(UsageProvider.CODEX)
            settings.setWidgetProvider(UsageProvider.CODEX)
            assertEquals(UsageProvider.CLAUDE, settings.widgetProvider(11).first())
            assertEquals(UsageProvider.CODEX, settings.widgetProvider(22).first())
            settings.setWidgetProvider(22, UsageProvider.CLAUDE)
            assertEquals(UsageProvider.CLAUDE, settings.widgetProvider(11).first())
            assertEquals(UsageProvider.CLAUDE, settings.widgetProvider(22).first())
            assertEquals(UsageProvider.CODEX, settings.provider.first())
            assertEquals(UsageProvider.CODEX, settings.widgetProvider.first())
        }
    }

    @Test
    fun `legacy widgets keep the shared GPT choice after migration and a new default`() = runTest {
        withStore { settings, dataStore ->
            dataStore.edit { it[stringPreferencesKey("usage_provider")] = UsageProvider.CODEX.name }
            settings.initializeWidgetProviders(intArrayOf(11, 22))
            settings.setProvider(UsageProvider.CLAUDE)
            settings.setWidgetProvider(UsageProvider.CLAUDE)
            settings.initializeWidgetProviders(intArrayOf(11, 22, 33))
            assertEquals(UsageProvider.CODEX, settings.widgetProvider(11).first())
            assertEquals(UsageProvider.CODEX, settings.widgetProvider(22).first())
            assertEquals(UsageProvider.CLAUDE, settings.widgetProvider(33).first())
        }
    }

    @Test
    fun `widget choices survive process restart and clearing Claude settings`() = runTest {
        val file = File(temporaryFolder.root, "widgets.preferences_pb")
        withStore(file) { settings, _ ->
            settings.setWidgetProvider(11, UsageProvider.CLAUDE)
            settings.setWidgetProvider(22, UsageProvider.CODEX)
            settings.clear()
        }
        withStore(file) { settings, _ ->
            assertEquals(UsageProvider.CLAUDE, settings.widgetProvider(11).first())
            assertEquals(UsageProvider.CODEX, settings.widgetProvider(22).first())
        }
    }

    @Test
    fun `background refresh includes widget sources once and forgets deleted widgets`() = runTest {
        withStore { settings, _ ->
            settings.setWidgetProvider(11, UsageProvider.CODEX)
            settings.setWidgetProvider(22, UsageProvider.CODEX)
            assertEquals(listOf(UsageProvider.CLAUDE, UsageProvider.CODEX), settings.displayedProviders())
            settings.removeWidgetProvider(11)
            assertEquals(listOf(UsageProvider.CLAUDE, UsageProvider.CODEX), settings.displayedProviders())
            settings.removeWidgetProvider(22)
            assertEquals(listOf(UsageProvider.CLAUDE), settings.displayedProviders())
            settings.setWidgetProvider(22, UsageProvider.CLAUDE)
            assertEquals(UsageProvider.CLAUDE, settings.widgetProvider(22).first())
        }
    }

    @Test
    fun `invalid widget data falls back without replacing another explicit choice`() = runTest {
        withStore { settings, dataStore ->
            settings.setWidgetProvider(UsageProvider.CODEX)
            settings.setWidgetProvider(11, UsageProvider.CLAUDE)
            dataStore.edit { it[stringPreferencesKey("widget_provider_22")] = "invalid" }
            settings.initializeWidgetProviders(intArrayOf(0, -1, 11, 22))
            assertEquals(UsageProvider.CLAUDE, settings.widgetProvider(11).first())
            assertEquals(UsageProvider.CODEX, settings.widgetProvider(22).first())
            assertTrue(runCatching { settings.setWidgetProvider(0, UsageProvider.CLAUDE) }.isFailure)
            assertTrue(runCatching { settings.setWidgetProvider(-1, UsageProvider.CLAUDE) }.isFailure)
        }
    }

    @Test
    fun `browsing another provider does not change the default widget`() = runTest {
        withStore { settings, _ ->
            assertEquals(UsageProvider.CLAUDE, settings.widgetProvider.first())
            settings.setProvider(UsageProvider.CODEX)
            assertEquals(UsageProvider.CODEX, settings.provider.first())
            assertEquals(UsageProvider.CLAUDE, settings.widgetProvider.first())
        }
    }

    @Test
    fun `upgrade preserves legacy GPT widget when browsing Claude`() = runTest {
        withStore { settings, dataStore ->
            dataStore.edit { it[stringPreferencesKey("usage_provider")] = UsageProvider.CODEX.name }
            assertEquals(UsageProvider.CODEX, settings.widgetProvider.first())
            settings.setProvider(UsageProvider.CLAUDE)
            assertEquals(UsageProvider.CLAUDE, settings.provider.first())
            assertEquals(UsageProvider.CODEX, settings.widgetProvider.first())
        }
    }

    @Test
    fun `explicit widget choice remains independent in both directions`() = runTest {
        withStore { settings, _ ->
            settings.setWidgetProvider(UsageProvider.CODEX)
            settings.setProvider(UsageProvider.CLAUDE)
            assertEquals(UsageProvider.CODEX, settings.widgetProvider.first())
            settings.setWidgetProvider(UsageProvider.CLAUDE)
            settings.setProvider(UsageProvider.CODEX)
            assertEquals(UsageProvider.CLAUDE, settings.widgetProvider.first())
        }
    }

    @Test
    fun `widget and Glyph choices survive reopening the settings file`() = runTest {
        val file = File(temporaryFolder.root, "saved.preferences_pb")
        val place = WeatherPlace("Москва", 55.75, 37.62)
        val second = WeatherPlace("Казань", 55.79, 49.12)
        val forecast = RainForecast(70, 1_000L)
        val secondForecast = RainForecast(20, 2_000L)
        withStore(file) { settings, _ ->
            settings.setWidgetProvider(UsageProvider.CODEX)
            settings.setProvider(UsageProvider.CLAUDE)
            settings.setGlyphEnabled(true)
            settings.setChannelMode(AmbientChannel.B, GlyphChannelMode.IDLE)
            settings.setChannelMode(AmbientChannel.A, GlyphChannelMode.RAIN)
            settings.setIdleThresholdMinutes(60)
            settings.setStripMode(GlyphStripMode.RAIN)
            settings.addWeatherPlace(place)
            settings.setRainForecast(place, forecast)
            settings.addWeatherPlace(second)
            settings.setRainForecast(second, secondForecast)
            settings.selectWeatherPlace(place)
        }
        withStore(file) { settings, _ ->
            assertEquals(UsageProvider.CODEX, settings.widgetProvider.first())
            assertEquals(UsageProvider.CLAUDE, settings.provider.first())
            assertTrue(settings.glyphEnabled.first())
            assertTrue(settings.ambient.first().idleEnabled)
            assertEquals(60, settings.ambient.first().idleThresholdMinutes)
            assertEquals(GlyphChannelMode.RAIN, settings.ambient.first().channelA)
            assertEquals(GlyphChannelMode.IDLE, settings.ambient.first().channelB)
            assertEquals(GlyphStripMode.RAIN, settings.ambient.first().stripMode)
            assertEquals(place, settings.ambient.first().place)
            assertEquals(listOf(place, second), settings.ambient.first().places)
            assertEquals(forecast, settings.currentRainForecast())
            settings.selectWeatherPlace(second)
            assertEquals(secondForecast, settings.currentRainForecast())
        }
    }

    @Test
    fun `clearing Claude settings preserves widget and ambient choices`() = runTest {
        withStore { settings, _ ->
            settings.setWidgetProvider(UsageProvider.CODEX)
            settings.setChannelMode(AmbientChannel.B, GlyphChannelMode.IDLE)
            settings.setOrganizationUuid("old-org")
            settings.clear()
            assertEquals(null, settings.organizationUuid.first())
            assertEquals(UsageProvider.CODEX, settings.widgetProvider.first())
            assertTrue(settings.ambient.first().idleEnabled)
        }
    }

    @Test
    fun `legacy enabled functions move to the requested channels`() = runTest {
        withStore { settings, dataStore ->
            dataStore.edit {
                it[booleanPreferencesKey("glyph_idle_enabled")] = true
                it[booleanPreferencesKey("glyph_rain_enabled")] = true
            }
            val ambient = settings.ambient.first()
            assertEquals(GlyphChannelMode.RAIN, ambient.channelA)
            assertEquals(GlyphChannelMode.IDLE, ambient.channelB)
            assertEquals(GlyphStripMode.USAGE, ambient.stripMode)
        }
    }

    @Test
    fun `explicit off overrides legacy enabled flag without changing the other channel`() = runTest {
        withStore { settings, dataStore ->
            dataStore.edit {
                it[booleanPreferencesKey("glyph_idle_enabled")] = true
                it[booleanPreferencesKey("glyph_rain_enabled")] = true
            }
            settings.setChannelMode(AmbientChannel.A, GlyphChannelMode.OFF)
            assertEquals(GlyphChannelMode.OFF, settings.ambient.first().channelA)
            assertEquals(GlyphChannelMode.IDLE, settings.ambient.first().channelB)
        }
    }

    @Test
    fun `both channels can use idle and disabling one keeps the timer active`() = runTest {
        withStore { settings, _ ->
            settings.setChannelMode(AmbientChannel.A, GlyphChannelMode.IDLE)
            settings.setChannelMode(AmbientChannel.B, GlyphChannelMode.IDLE)
            settings.setChannelMode(AmbientChannel.A, GlyphChannelMode.OFF)
            assertTrue(settings.ambient.first().idleEnabled)
            settings.setChannelMode(AmbientChannel.B, GlyphChannelMode.OFF)
            assertEquals(false, settings.ambient.first().idleEnabled)
        }
    }

    @Test
    fun `upgrade preserves the old city and its forecast when another is added`() = runTest {
        withStore { settings, dataStore ->
            val old = WeatherPlace("Москва", 55.75, 37.62)
            val next = WeatherPlace("Казань", 55.79, 49.12)
            val forecast = RainForecast(70, 1_000L)
            dataStore.edit {
                it[stringPreferencesKey("weather_place")] = Json.encodeToString(old)
                it[stringPreferencesKey("rain_forecast")] = Json.encodeToString(forecast)
            }
            assertEquals(listOf(old), settings.currentAmbient().places)
            assertEquals(old, settings.currentAmbient().place)
            assertEquals(forecast, settings.currentRainForecast())
            settings.addWeatherPlace(next)
            assertEquals(listOf(old, next), settings.currentAmbient().places)
            assertEquals(next, settings.currentAmbient().place)
            assertEquals(null, settings.currentRainForecast())
            settings.selectWeatherPlace(old)
            assertEquals(forecast, settings.currentRainForecast())
        }
    }

    @Test
    fun `switching cities emits each city with only its own forecast`() = runTest {
        withStore { settings, _ ->
            val first = WeatherPlace("Москва", 55.75, 37.62)
            val second = WeatherPlace("Казань", 55.79, 49.12)
            settings.addWeatherPlace(first)
            settings.setRainForecast(first, RainForecast(80, 1_000L))
            val observations = mutableListOf<AmbientState>()
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                settings.ambientState.collect { observations += it }
            }
            try {
                settings.addWeatherPlace(second)
                assertEquals(second, settings.currentAmbient().place)
                assertEquals(null, settings.currentRainForecast())
                // Ответ на старый запрос после переключения не должен стать прогнозом нового города.
                settings.setRainForecast(first, RainForecast(90, 2_000L))
                assertEquals(null, settings.currentRainForecast())
                settings.setRainForecast(second, RainForecast(0, 3_000L))
                settings.selectWeatherPlace(first)
                assertEquals(90, settings.currentRainForecast()?.probabilityPercent)
                settings.selectWeatherPlace(second)
                assertEquals(0, settings.currentRainForecast()?.probabilityPercent)
                assertTrue(observations.any { it.settings.place == first && it.forecast?.probabilityPercent == 90 })
                assertTrue(observations.any { it.settings.place == second && it.forecast?.probabilityPercent == 0 })
                observations.forEach { state ->
                    if (state.settings.place == first) {
                        assertTrue(state.forecast?.probabilityPercent in listOf(80, 90))
                    } else {
                        assertTrue(state.forecast?.probabilityPercent in listOf(null, 0))
                    }
                }
            } finally {
                observer.cancelAndJoin()
            }
        }
    }

    @Test
    fun `duplicate coordinates select the saved city while same names can refer to different cities`() = runTest {
        withStore { settings, _ ->
            val first = WeatherPlace("Одинаковое название", 55.75, 37.62)
            val second = WeatherPlace("Одинаковое название", 55.79, 49.12)
            settings.addWeatherPlace(first)
            settings.setRainForecast(first, RainForecast(70, 1_000L))
            settings.addWeatherPlace(second)
            settings.addWeatherPlace(first.copy(name = "Другое название тех же координат"))
            assertEquals(listOf(first, second), settings.currentAmbient().places)
            assertEquals(first, settings.currentAmbient().place)
            assertEquals(70, settings.currentRainForecast()?.probabilityPercent)
        }
    }

    @Test
    fun `removing cities preserves the selected forecast then falls back and finally clears weather`() = runTest {
        withStore { settings, _ ->
            val first = WeatherPlace("Москва", 55.75, 37.62)
            val second = WeatherPlace("Казань", 55.79, 49.12)
            val third = WeatherPlace("Самара", 53.2, 50.15)
            settings.addWeatherPlace(first)
            settings.setRainForecast(first, RainForecast(80, 1_000L))
            settings.addWeatherPlace(second)
            settings.setRainForecast(second, RainForecast(20, 2_000L))
            settings.addWeatherPlace(third)
            settings.selectWeatherPlace(second)
            settings.removeWeatherPlace(third)
            assertEquals(second, settings.currentAmbient().place)
            assertEquals(20, settings.currentRainForecast()?.probabilityPercent)
            settings.removeWeatherPlace(second)
            assertEquals(first, settings.currentAmbient().place)
            assertEquals(80, settings.currentRainForecast()?.probabilityPercent)
            settings.removeWeatherPlace(first)
            assertTrue(settings.currentAmbient().places.isEmpty())
            assertEquals(null, settings.currentAmbient().place)
            assertEquals(null, settings.currentRainForecast())
            settings.addWeatherPlace(first)
            assertEquals(null, settings.currentRainForecast())
        }
    }

    @Test
    fun `late forecast or stale selection cannot bring a deleted city back`() = runTest {
        withStore { settings, _ ->
            val first = WeatherPlace("Москва", 55.75, 37.62)
            val second = WeatherPlace("Казань", 55.79, 49.12)
            settings.addWeatherPlace(first)
            settings.addWeatherPlace(second)
            settings.setRainForecast(second, RainForecast(20, 2_000L))
            settings.removeWeatherPlace(first)
            settings.setRainForecast(first, RainForecast(90, 3_000L))
            settings.selectWeatherPlace(first)
            assertEquals(listOf(second), settings.currentAmbient().places)
            assertEquals(second, settings.currentAmbient().place)
            assertEquals(20, settings.currentRainForecast()?.probabilityPercent)
        }
    }

    private suspend fun TestScope.withStore(
        file: File = File(temporaryFolder.newFolder(), "settings.preferences_pb"),
        block: suspend (SettingsStore, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit,
    ) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
            produceFile = { file },
        )
        try {
            block(SettingsStore(store), store)
        } finally {
            job.cancelAndJoin()
        }
    }
}
