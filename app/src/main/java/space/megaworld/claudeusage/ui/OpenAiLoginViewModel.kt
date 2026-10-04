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

/** Pending login survives rotation and continues while the system browser is open. */
class OpenAiLoginViewModel(application: Application) : AndroidViewModel(application) {
    internal var busy by mutableStateOf(false)
        private set
    internal var error by mutableStateOf<String?>(null)
        private set
    internal var networkStatus by mutableStateOf<String?>(null)
        private set
    internal var authorization by mutableStateOf<DeviceAuthorization?>(null)
        private set
    internal var connected by mutableStateOf(false)
        private set
    private var operation: Job? = null
    private val graph = AppGraph.get(application)
    private val auth = OpenAiAuthClient()

    internal fun startLogin() = runOperation {
        val session = auth.startDeviceLogin()
        authorization = session
        while (true) {
            delay(session.intervalMillis)
            when (val result = auth.pollDeviceLogin(session)) {
                DevicePoll.Pending -> Unit
                is DevicePoll.Authorized -> {
                    ensureActive()
                    when (graph.usageRepository.connectOpenAi(result.credentials)) {
                        RefreshResult.SessionExpired, RefreshResult.NotAuthorized ->
                            throw OpenAiAuthException("OpenAI отклонил сессию. Начните вход заново.")
                        is RefreshResult.Failure, is RefreshResult.Success -> completeLogin()
                    }
                    break
                }
            }
        }
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
                error = "Сессия в файле истекла. Войдите через ChatGPT или импортируйте свежий auth.json."
            is RefreshResult.Failure -> error = result.message
        }
    }

    internal fun checkNetwork() = runOperation { networkStatus = OpenAiUsageClient().checkConnectivity() }

    internal fun cancelLogin() {
        operation?.cancel()
        operation = null
        authorization = null
        busy = false
        error = null
    }

    internal fun browserUnavailable() {
        error = "Не удалось открыть браузер. Откройте auth.openai.com/codex/device вручную и введите код."
    }

    private suspend fun completeLogin() {
        val context = getApplication<Application>()
        UsageRefreshWorker.ensureScheduled(context, graph.settingsStore.currentIntervalMinutes())
        UsageWidget().updateAll(context)
        connected = true
    }

    private fun runOperation(block: suspend CoroutineScope.() -> Unit) {
        if (busy) return
        busy = true
        error = null
        operation = viewModelScope.launch {
            try { block()
            } catch (e: CancellationException) { throw e
            } catch (e: OpenAiAuthException) { error = e.message
            } catch (e: OpenAiImportException) { error = e.message
            } catch (e: IOException) { error = "Нет связи с OpenAI. Проверьте интернет и VPN для tinyGlyph, затем повторите вход."
            } catch (e: Exception) { error = "Не удалось подключить GPT. Попробуйте ещё раз."
            } finally {
                if (currentCoroutineContext().isActive) {
                    authorization = null
                    busy = false
                }
            }
        }
    }
}
