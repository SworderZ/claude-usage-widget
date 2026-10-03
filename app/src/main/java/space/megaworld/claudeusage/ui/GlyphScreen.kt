package space.megaworld.claudeusage.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.unit.dp
import space.megaworld.claudeusage.data.AmbientChannel
import space.megaworld.claudeusage.data.GlyphChannelMode
import space.megaworld.claudeusage.data.AmbientSettings
import space.megaworld.claudeusage.data.GlyphRenderMode
import space.megaworld.claudeusage.data.RainForecast
import space.megaworld.claudeusage.data.UsageProvider
import space.megaworld.claudeusage.data.UsageState
import space.megaworld.claudeusage.data.WeatherPlace
import space.megaworld.claudeusage.glyph.GlyphSupport
import space.megaworld.claudeusage.glyph.UsageForegroundService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphScreen(
    state: UsageState,
    busy: Boolean,
    message: String?,
    onDismissMessage: () -> Unit,
    onToggleGlyph: (Boolean) -> Unit,
    onSelectGlyphMode: (GlyphRenderMode) -> Unit,
    onSelectChannelMode: (AmbientChannel, GlyphChannelMode) -> Unit,
    onTestChannel: (AmbientChannel) -> Unit,
    onSelectIdleMinutes: (Int) -> Unit,
    onSelectPlace: (String) -> Unit,
    onClearPlace: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Glyph") }) }) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (!GlyphSupport.isAvailable) {
                Text("Индикация доступна на Nothing Phone (2a) и (2a) Plus.", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Включить Glyph", style = MaterialTheme.typography.titleMedium)
                    Text("Общий переключатель для каналов A, B и C", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.glyphEnabled, onCheckedChange = onToggleGlyph, enabled = !busy)
            }
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onDismissMessage) { Text("Скрыть") }
                    }
                }
            }
            if (state.glyphEnabled) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                AmbientSetting(
                    settings = state.ambient, forecast = state.rainForecast, busy = busy,
                    onSelectChannelMode = onSelectChannelMode, onTestChannel = onTestChannel,
                    onSelectIdleMinutes = onSelectIdleMinutes, onSelectPlace = onSelectPlace, onClearPlace = onClearPlace,
                )
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                GlyphSetting(provider = state.provider, mode = state.glyphRenderMode, busy = busy, onSelectMode = onSelectGlyphMode)
            }
            Spacer(Modifier.height(24.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Если подсветка не включается", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Для этой сборки нужно разрешить отладку Glyph с компьютера. Разрешение действует 48 часов:",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("adb shell settings put global nt_glyph_interface_debug_enable 1",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    Text("Во время работы Glyph в шторке есть уведомление службы. Если система откажет в доступе, причина появится в нём.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Независимый выбор функции A и B; настройки одинаковых функций общие. */
@Composable
private fun AmbientSetting(
    settings: AmbientSettings,
    forecast: RainForecast?,
    busy: Boolean,
    onSelectChannelMode: (AmbientChannel, GlyphChannelMode) -> Unit,
    onTestChannel: (AmbientChannel) -> Unit,
    onSelectIdleMinutes: (Int) -> Unit,
    onSelectPlace: (String) -> Unit,
    onClearPlace: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Каналы A и B", style = MaterialTheme.typography.titleMedium)
        Text("Выберите функцию для каждого канала. Город и время ожидания общие, если функция выбрана на обоих.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AmbientChannel.entries.forEach { channel ->
            ChannelModePicker(
                channel = channel, selected = settings.modeFor(channel), enabled = !busy,
                onSelect = { onSelectChannelMode(channel, it) }, onTest = { onTestChannel(channel) },
            )
        }
        if (settings.idleEnabled) {
            HorizontalDivider()
            Text("Время с выключения экрана", style = MaterialTheme.typography.titleMedium)
            Text("Заблокируйте экран и оставьте телефон экраном вниз. После выбранного времени подсветка " +
                "начнёт светиться слабо и постепенно станет ярче. Включение экрана сбрасывает отсчёт; движения не учитываются.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IdleAlarmNotice()
            Text("Загорается через", style = MaterialTheme.typography.bodyMedium)
            AmbientSettings.ALLOWED_IDLE_MINUTES.forEach { minutes ->
                Row(modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { onSelectIdleMinutes(minutes) },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = minutes == settings.idleThresholdMinutes,
                        onClick = { onSelectIdleMinutes(minutes) }, enabled = !busy)
                    Text("$minutes мин", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        if (settings.rainEnabled) {
            HorizontalDivider()
            Text("Прогноз дождя", style = MaterialTheme.typography.titleMedium)
            Text("Яркость показывает вероятность осадков на ближайшие три часа. Ниже 30% подсветка погашена.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PlacePicker(place = settings.place, busy = busy, onSelectPlace = onSelectPlace, onClearPlace = onClearPlace)
            forecast?.let {
                Text("Последний прогноз: ${it.probabilityPercent}% · " + UsageFormat.updatedAt(it.fetchedAtMillis),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Прогноз Open-Meteo обновляется раз в полчаса. Введите город; доступ к геолокации и регистрация не нужны.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelModePicker(
    channel: AmbientChannel,
    selected: GlyphChannelMode,
    enabled: Boolean,
    onSelect: (GlyphChannelMode) -> Unit,
    onTest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Канал $channel", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                GlyphChannelMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = selected == mode, onClick = { onSelect(mode) }, enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index, GlyphChannelMode.entries.size),
                        label = { Text(mode.label) }, icon = {},
                    )
                }
            }
            val description = when (selected) {
                GlyphChannelMode.OFF -> "Обычная подсветка этого канала выключена."
                GlyphChannelMode.RAIN -> "Вероятность дождя для выбранного города."
                GlyphChannelMode.IDLE -> "Сколько времени экран остаётся выключенным."
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onTest, enabled = enabled) { Text("Проверить $channel · 5 секунд") }
            Text("Проверка включает полную яркость и возвращает обычную индикацию. Экран можно оставить включённым.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Место для прогноза: вводится названием, координаты достаёт геокодер. */
@Composable
private fun PlacePicker(
    place: WeatherPlace?,
    busy: Boolean,
    onSelectPlace: (String) -> Unit,
    onClearPlace: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }

    if (place == null) {
        Text(
            text = "Город не выбран — индикация дождя будет погашена.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = place.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "%.3f, %.3f".format(place.latitude, place.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onClearPlace, enabled = !busy) {
                Text(text = "Сбросить")
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(text = "Город") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            onSelectPlace(query)
            query = ""
        },
        enabled = !busy && query.isNotBlank(),
    ) {
        Text(text = "Найти")
    }
}

@Composable
private fun GlyphSetting(
    provider: UsageProvider,
    mode: GlyphRenderMode,
    busy: Boolean,
    onSelectMode: (GlyphRenderMode) -> Unit,
) {
    Column {
        Text(text = "C: лимит " + provider.displayLabel(), style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Полоса показывает расход 5-часового окна ИИ, выбранного на вкладке «Лимиты».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Как именно полоса заполняется, зависит от прошивки — вариант подбирается глазами.
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "Отрисовка полосы", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Если заливка идёт не с той стороны или выглядит неправильно, " +
                "переключите вариант и посмотрите на полосу.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        GlyphRenderMode.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) { onSelectMode(option) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = option == mode,
                    onClick = { onSelectMode(option) },
                    enabled = !busy,
                )
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

    }
}

@Composable
private fun IdleAlarmNotice() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val context = LocalContext.current
    var exactAllowed by remember { mutableStateOf(UsageForegroundService.canScheduleExact(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exactAllowed = UsageForegroundService.canScheduleExact(context)
    }
    if (exactAllowed) return
    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Срабатывание таймера во время сна", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("Разрешите точные будильники, чтобы подсветка загоралась ближе к выбранному времени. " +
                "Без разрешения Android может отложить подсветку.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:" + context.packageName)))
            }) { Text("Разрешить будильники") }
        }
    }
}
