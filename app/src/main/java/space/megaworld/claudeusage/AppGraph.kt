package space.megaworld.claudeusage

import android.content.Context
import space.megaworld.claudeusage.data.ApiClient
import space.megaworld.claudeusage.data.CredentialStore
import space.megaworld.claudeusage.data.SettingsStore
import space.megaworld.claudeusage.data.UsageRepository
import space.megaworld.claudeusage.data.*
import kotlinx.coroutines.*
import androidx.glance.appwidget.updateAll
import space.megaworld.claudeusage.widget.UsageWidget
import space.megaworld.claudeusage.worker.UsageRefreshWorker

/**
 * Ручной DI: зависимостей мало, полноценный фреймворк не нужен.
 * Инициализируется лениво, потому что точкой входа может быть и Activity,
 * и воркер, и виджет.
 */
class AppGraph private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    val credentialStore: CredentialStore by lazy { CredentialStore(appContext) }
    val openAiCredentialStore: CredentialStore by lazy { CredentialStore(appContext, "openai") }
    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }
    val apiClient: ApiClient by lazy { ApiClient() }

    val usageRepository: UsageRepository by lazy {
        UsageRepository(appContext, credentialStore, settingsStore, apiClient, openAiCredentialStore = openAiCredentialStore)
    }

    private val loginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val openAiLoginCoordinator: OpenAiLoginCoordinator by lazy {
        OpenAiLoginCoordinator(loginScope, OpenAiAuthClient(), openAiCredentialStore.loginVault(), connect = { credentials ->
            when (usageRepository.connectOpenAi(credentials)) {
                RefreshResult.SessionExpired, RefreshResult.NotAuthorized ->
                    throw OpenAiAuthException("OpenAI отклонил сессию. Начните вход заново.", terminal = true)
                is RefreshResult.Success, is RefreshResult.Failure -> {
                    // The account is stored already; a widget refresh must not consume its grant again.
                    runCatching {
                        UsageRefreshWorker.ensureScheduled(appContext, settingsStore.currentIntervalMinutes())
                        UsageWidget().updateAll(appContext)
                    }
                }
            }
        })
    }

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}
