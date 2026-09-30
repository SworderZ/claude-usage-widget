package space.megaworld.claudeusage.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.data.UsageSnapshot
import space.megaworld.claudeusage.data.UsageState
import space.megaworld.claudeusage.data.UsageStatus
import space.megaworld.claudeusage.data.UsageWindow
import space.megaworld.claudeusage.ui.MainActivity
import space.megaworld.claudeusage.ui.UsageFormat
import space.megaworld.claudeusage.ui.UsageLevel

/**
 * Виджет расхода лимитов. Данные берёт из репозитория (кеш в DataStore) — сам в сеть
 * не ходит, этим занимается UsageRefreshWorker.
 */
class UsageWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT_SIZE, FULL_SIZE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = AppGraph.get(context).usageRepository
        // Начальное значение читаем до provideContent, иначе виджет моргнёт
        // состоянием «не авторизован» на первом кадре.
        val initial = repository.currentState()
        provideContent {
            val state by repository.state.collectAsState(initial = initial)
            GlanceTheme {
                WidgetBody(state)
            }
        }
    }

    companion object {
        /** 2x1 — только проценты. */
        private val COMPACT_SIZE = DpSize(110.dp, 40.dp)

        /** 4x2 — полосы, время сброса и время обновления. */
        private val FULL_SIZE = DpSize(250.dp, 110.dp)

        internal val COMPACT_WIDTH_THRESHOLD = 180.dp
    }
}

@Composable
private fun WidgetBody(state: UsageState) {
    val needsLogin = state.status == UsageStatus.NOT_AUTHORIZED ||
        state.status == UsageStatus.SESSION_EXPIRED
    val tapAction = if (needsLogin) {
        // Открываем главный экран, а не сразу WebView: там есть выбор между входом
        // через WebView и ручным вводом sessionKey.
        actionStartActivity<MainActivity>()
    } else {
        actionRunCallback<RefreshWidgetAction>()
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(tapAction),
    ) {
        val compact = LocalSize.current.width < UsageWidget.COMPACT_WIDTH_THRESHOLD
        if (state.hasData) {
            if (compact) CompactContent(state) else FullContent(state)
        } else {
            PlaceholderContent(state, compact)
        }
    }
}

@Composable
private fun CompactContent(state: UsageState) {
    val snapshot = state.snapshot ?: return
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        widgetWindows(snapshot).forEach { window ->
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    text = UsageFormat.windowLabel(window.key),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                    ),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(
                    text = UsageFormat.percent(window.utilization),
                    style = TextStyle(
                        color = levelColor(window.utilization, state.isStale),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
    }
}

@Composable
private fun FullContent(state: UsageState) {
    val snapshot = state.snapshot ?: return
    Column(modifier = GlanceModifier.fillMaxSize()) {
        widgetWindows(snapshot).forEach { window ->
            UsageBar(window = window, stale = state.isStale)
            Spacer(modifier = GlanceModifier.height(8.dp))
        }
        Text(
            text = footerText(state),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 11.sp,
            ),
        )
    }
}

@Composable
private fun UsageBar(window: UsageWindow, stale: Boolean) {
    val color = levelColor(window.utilization, stale)
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        Text(
            text = UsageFormat.windowLabel(window.key),
            style = TextStyle(
                color = if (stale) {
                    GlanceTheme.colors.onSurfaceVariant
                } else {
                    GlanceTheme.colors.onSurface
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(modifier = GlanceModifier.defaultWeight())
        val hint = UsageFormat.resetHint(window.resetsAtMillis)
        if (hint != null) {
            Text(
                text = hint,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                ),
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
        }
        Text(
            text = UsageFormat.percent(window.utilization),
            style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        )
    }
    Spacer(modifier = GlanceModifier.height(4.dp))
    LinearProgressIndicator(
        progress = UsageFormat.fraction(window.utilization),
        modifier = GlanceModifier.fillMaxWidth().height(6.dp),
        color = color,
        backgroundColor = ColorProvider(TRACK_COLOR),
    )
}

@Composable
private fun PlaceholderContent(state: UsageState, compact: Boolean) {
    val title: String
    val hint: String
    when (state.status) {
        UsageStatus.NOT_AUTHORIZED -> {
            title = "Не авторизован"
            hint = "нажмите, чтобы войти"
        }
        UsageStatus.SESSION_EXPIRED -> {
            title = "Сессия истекла"
            hint = "нажмите, чтобы войти заново"
        }
        UsageStatus.NETWORK_ERROR -> {
            title = "Ошибка сети"
            hint = "нажмите, чтобы повторить"
        }
        UsageStatus.NEVER_LOADED, UsageStatus.OK -> {
            title = "Загрузка…"
            hint = "нажмите, чтобы обновить"
        }
    }
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        if (!compact) {
            Text(
                text = hint,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                ),
            )
        }
    }
}

/** В виджете только 5ч и 7д; остальные окна видны на главном экране приложения. */
private fun widgetWindows(snapshot: UsageSnapshot): List<UsageWindow> {
    val primary = listOfNotNull(snapshot.fiveHour, snapshot.sevenDay)
    return if (primary.isEmpty()) snapshot.windows.take(2) else primary
}

private fun footerText(state: UsageState): String {
    val updated = state.snapshot?.fetchedAtMillis ?: 0L
    val prefix = when (state.status) {
        UsageStatus.SESSION_EXPIRED -> "сессия истекла · "
        UsageStatus.NETWORK_ERROR -> "нет связи · "
        else -> ""
    }
    return prefix + "обновлено " + UsageFormat.updatedAt(updated)
}

private fun levelColor(utilization: Double, stale: Boolean): ColorProvider {
    val base = when (UsageFormat.level(utilization)) {
        UsageLevel.NORMAL -> NORMAL_COLOR
        UsageLevel.WARNING -> WARNING_COLOR
        UsageLevel.CRITICAL -> CRITICAL_COLOR
    }
    return ColorProvider(if (stale) base.copy(alpha = 0.45f) else base)
}

private val NORMAL_COLOR = Color(0xFF3F7DE0)
private val WARNING_COLOR = Color(0xFFE0A400)
private val CRITICAL_COLOR = Color(0xFFD93025)
private val TRACK_COLOR = Color(0x33808080)
