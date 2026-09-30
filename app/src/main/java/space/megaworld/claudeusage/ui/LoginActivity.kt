package space.megaworld.claudeusage.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.data.RefreshResult
import space.megaworld.claudeusage.widget.UsageWidget
import space.megaworld.claudeusage.worker.UsageRefreshWorker

/**
 * Логин через WebView: официального способа получить токен нет, поэтому пользователь
 * входит на claude.ai обычным образом, а мы забираем cookie домена из CookieManager.
 */
class LoginActivity : ComponentActivity() {

    private var captureJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClaudeUsageTheme {
                var status by remember { mutableStateOf<String?>(null) }
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LoginWebView(
                            onCookiesReady = { cookieHeader, userAgent ->
                                status = "Сохраняем сессию…"
                                completeLogin(cookieHeader, userAgent) { error ->
                                    status = error
                                }
                            },
                        )
                        status?.let { text ->
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Сохраняет cookie/UA и сразу пробует сходить за данными. Ошибка сети не отменяет
     * логин — cookie уже сохранены, обновится позже.
     */
    private fun completeLogin(
        cookieHeader: String,
        userAgent: String,
        onError: (String) -> Unit,
    ) {
        captureJob?.cancel()
        captureJob = lifecycleScope.launch {
            val graph = AppGraph.get(this@LoginActivity)
            when (val result = graph.usageRepository.onLoggedIn(cookieHeader, userAgent)) {
                is RefreshResult.Failure -> onError("Вошли, но данные не загрузились: ${result.message}")
                else -> Unit
            }
            UsageRefreshWorker.ensureScheduled(
                context = this@LoginActivity,
                intervalMinutes = graph.settingsStore.currentIntervalMinutes(),
            )
            UsageWidget().updateAll(this@LoginActivity)
            setResult(RESULT_OK)
            finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun LoginWebView(onCookiesReady: (String, String) -> Unit) {
        var handled by remember { mutableStateOf(false) }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                WebView(context).apply {
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(LOGIN_URL)

                    // Логин завершается XHR-ом, отдельного перехода страницы может не быть,
                    // поэтому просто опрашиваем CookieManager, пока не появится sessionKey.
                    lifecycleScope.launch {
                        while (isActive && !handled) {
                            cookieManager.flush()
                            val cookies = cookieManager.getCookie(COOKIE_ORIGIN)
                            if (cookies != null && cookies.contains(SESSION_COOKIE)) {
                                handled = true
                                onCookiesReady(cookies, settings.userAgentString)
                            }
                            delay(POLL_INTERVAL_MS)
                        }
                    }
                }
            },
        )
    }

    private companion object {
        const val LOGIN_URL = "https://claude.ai/login"
        const val COOKIE_ORIGIN = "https://claude.ai"
        const val SESSION_COOKIE = "sessionKey="
        const val POLL_INTERVAL_MS = 1_000L
    }
}
