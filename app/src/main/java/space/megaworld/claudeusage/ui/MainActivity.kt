package space.megaworld.claudeusage.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.glyph.UsageForegroundService

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* см. ниже */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        followGlyphSetting()
        setContent {
            ClaudeUsageTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        onOpenLogin = {
                            startActivity(Intent(this, LoginActivity::class.java))
                        },
                        onOpenManualLogin = {
                            startActivity(Intent(this, ManualLoginActivity::class.java))
                        },
                    )
                }
            }
        }
    }

    /**
     * Полосу ведёт foreground service, а не Activity: иначе она гасла бы при сворачивании.
     * Здесь только включаем и выключаем его вслед за настройкой — владелец сессии Glyph
     * должен быть один.
     *
     * Отказ в разрешении на уведомления не критичен: сервис всё равно стартует, просто
     * уведомление не будет видно в шторке.
     */
    private fun followGlyphSetting() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppGraph.get(this@MainActivity).usageRepository.state
                    .map { UsageForegroundService.isNeeded(it.glyphEnabled, it.refreshIntervalMinutes) }
                    .distinctUntilChanged()
                    .collect { needed ->
                        if (needed) {
                            ensureNotificationPermission()
                            UsageForegroundService.start(this@MainActivity)
                        } else {
                            UsageForegroundService.stop(this@MainActivity)
                        }
                    }
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private enum class Screen { MAIN, SETTINGS }

@Composable
private fun AppRoot(onOpenLogin: () -> Unit, onOpenManualLogin: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Экранов два, NavHost ради них тянуть не стоит.
    var screen by remember { mutableStateOf(Screen.MAIN) }
    val viewModel: MainViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val loaded = state ?: run {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    when (screen) {
        Screen.MAIN -> MainScreen(
            state = loaded,
            busy = busy,
            message = message,
            onRefresh = viewModel::refresh,
            onLogin = onOpenLogin,
            onManualLogin = onOpenManualLogin,
            onLogout = viewModel::logout,
            onOpenSettings = { screen = Screen.SETTINGS },
            onDismissMessage = viewModel::dismissMessage,
            onSelectProvider = viewModel::selectProvider,
            onOpenAiLogin = { context.startActivity(Intent(context, OpenAiLoginActivity::class.java)) },
        )
        Screen.SETTINGS -> SettingsScreen(
            state = loaded,
            busy = busy,
            onBack = { screen = Screen.MAIN },
            onSelectOrganization = viewModel::selectOrganization,
            onSelectInterval = viewModel::setRefreshInterval,
            onReloadOrganizations = viewModel::reloadOrganizations,
            onToggleGlyph = viewModel::setGlyphEnabled,
            onSelectGlyphMode = viewModel::setGlyphRenderMode,
            onToggleIdle = viewModel::setIdleEnabled,
            onSelectIdleMinutes = viewModel::setIdleThresholdMinutes,
            onToggleRain = viewModel::setRainEnabled,
            onSelectPlace = viewModel::selectWeatherPlace,
            onClearPlace = viewModel::clearWeatherPlace,
        )
    }
}
