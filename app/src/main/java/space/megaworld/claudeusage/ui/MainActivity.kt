package space.megaworld.claudeusage.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.glyph.GlyphController
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val glyph by lazy { GlyphController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        driveGlyphWhileVisible()
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
     * Glyph SDK разрешает работу только приложению на переднем плане, поэтому полосу
     * ведёт Activity: сессия открывается на STARTED и закрывается на STOP. Из воркера
     * это сделать нельзя — так устроен SDK.
     */
    private fun driveGlyphWhileVisible() {
        if (!glyph.isSupportedDevice) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                glyph.connect()
                try {
                    AppGraph.get(this@MainActivity).usageRepository.state.collect { state ->
                        val fiveHour = state.snapshot?.fiveHour
                        if (state.glyphEnabled && fiveHour != null) {
                            glyph.showProgress(fiveHour.utilization.roundToInt())
                        } else {
                            glyph.turnOff()
                        }
                    }
                } finally {
                    glyph.disconnect()
                }
            }
        }
    }
}

private enum class Screen { MAIN, SETTINGS }

@Composable
private fun AppRoot(onOpenLogin: () -> Unit, onOpenManualLogin: () -> Unit) {
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
        )
        Screen.SETTINGS -> SettingsScreen(
            state = loaded,
            busy = busy,
            onBack = { screen = Screen.MAIN },
            onSelectOrganization = viewModel::selectOrganization,
            onSelectInterval = viewModel::setRefreshInterval,
            onReloadOrganizations = viewModel::reloadOrganizations,
            onToggleGlyph = viewModel::setGlyphEnabled,
        )
    }
}
