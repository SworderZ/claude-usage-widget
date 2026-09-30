package space.megaworld.claudeusage.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
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
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.LocalSize
import space.megaworld.claudeusage.AppGraph
import space.megaworld.claudeusage.R
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
 *
 * [SizeMode.Exact] выбран намеренно: ширина полос считается в dp от реального размера
 * виджета, а размер «корзины» из Responsive для этого слишком приблизителен.
 */
class UsageWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = AppGraph.get(context).usageRepository
        // Начальное значение читаем до provideContent, иначе виджет моргнёт
        // состоянием «не авторизован» на первом кадре.
        val initial = repository.currentState()
        provideContent {
            val state by repository.state.collectAsState(initial = initial)
            WidgetBody(state)
        }
    }
}

@Composable
private fun WidgetBody(state: UsageState) {
    val needsLogin = state.status == UsageStatus.NOT_AUTHORIZED ||
        state.status == UsageStatus.SESSION_EXPIRED
    val size = LocalSize.current
    val compact = size.height < COMPACT_HEIGHT_THRESHOLD

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_card_bg))
            .padding(CARD_PADDING)
            .clickable(
                if (needsLogin) {
                    actionStartActivity<MainActivity>()
                } else {
                    actionRunCallback<RefreshWidgetAction>()
                }
            ),
    ) {
        if (!compact) {
            Header()
            Spacer(modifier = GlanceModifier.height(12.dp))
        }

        if (state.hasData) {
            val windows = widgetWindows(state.snapshot!!)
            windows.forEachIndexed { index, window ->
                if (compact) {
                    CompactRow(window = window, stale = state.isStale)
                } else {
                    UsageTile(window = window, stale = state.isStale, widgetWidth = size.width)
                }
                if (index != windows.lastIndex) {
                    Spacer(modifier = GlanceModifier.height(if (compact) 6.dp else 10.dp))
                }
            }
            if (state.isStale && !compact) {
                Spacer(modifier = GlanceModifier.height(8.dp))
                Text(text = staleNote(state), style = secondaryStyle(11.sp))
            }
        } else {
            Placeholder(state = state, compact = compact)
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_claude_mark),
            contentDescription = null,
            modifier = GlanceModifier.size(20.dp),
        )
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = "Claude",
            style = TextStyle(color = ColorProvider(TEXT_PRIMARY), fontSize = 17.sp),
            modifier = GlanceModifier.defaultWeight(),
        )
        Image(
            provider = ImageProvider(R.drawable.ic_refresh),
            contentDescription = "Обновить",
            colorFilter = ColorFilter.tint(ColorProvider(TEXT_PRIMARY)),
            modifier = GlanceModifier
                .size(20.dp)
                .clickable(actionRunCallback<RefreshWidgetAction>()),
        )
    }
}

@Composable
private fun UsageTile(window: UsageWindow, stale: Boolean, widgetWidth: Dp) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(ImageProvider(R.drawable.widget_tile_bg))
            .padding(TILE_PADDING),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = UsageFormat.windowTitle(window.key),
                style = TextStyle(
                    color = ColorProvider(TEXT_PRIMARY),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = UsageFormat.percent(window.utilization),
                style = TextStyle(
                    color = ColorProvider(TEXT_PRIMARY),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }

        Spacer(modifier = GlanceModifier.height(8.dp))
        ProgressBar(window = window, stale = stale, widgetWidth = widgetWidth)
        Spacer(modifier = GlanceModifier.height(8.dp))

        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = UsageFormat.resetTextLong(window.resetsAtMillis) ?: "Время сброса неизвестно",
                style = secondaryStyle(13.sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            // Доля истёкшего времени окна: показывает, обгоняет ли расход часы.
            UsageFormat.elapsedPercent(window.key, window.resetsAtMillis)?.let { elapsed ->
                Spacer(modifier = GlanceModifier.width(8.dp))
                Text(text = "$elapsed%", style = secondaryStyle(13.sp))
            }
        }
    }
}

/**
 * Полосу собираем из двух Box вместо LinearProgressIndicator: только так получаются
 * скруглённые концы и своя палитра. Ширину заливки считаем в dp — Glance не умеет
 * долевые размеры, поэтому и нужен точный размер виджета.
 */
@Composable
private fun ProgressBar(window: UsageWindow, stale: Boolean, widgetWidth: Dp) {
    val available = (widgetWidth.value - (CARD_PADDING.value + TILE_PADDING.value) * 2)
        .coerceAtLeast(MIN_BAR_WIDTH)
    val filled = (available * UsageFormat.fraction(window.utilization)).coerceAtLeast(0f)
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .background(ImageProvider(R.drawable.widget_bar_track)),
    ) {
        if (filled >= 1f) {
            Box(
                modifier = GlanceModifier
                    .width(filled.dp)
                    .height(BAR_HEIGHT)
                    .background(ImageProvider(fillDrawable(window.utilization, stale))),
            ) {}
        }
    }
}

@Composable
private fun CompactRow(window: UsageWindow, stale: Boolean) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = UsageFormat.windowLabel(window.key),
            style = secondaryStyle(13.sp),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = UsageFormat.percent(window.utilization),
            style = TextStyle(
                color = ColorProvider(if (stale) TEXT_SECONDARY else TEXT_PRIMARY),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
private fun Placeholder(state: UsageState, compact: Boolean) {
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
                color = ColorProvider(TEXT_PRIMARY),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        if (!compact) {
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(text = hint, style = secondaryStyle(12.sp))
        }
    }
}

private fun secondaryStyle(size: androidx.compose.ui.unit.TextUnit) =
    TextStyle(color = ColorProvider(TEXT_SECONDARY), fontSize = size)

/** В виджете только 5ч и 7д; остальные окна видны на главном экране приложения. */
private fun widgetWindows(snapshot: UsageSnapshot): List<UsageWindow> {
    val primary = listOfNotNull(snapshot.fiveHour, snapshot.sevenDay)
    return if (primary.isEmpty()) snapshot.windows.take(2) else primary
}

private fun staleNote(state: UsageState): String {
    val updated = UsageFormat.updatedAt(state.snapshot?.fetchedAtMillis ?: 0L)
    return when (state.status) {
        UsageStatus.SESSION_EXPIRED -> "Сессия истекла · данные от $updated"
        else -> "Нет связи · данные от $updated"
    }
}

private fun fillDrawable(utilization: Double, stale: Boolean): Int = when {
    stale -> R.drawable.widget_bar_track
    UsageFormat.level(utilization) == UsageLevel.CRITICAL -> R.drawable.widget_bar_fill_critical
    UsageFormat.level(utilization) == UsageLevel.WARNING -> R.drawable.widget_bar_fill_warning
    else -> R.drawable.widget_bar_fill
}

private val CARD_PADDING = 14.dp
private val TILE_PADDING = 12.dp
private val BAR_HEIGHT = 14.dp
private const val MIN_BAR_WIDTH = 40f
private val COMPACT_HEIGHT_THRESHOLD = 120.dp

private val TEXT_PRIMARY = androidx.compose.ui.graphics.Color(0xFFF3F0F8)
private val TEXT_SECONDARY = androidx.compose.ui.graphics.Color(0xFFA9A1B8)
