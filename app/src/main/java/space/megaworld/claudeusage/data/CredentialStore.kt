package space.megaworld.claudeusage.data

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Cookie-заголовок и User-Agent, снятые с WebView после логина.
 *
 * Хранится весь набор cookie домена claude.ai, а не только `sessionKey`:
 * без `cf_clearance` и совпадающего UA Cloudflare отдаёт челлендж вместо JSON.
 */
@Serializable
data class Credentials(
    val cookieHeader: String,
    val userAgent: String,
    val savedAtMillis: Long,
)

/**
 * Секреты в DataStore, зашифрованные AEAD-ключом из Android Keystore (Tink).
 * EncryptedSharedPreferences не используется — он deprecated.
 */
class CredentialStore(private val context: Context) {

    private val aeadMutex = Mutex()
    @Volatile private var cachedAead: Aead? = null

    /** Есть ли сохранённая сессия. Дешёвая проверка — без расшифровки. */
    val hasCredentials: Flow<Boolean> =
        context.appDataStore.data.map { it[KEY_CREDENTIALS] != null }

    suspend fun load(): Credentials? = withContext(Dispatchers.IO) {
        val encoded = context.appDataStore.data.first()[KEY_CREDENTIALS] ?: return@withContext null
        runCatching {
            val plain = aead().decrypt(Base64.decode(encoded, Base64.NO_WRAP), ASSOCIATED_DATA)
            json.decodeFromString<Credentials>(plain.decodeToString())
        }.getOrNull()
    }

    suspend fun save(credentials: Credentials) = withContext(Dispatchers.IO) {
        val cipher = aead().encrypt(
            json.encodeToString(Credentials.serializer(), credentials).toByteArray(),
            ASSOCIATED_DATA,
        )
        val encoded = Base64.encodeToString(cipher, Base64.NO_WRAP)
        context.appDataStore.edit { it[KEY_CREDENTIALS] = encoded }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        context.appDataStore.edit { it.remove(KEY_CREDENTIALS) }
        Unit
    }

    private suspend fun aead(): Aead {
        cachedAead?.let { return it }
        return aeadMutex.withLock {
            cachedAead ?: createAead().also { cachedAead = it }
        }
    }

    private fun createAead(): Aead {
        AeadConfig.register()
        val handle = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        return handle.getPrimitive(Aead::class.java)
    }

    private companion object {
        val KEY_CREDENTIALS = stringPreferencesKey("credentials_aead")
        const val KEYSET_NAME = "claude_usage_keyset"
        const val KEYSET_PREF_FILE = "claude_usage_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://claude_usage_master_key"
        val ASSOCIATED_DATA = "claude_usage_credentials".toByteArray()
        val json = Json { ignoreUnknownKeys = true }
    }
}
