package space.megaworld.claudeusage.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
import space.megaworld.claudeusage.R
import space.megaworld.claudeusage.data.UsageProvider
import space.megaworld.claudeusage.glyph.UsageForegroundService

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* см. ниже */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) selectRequestedProvider(intent)
        followGlyphSetting()
        setContent {
            AppRoot(
                onOpenLogin = { startActivity(Intent(this, LoginActivity::class.java)) },
                onOpenManualLogin = { startActivity(Intent(this, ManualLoginActivity::class.java)) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectRequestedProvider(intent)
    }

    private fun selectRequestedProvider(intent: Intent?) {
        val provider = intent?.getStringExtra(EXTRA_PROVIDER)?.let {
            runCatching { UsageProvider.valueOf(it) }.getOrNull()
        } ?: return
        lifecycleScope.launch { AppGraph.get(this@MainActivity).usageRepository.selectProvider(provider) }
    }

    companion object {
        const val EXTRA_PROVIDER = "usage_provider"
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

private enum class Screen(val label: String, val icon: Int) {
    MAIN("Лимиты", R.drawable.ic_limits),
    GLYPH("Glyph", R.drawable.ic_glyph),
    SETTINGS("Настройки", R.drawable.ic_settings),
}

@Composable
private fun AppRoot(onOpenLogin: () -> Unit, onOpenManualLogin: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    val viewModel: MainViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val loaded = state
    ClaudeUsageTheme(provider = loaded?.provider ?: UsageProvider.CLAUDE) {
        if (loaded == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            BackHandler(enabled = screen != Screen.MAIN) { screen = Screen.MAIN }
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        Screen.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = screen == tab,
                                onClick = { screen = tab },
                                icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
                    when (screen) {
                        Screen.MAIN -> MainScreen(
                            state = loaded, busy = busy, message = message,
                            onRefresh = viewModel::refresh, onLogin = onOpenLogin,
                            onManualLogin = onOpenManualLogin, onLogout = viewModel::logout,
                            onDismissMessage = viewModel::dismissMessage,
                            onSelectProvider = viewModel::selectProvider,
                            onOpenAiLogin = { context.startActivity(Intent(context, OpenAiLoginActivity::class.java)) },
                        )
                        Screen.GLYPH -> GlyphScreen(
                            state = loaded, busy = busy, message = message,
                            onDismissMessage = viewModel::dismissMessage,
                            onToggleGlyph = viewModel::setGlyphEnabled,
                            onSelectGlyphMode = viewModel::setGlyphRenderMode,
                            onToggleIdle = viewModel::setIdleEnabled,
                            onTestA = viewModel::testA,
                            onSelectIdleMinutes = viewModel::setIdleThresholdMinutes,
                            onToggleRain = viewModel::setRainEnabled,
                            onSelectPlace = viewModel::selectWeatherPlace,
                            onClearPlace = viewModel::clearWeatherPlace,
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            state = loaded, busy = busy,
                            onSelectWidgetProvider = viewModel::setWidgetProvider,
                            onSelectOrganization = viewModel::selectOrganization,
                            onSelectInterval = viewModel::setRefreshInterval,
                            onReloadOrganizations = viewModel::reloadOrganizations,
                        )
                    }
                }
            }
        }
    }
}
