package space.megaworld.claudeusage.ui

import android.app.Application
import android.webkit.CookieManager
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.glyph.UsageForegroundService
import space.megaworld.claudeusage.data.AmbientChannel
import space.megaworld.claudeusage.data.GlyphChannelMode
import space.megaworld.claudeusage.data.GlyphStripMode
import space.megaworld.claudeusage.data.ApiResult
import space.megaworld.claudeusage.data.GlyphRenderMode
import space.megaworld.claudeusage.data.RefreshResult
import space.megaworld.claudeusage.data.UsageProvider
import space.megaworld.claudeusage.data.UsageState
import space.megaworld.claudeusage.data.WeatherPlace
import space.megaworld.claudeusage.widget.UsageWidget
import space.megaworld.claudeusage.worker.UsageRefreshWorker

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = AppGraph.get(application)

    /**
     * null — DataStore ещё не прочитан. Без этого экран на первом кадре мигал бы
     * состоянием «не авторизован».
     */
    val state: StateFlow<UsageState?> = graph.usageRepository.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() = runBusy {
        when (val result = graph.usageRepository.refresh()) {
            is RefreshResult.Success -> _message.value = null
            RefreshResult.NotAuthorized -> _message.value = "Подключите выбранный аккаунт"
            RefreshResult.SessionExpired -> _message.value = "Сессия истекла, войдите заново"
            is RefreshResult.Failure -> _message.value = result.message
        }
        UsageWidget().updateAll(getApplication())
    }

    fun reloadOrganizations() = runBusy {
        when (val result = graph.usageRepository.loadOrganizations()) {
            is ApiResult.Success -> _message.value = null
            ApiResult.Unauthorized -> _message.value = "Сессия истекла, войдите заново"
            is ApiResult.Failure -> _message.value = result.message
        }
    }

    fun selectOrganization(uuid: String) = runBusy {
        graph.usageRepository.selectOrganization(uuid)
        graph.usageRepository.refresh()
        UsageWidget().updateAll(getApplication())
    }

    fun selectProvider(provider: UsageProvider) = runBusy {
        graph.usageRepository.selectProvider(provider)
        UsageRefreshWorker.ensureScheduled(getApplication(), graph.settingsStore.currentIntervalMinutes())
        UsageWidget().updateAll(getApplication())
        _message.value = null
    }

    fun setWidgetProvider(provider: UsageProvider) = runBusy {
        graph.usageRepository.setWidgetProvider(provider)
        UsageWidget().updateAll(getApplication())
        UsageRefreshWorker.ensureScheduled(getApplication(), graph.settingsStore.currentIntervalMinutes())
        UsageRefreshWorker.refreshNow(getApplication())
    }

    fun setGlyphEnabled(enabled: Boolean) = runBusy {
        graph.usageRepository.setGlyphEnabled(enabled)
    }

    fun setGlyphRenderMode(mode: GlyphRenderMode) = runBusy {
        graph.usageRepository.setGlyphRenderMode(mode)
    }

    fun testChannel(channel: AmbientChannel) = runBusy {
        UsageForegroundService.testChannel(getApplication(), channel)
        _message.value = "Проверка $channel запущена на 5 секунд. Посмотрите на подсветку сзади."
    }

    fun setChannelMode(channel: AmbientChannel, mode: GlyphChannelMode) = runBusy {
        graph.usageRepository.setChannelMode(channel, mode)
    }

    fun setStripMode(mode: GlyphStripMode) = runBusy {
        graph.usageRepository.setStripMode(mode)
    }

    fun setIdleThresholdMinutes(minutes: Int) = runBusy {
        graph.usageRepository.setIdleThresholdMinutes(minutes)
    }

    /** Геокодер отвечает не всегда — про неудачу пользователю надо сказать. */
    fun addWeatherPlace(query: String) = runBusy {
        when (val result = graph.usageRepository.addWeatherPlace(query)) {
            is ApiResult.Success -> _message.value = "Выбран город: " + result.value.name
            ApiResult.Unauthorized -> _message.value = "Сервис погоды отказал"
            is ApiResult.Failure -> _message.value = result.message
        }
    }

    fun selectWeatherPlace(place: WeatherPlace) = runBusy {
        _message.value = null
        graph.usageRepository.selectWeatherPlace(place)
    }

    fun removeWeatherPlace(place: WeatherPlace) = runBusy {
        _message.value = null
        graph.usageRepository.removeWeatherPlace(place)
    }

    fun setRefreshInterval(minutes: Int) = runBusy {
        graph.usageRepository.setRefreshIntervalMinutes(minutes)
        UsageRefreshWorker.reschedule(getApplication(), minutes)
    }

    /** Выход только из просматриваемого аккаунта; другой источник виджета продолжает обновляться. */
    fun logout() = runBusy {
        graph.usageRepository.logout()
        UsageRefreshWorker.ensureScheduled(getApplication(), graph.settingsStore.currentIntervalMinutes())
        clearWebViewCookies()
        UsageWidget().updateAll(getApplication())
        _message.value = null
    }

    fun dismissMessage() {
        _message.value = null
    }

    private fun clearWebViewCookies() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
    }

    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}
