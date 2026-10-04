package space.megaworld.claudeusage.ui

import android.os.Bundle
import android.content.Context
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import space.megaworld.claudeusage.widget.WidgetBody
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import space.megaworld.claudeusage.data.*

/** Debug-only, deterministic visual fixtures; never included in release APKs. */
class DesignPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val page = intent.getStringExtra("screen") ?: "MAIN"
        val provider = if (intent.getStringExtra("provider") == "GPT") UsageProvider.CODEX else UsageProvider.CLAUDE
        val scenario = intent.getStringExtra("scenario") ?: "data"
        val cities = listOf(WeatherPlace("Москва",55.75,37.62), WeatherPlace("Санкт-Петербург",59.93,30.31),
            WeatherPlace("Петропавловск-Камчатский",53.04,158.67))
        val now = System.currentTimeMillis()
        val initial = UsageState(provider = provider, widgetProvider = provider,
            status = if (scenario == "empty") UsageStatus.NOT_AUTHORIZED else if (scenario == "error") UsageStatus.NETWORK_ERROR else UsageStatus.OK,
            snapshot = if (scenario == "empty") null else UsageSnapshot(windows = listOf(
                UsageWindow("five_hour",38.0,now+7200000), UsageWindow("seven_day",76.0,now+172800000),
                UsageWindow("seven_day_opus",12.0,now+172800000)), fetchedAtMillis = now),
            organizations = listOf(Organization("d98709e5-78f0-4a16-9a23-11110000abcd", "Личный аккаунт")),
            organizationUuid = "d98709e5-78f0-4a16-9a23-11110000abcd", glyphEnabled = true,
            ambient = AmbientSettings(channelA = GlyphChannelMode.IDLE, channelB = GlyphChannelMode.RAIN,
                stripMode = GlyphStripMode.RAIN, place = cities.first(), places = cities),
            rainForecast = RainForecast(70,now))
        setContent {
            var state by remember { mutableStateOf(initial) }
            var screen by remember { mutableStateOf(runCatching { Screen.valueOf(page) }.getOrDefault(Screen.MAIN)) }
            ClaudeUsageTheme(state.provider) {
                when (page) {
                    "ABOUT" -> AboutContent(installedVersionName(this@DesignPreviewActivity), { finish() }, {})
                    "WIDGET" -> WidgetPreview(state, intent.getIntExtra("width", 320), intent.getIntExtra("height", 170))
                    "LOGIN_GPT" -> {
                        val sample = remember { DeviceAuthorization("preview", "ABCD-1234", 5000, now + 900_000) }
                        var authorization by remember { mutableStateOf(if (scenario == "pending") sample else null) }
                        var browser by remember { mutableStateOf(if (scenario == "browser") BrowserAuthorization.create(now) else null) }
                        OpenAiLoginContent(authorization != null || browser != null,
                            if (scenario == "error") "Время действия кода закончилось. Начните вход заново." else null,
                            null,{},{},{ finish() }, authorization = authorization, browserAuthorization = browser,
                            onStartLogin = { browser = BrowserAuthorization.create(now) },
                            onStartDeviceLogin = { authorization = sample }, onCancelLogin = { authorization = null; browser = null })
                    }
                    "LOGIN_MANUAL" -> {
                        var input by remember { mutableStateOf("") }
                        ManualLoginContent(input,false,null,{ input = it },{},{},{ finish() })
                    }
                    else -> Scaffold(contentWindowInsets = WindowInsets(0,0,0,0), bottomBar = { AppNavigation(screen) { screen = it } }) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                            when (screen) {
                                Screen.MAIN -> MainScreen(state,false,null,{},{},{},{},{},
                                    onSelectProvider = { state = state.copy(provider = it) }, onOpenAiLogin = {})
                                Screen.SETTINGS -> SettingsScreen(state,false,
                                    onSelectWidgetProvider = { state = state.copy(widgetProvider = it) },
                                    onSelectOrganization = { state = state.copy(organizationUuid = it) },
                                    onSelectInterval = { state = state.copy(refreshIntervalMinutes = it) }, onReloadOrganizations = {})
                                Screen.GLYPH -> GlyphScreen(state,false,null,{},
                                    onToggleGlyph = { state = state.copy(glyphEnabled = it) },
                                    onSelectGlyphMode = { state = state.copy(glyphRenderMode = it) },
                                    onSelectStripMode = { state = state.copy(ambient = state.ambient.copy(stripMode = it)) },
                                    onSelectChannelMode = { channel, mode -> state = state.copy(ambient = if (channel == AmbientChannel.A)
                                        state.ambient.copy(channelA = mode) else state.ambient.copy(channelB = mode)) },
                                    onTestChannel = {}, onSelectIdleMinutes = { state = state.copy(ambient = state.ambient.copy(idleThresholdMinutes = it)) },
                                    onAddPlace = {}, onSelectPlace = { state = state.copy(ambient = state.ambient.copy(place = it)) },
                                    onRemovePlace = { city ->
                                        val remaining = state.ambient.places.filter { it.id != city.id }
                                        state = state.copy(ambient = state.ambient.copy(places = remaining,
                                            place = if (state.ambient.place?.id == city.id) remaining.firstOrNull() else state.ambient.place))
                                    }, supported = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

private class PreviewWidget(private val state: UsageState) : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetBody(state, appWidgetId = 1) }
    }
}

@Composable
private fun WidgetPreview(state: UsageState, width: Int, height: Int) {
    val context = LocalContext.current
    var remoteViews by remember { mutableStateOf<RemoteViews?>(null) }
    LaunchedEffect(state, width, height) {
        remoteViews = PreviewWidget(state).compose(context, size = DpSize(width.dp, height.dp))
    }
    androidx.compose.material3.Surface(color = androidx.compose.ui.graphics.Color(0xFF414149), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            remoteViews?.let { views ->
                AndroidView(modifier = Modifier.width(width.dp).height(height.dp),
                    factory = { FrameLayout(it) }, update = { container ->
                        container.removeAllViews()
                        container.addView(views.apply(context, container))
                    })
            }
        }
    }
}
