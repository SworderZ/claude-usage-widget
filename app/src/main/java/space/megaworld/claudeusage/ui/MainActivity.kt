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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        )
    }
}
