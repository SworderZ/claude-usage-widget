package space.megaworld.claudeusage.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
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
 * [SizeMode.Exact] выбран намеренно: и ширина полос, и набор отступов считаются от
 * реального размера виджета, а размер «корзины» из Responsive для этого слишком груб.
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

/**
 * Набор размеров под конкретную высоту виджета. Содержимое фиксированной высоты в
 * 4x2 не влезает, поэтому вместо одного макета подбираем плотность по месту.
 */
private data class Metrics(
    val cardPadding: Dp,
    val tilePadding: Dp,
    val tileBackground: Boolean,
    val header: Boolean,
    val headerIcon: Dp,
    val headerFont: TextUnit,
    val headerGap: Dp,
    val tileGap: Dp,
    val titleFont: TextUnit,
    val percentFont: TextUnit,
    val barHeight: Dp,
    val gapTitleBar: Dp,
    val gapBarReset: Dp,
    val resetFont: TextUnit,
    val reset: Boolean,
)

/**
 * Пороги с запасом: лаунчер отдаёт виджету меньше места, чем сообщает LocalSize
 * (свои поля вокруг), поэтому содержимое плитки держим заметно ниже её доли высоты —
 * иначе снизу обрезается строка сброса.
 */
private fun metricsFor(height: Dp): Metrics = when {
    height >= 230.dp -> Metrics(
        cardPadding = 13.dp, tilePadding = 11.dp, tileBackground = true,
        header = true, headerIcon = 19.dp, headerFont = 16.sp, headerGap = 10.dp,
        tileGap = 9.dp, titleFont = 14.sp, percentFont = 17.sp,
        barHeight = 11.dp, gapTitleBar = 7.dp, gapBarReset = 7.dp,
        resetFont = 12.sp, reset = true,
    )
    height >= 170.dp -> Metrics(
        cardPadding = 11.dp, tilePadding = 9.dp, tileBackground = true,
        header = true, headerIcon = 16.dp, headerFont = 14.sp, headerGap = 8.dp,
        tileGap = 7.dp, titleFont = 13.sp, percentFont = 15.sp,
        barHeight = 9.dp, gapTitleBar = 5.dp, gapBarReset = 5.dp,
        resetFont = 11.sp, reset = true,
    )
    height >= 130.dp -> Metrics(
        cardPadding = 9.dp, tilePadding = 8.dp, tileBackground = true,
        header = false, headerIcon = 0.dp, headerFont = 0.sp, headerGap = 0.dp,
        tileGap = 6.dp, titleFont = 13.sp, percentFont = 15.sp,
        barHeight = 9.dp, gapTitleBar = 5.dp, gapBarReset = 5.dp,
        resetFont = 11.sp, reset = true,
    )
    height >= 95.dp -> Metrics(
        cardPadding = 8.dp, tilePadding = 0.dp, tileBackground = false,
        header = false, headerIcon = 0.dp, headerFont = 0.sp, headerGap = 0.dp,
        tileGap = 6.dp, titleFont = 12.sp, percentFont = 14.sp,
        barHeight = 8.dp, gapTitleBar = 4.dp, gapBarReset = 0.dp,
        resetFont = 0.sp, reset = false,
    )
    else -> Metrics(
        cardPadding = 8.dp, tilePadding = 0.dp, tileBackground = false,
        header = false, headerIcon = 0.dp, headerFont = 0.sp, headerGap = 0.dp,
        tileGap = 4.dp, titleFont = 12.sp, percentFont = 14.sp,
        barHeight = 0.dp, gapTitleBar = 0.dp, gapBarReset = 0.dp,
        resetFont = 0.sp, reset = false,
    )
}

@Composable
private fun WidgetBody(state: UsageState) {
    val size = LocalSize.current
    val m = metricsFor(size.height)
    val needsLogin = state.status == UsageStatus.NOT_AUTHORIZED ||
        state.status == UsageStatus.SESSION_EXPIRED

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_card_bg))
            .padding(m.cardPadding)
            .clickable(
                if (needsLogin) {
                    actionStartActivity<MainActivity>()
                } else {
                    actionRunCallback<RefreshWidgetAction>()
                }
            ),
    ) {
        if (m.header) {
            Header(m)
            Spacer(modifier = GlanceModifier.height(m.headerGap))
        }

        if (state.hasData) {
            val windows = widgetWindows(state.snapshot!!)
            windows.forEachIndexed { index, window ->
                // defaultWeight: плитки делят остаток высоты поровну, что бы ни
                // осталось после шапки — так нижняя не уезжает за край.
                UsageTile(
                    window = window,
                    stale = state.isStale,
                    metrics = m,
                    widgetWidth = size.width,
                    modifier = GlanceModifier.defaultWeight(),
                )
                if (index != windows.lastIndex) {
                    Spacer(modifier = GlanceModifier.height(m.tileGap))
                }
            }
        } else {
            Placeholder(state = state, metrics = m)
        }
    }
}

@Composable
private fun Header(m: Metrics) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Значок и название — отдельная цель: открывают приложение, а не обновление.
        Row(
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_claude_mark),
                contentDescription = "Открыть приложение",
                modifier = GlanceModifier.size(m.headerIcon),
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = "Claude",
                style = TextStyle(color = ColorProvider(TEXT_PRIMARY), fontSize = m.headerFont),
                maxLines = 1,
            )
        }
        Image(
            provider = ImageProvider(R.drawable.ic_refresh),
            contentDescription = "Обновить",
            colorFilter = ColorFilter.tint(ColorProvider(TEXT_PRIMARY)),
            modifier = GlanceModifier
                .size(m.headerIcon)
                .clickable(actionRunCallback<RefreshWidgetAction>()),
        )
    }
}

@Composable
private fun UsageTile(
    window: UsageWindow,
    stale: Boolean,
    metrics: Metrics,
    widgetWidth: Dp,
    modifier: GlanceModifier = GlanceModifier,
) {
    var tile = modifier.fillMaxWidth()
    if (metrics.tileBackground) {
        tile = tile.background(ImageProvider(R.drawable.widget_tile_bg)).padding(metrics.tilePadding)
    }
    Column(modifier = tile, verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = UsageFormat.windowTitle(window.key),
                style = TextStyle(
                    color = ColorProvider(TEXT_PRIMARY),
                    fontSize = metrics.titleFont,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = UsageFormat.percent(window.utilization),
                style = TextStyle(
                    color = ColorProvider(TEXT_PRIMARY),
                    fontSize = metrics.percentFont,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }

        if (metrics.barHeight > 0.dp) {
            Spacer(modifier = GlanceModifier.height(metrics.gapTitleBar))
            ProgressBar(
                window = window,
                stale = stale,
                metrics = metrics,
                widgetWidth = widgetWidth,
            )
        }

        if (metrics.reset) {
            Spacer(modifier = GlanceModifier.height(metrics.gapBarReset))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = UsageFormat.resetTextLong(window.resetsAtMillis) ?: "Сброс неизвестен",
                    style = TextStyle(
                        color = ColorProvider(TEXT_SECONDARY),
                        fontSize = metrics.resetFont,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                // Доля истёкшего времени окна: показывает, обгоняет ли расход часы.
                UsageFormat.elapsedPercent(window.key, window.resetsAtMillis)?.let { elapsed ->
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    Text(
                        text = "$elapsed%",
                        style = TextStyle(
                            color = ColorProvider(TEXT_SECONDARY),
                            fontSize = metrics.resetFont,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Полоса из трёх слоёв: трек, доля истёкшего времени окна и поверх неё расход.
 *
 * Средний слой — главное здесь: если яркая заливка расхода обгоняет его, лимит
 * кончится раньше, чем окно сбросится. Сравнение видно одним взглядом, без цифр.
 *
 * Собрано на Box со своими shape-drawable, а не на LinearProgressIndicator: тому не
 * задать ни три слоя, ни скруглённые концы. Ширины считаем в dp — Glance не умеет
 * долевые размеры, поэтому и нужен точный размер виджета.
 */
@Composable
private fun ProgressBar(
    window: UsageWindow,
    stale: Boolean,
    metrics: Metrics,
    widgetWidth: Dp,
) {
    val inset = (metrics.cardPadding.value + metrics.tilePadding.value) * 2
    val available = (widgetWidth.value - inset).coerceAtLeast(MIN_BAR_WIDTH)
    val minVisible = metrics.barHeight.value

    fun widthFor(fraction: Float): Float {
        val raw = available * fraction
        // Ненулевая доля не должна пропадать: минимум — кружок в высоту полосы.
        return if (raw > 0f) raw.coerceAtLeast(minVisible) else 0f
    }

    val usedWidth = widthFor(UsageFormat.fraction(window.utilization))
    val elapsedWidth = UsageFormat.elapsedPercent(window.key, window.resetsAtMillis)
        ?.let { widthFor(it / 100f) } ?: 0f

    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(metrics.barHeight)
            .background(ImageProvider(R.drawable.widget_bar_track)),
    ) {
        if (elapsedWidth > 0f) {
            Box(
                modifier = GlanceModifier
                    .width(elapsedWidth.dp)
                    .height(metrics.barHeight)
                    .background(ImageProvider(R.drawable.widget_bar_elapsed)),
            ) {}
        }
        // Расход рисуем последним, чтобы он был виден и когда отстаёт от времени,
        // и когда обгоняет его.
        if (usedWidth > 0f) {
            Box(
                modifier = GlanceModifier
                    .width(usedWidth.dp)
                    .height(metrics.barHeight)
                    .background(ImageProvider(fillDrawable(window.utilization, stale))),
            ) {}
        }
    }
}

@Composable
private fun Placeholder(state: UsageState, metrics: Metrics) {
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
                fontSize = metrics.percentFont,
                fontWeight = FontWeight.Medium,
            ),
        )
        if (metrics.reset) {
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = hint,
                style = TextStyle(
                    color = ColorProvider(TEXT_SECONDARY),
                    fontSize = metrics.resetFont,
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

private fun fillDrawable(utilization: Double, stale: Boolean): Int = when {
    stale -> R.drawable.widget_bar_fill_stale
    UsageFormat.level(utilization) == UsageLevel.CRITICAL -> R.drawable.widget_bar_fill_critical
    UsageFormat.level(utilization) == UsageLevel.WARNING -> R.drawable.widget_bar_fill_warning
    else -> R.drawable.widget_bar_fill
}

private const val MIN_BAR_WIDTH = 40f

private val TEXT_PRIMARY = androidx.compose.ui.graphics.Color(0xFFF3F0F8)
private val TEXT_SECONDARY = androidx.compose.ui.graphics.Color(0xFFA9A1B8)
