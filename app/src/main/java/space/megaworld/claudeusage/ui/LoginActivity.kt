package space.megaworld.claudeusage.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            ClaudeUsageTheme {
                var status by remember { mutableStateOf<String?>(null) }
                var diagnostics by remember { mutableStateOf<String?>(null) }
                var reloadToken by remember { mutableStateOf(0) }

                Scaffold(topBar = { AppTopBar("Войти в Claude", onBack = { finish() }) }) { padding ->
                    Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                        LoginWebView(
                            reloadToken = reloadToken,
                            onCookiesReady = { cookieHeader, userAgent ->
                                status = "Сохраняем сессию…"
                                completeLogin(cookieHeader, userAgent) { error ->
                                    status = error
                                }
                            },
                            onLoadProblem = { diagnostics = it },
                        )

                        diagnostics?.let { text ->
                            DiagnosticsCard(
                                text = text,
                                onReload = {
                                    diagnostics = null
                                    reloadToken++
                                },
                                onDismiss = { diagnostics = null },
                                modifier = Modifier.align(Alignment.TopCenter),
                            )
                        }

                        status?.let { text ->
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
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
    private fun LoginWebView(
        reloadToken: Int,
        onCookiesReady: (String, String) -> Unit,
        onLoadProblem: (String) -> Unit,
    ) {
        var handled by remember { mutableStateOf(false) }
        // Не state: перезагрузку надо отследить, не вызывая рекомпозицию.
        val lastReload = remember { intArrayOf(0) }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                WebView(context).apply {
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // claude.ai отдаёт «App unavailable» браузерам, которые считает
                    // неподдерживаемыми, а дефолтный UA WebView помечен маркерами
                    // "; wv" и "Version/4.0". Убираем их, оставляя настоящую версию
                    // Chrome. Этот же UA потом уходит в заголовки API-запросов.
                    settings.userAgentString = browserLikeUserAgent(context)
                    webViewClient = DiagnosticWebViewClient(onLoadProblem)
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
            update = { webView ->
                if (reloadToken != lastReload[0]) {
                    lastReload[0] = reloadToken
                    webView.loadUrl(LOGIN_URL)
                }
            },
        )
    }

    /** Показывает, что именно не загрузилось: без этого «App unavailable» ничем не объяснить. */
    private class DiagnosticWebViewClient(
        private val onLoadProblem: (String) -> Unit,
    ) : WebViewClient() {

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (!request.isForMainFrame) return
            onLoadProblem("Сеть: ${error.errorCode} ${error.description}\n${request.url}")
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (!request.isForMainFrame) return
            onLoadProblem("HTTP ${errorResponse.statusCode}\n${request.url}")
        }

        /**
         * Заглушку «App unavailable» claude.ai отдаёт с кодом 200, поэтому обработчики
         * ошибок её не видят — ловим по заголовку страницы и показываем UA, чтобы было
         * понятно, дошла ли подмена User-Agent.
         */
        override fun onPageFinished(view: WebView, url: String) {
            val title = view.title.orEmpty()
            val looksBroken = BROKEN_TITLE_MARKERS.any { title.contains(it, ignoreCase = true) }
            if (looksBroken) {
                onLoadProblem(
                    "Сайт ответил страницей «$title»\n$url\n\nUA: ${view.settings.userAgentString}"
                )
            }
        }

        private companion object {
            val BROKEN_TITLE_MARKERS = listOf("unavailable", "not available", "unsupported")
        }
    }

    private companion object {
        const val LOGIN_URL = "https://claude.ai/login"
        const val COOKIE_ORIGIN = "https://claude.ai"
        const val SESSION_COOKIE = "sessionKey="
        const val POLL_INTERVAL_MS = 1_000L

        /** Дефолтный UA WebView без маркеров, по которым сайты его отбраковывают. */
        fun browserLikeUserAgent(context: android.content.Context): String =
            WebSettings.getDefaultUserAgent(context)
                .replace("; wv)", ")")
                .replace(Regex("Version/[0-9.]+ "), "")
    }
}

@Composable
private fun DiagnosticsCard(
    text: String,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(12.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Страница не загрузилась",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = onReload) { Text(text = "Ещё раз") }
                TextButton(onClick = onDismiss, modifier = Modifier.padding(start = 8.dp)) {
                    Text(text = "Скрыть")
                }
            }
        }
    }
}
