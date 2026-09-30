package space.megaworld.claudeusage.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Единственный экземпляр DataStore на процесс: и виджет, и воркер, и UI живут в одном
 * процессе, а повторное создание DataStore с тем же именем падает в рантайме.
 */
internal val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "claude_usage")
