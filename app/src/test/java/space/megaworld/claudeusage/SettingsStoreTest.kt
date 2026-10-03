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
import space.megaworld.claudeusage.data.SettingsStore
import space.megaworld.claudeusage.data.UsageProvider

class SettingsStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

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
        withStore(file) { settings, _ ->
            settings.setWidgetProvider(UsageProvider.CODEX)
            settings.setProvider(UsageProvider.CLAUDE)
            settings.setGlyphEnabled(true)
            settings.setChannelMode(AmbientChannel.B, GlyphChannelMode.IDLE)
            settings.setChannelMode(AmbientChannel.A, GlyphChannelMode.RAIN)
            settings.setIdleThresholdMinutes(60)
        }
        withStore(file) { settings, _ ->
            assertEquals(UsageProvider.CODEX, settings.widgetProvider.first())
            assertEquals(UsageProvider.CLAUDE, settings.provider.first())
            assertTrue(settings.glyphEnabled.first())
            assertTrue(settings.ambient.first().idleEnabled)
            assertEquals(60, settings.ambient.first().idleThresholdMinutes)
            assertEquals(GlyphChannelMode.RAIN, settings.ambient.first().channelA)
            assertEquals(GlyphChannelMode.IDLE, settings.ambient.first().channelB)
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
