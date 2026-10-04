package space.megaworld.claudeusage.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.*
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.data.*
import space.megaworld.claudeusage.widget.UsageWidget
import space.megaworld.claudeusage.worker.UsageRefreshWorker
import java.io.IOException

/** Observes the process-wide coordinator; leaving this Activity never cancels login. */
class OpenAiLoginViewModel internal constructor(application: Application,
    internal val coordinator: OpenAiLoginCoordinator) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, AppGraph.get(application).openAiLoginCoordinator)

    private var loginState by mutableStateOf(coordinator.state.value)
    private var localBusy by mutableStateOf(false)
    private var localError by mutableStateOf<String?>(null)
    private var importConnected by mutableStateOf(false)
    internal var networkStatus by mutableStateOf<String?>(null)
        private set
    internal var browserLaunchRequested by mutableStateOf(false)
        private set
    internal val busy get() = localBusy || loginState.busy
    internal val error get() = localError ?: loginState.error
    internal val authorization get() = loginState.pending?.device
    internal val browserAuthorization get() = loginState.pending?.browser
    internal val connected get() = importConnected || loginState.connected
    private val graph = AppGraph.get(application)

    init { viewModelScope.launch { coordinator.state.collect { loginState = it } } }

    internal fun startLogin() {
        if (busy) return
        localError = null
        browserLaunchRequested = true
        coordinator.startBrowser()
    }

    internal fun startDeviceLogin() {
        if (busy) return
        localError = null
        coordinator.startDevice()
    }

    internal fun consumeBrowserLaunch(): String? {
        val browser = browserAuthorization ?: return null
        if (!browserLaunchRequested) return null
        browserLaunchRequested = false
        return browser.authorizationUrl
    }

    internal fun acknowledgeConnection() {
        importConnected = false
        coordinator.acknowledgeConnection()
    }

    internal fun importFile(uri: Uri) = runOperation {
        val raw = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > 128 * 1024) throw OpenAiImportException("Файл слишком большой. Выберите auth.json из Codex.")
                    out.write(buffer, 0, count)
                }
                out.toString(Charsets.UTF_8.name())
            } ?: throw OpenAiImportException("Не удалось прочитать файл. Выберите его заново.")
        }
        when (val result = graph.usageRepository.importOpenAi(raw)) {
            is RefreshResult.Success -> completeLogin()
            RefreshResult.SessionExpired, RefreshResult.NotAuthorized ->
                localError = "Сессия в файле истекла. Войдите через ChatGPT или импортируйте свежий auth.json."
            is RefreshResult.Failure -> localError = result.message
        }
    }

    internal fun checkNetwork() = runOperation { networkStatus = OpenAiUsageClient().checkConnectivity() }

    internal fun cancelLogin() {
        browserLaunchRequested = false
        localError = null
        coordinator.cancel()
    }

    internal fun browserUnavailable() {
        localError = "Не удалось открыть браузер. Попробуйте открыть страницу входа ещё раз или выберите вход по коду."
    }

    private suspend fun completeLogin() {
        val context = getApplication<Application>()
        UsageRefreshWorker.ensureScheduled(context, graph.settingsStore.currentIntervalMinutes())
        UsageWidget().updateAll(context)
        importConnected = true
    }

    private fun runOperation(block: suspend CoroutineScope.() -> Unit) {
        if (busy) return
        localBusy = true
        localError = null
        viewModelScope.launch {
            try { block()
            } catch (e: CancellationException) { throw e
            } catch (e: OpenAiAuthException) { localError = e.message
            } catch (e: OpenAiImportException) { localError = e.message
            } catch (e: IOException) { localError = "Нет связи с OpenAI. Проверьте интернет и VPN для tinyGlyph, затем повторите вход."
            } catch (e: Exception) { localError = "Не удалось подключить GPT. Попробуйте ещё раз."
            } finally {
                if (currentCoroutineContext().isActive) {
                    localBusy = false
                }
            }
        }
    }
}
