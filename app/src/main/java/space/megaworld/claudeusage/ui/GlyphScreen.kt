package space.megaworld.claudeusage.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import space.megaworld.claudeusage.data.AmbientSettings
import space.megaworld.claudeusage.data.GlyphRenderMode
import space.megaworld.claudeusage.data.RainForecast
import space.megaworld.claudeusage.data.UsageProvider
import space.megaworld.claudeusage.data.UsageState
import space.megaworld.claudeusage.data.WeatherPlace
import space.megaworld.claudeusage.glyph.GlyphSupport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphScreen(
    state: UsageState,
    busy: Boolean,
    message: String?,
    onDismissMessage: () -> Unit,
    onToggleGlyph: (Boolean) -> Unit,
    onSelectGlyphMode: (GlyphRenderMode) -> Unit,
    onToggleIdle: (Boolean) -> Unit,
    onSelectIdleMinutes: (Int) -> Unit,
    onToggleRain: (Boolean) -> Unit,
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
                    onToggleIdle = onToggleIdle, onSelectIdleMinutes = onSelectIdleMinutes,
                    onToggleRain = onToggleRain, onSelectPlace = onSelectPlace, onClearPlace = onClearPlace,
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

/**
 * Короткие каналы A и B.
 *
 * Процент на одиночном канале не покажешь, поэтому каждому досталось состояние:
 * A — сколько выключен экран, B — ждать ли дождя. Отличаются они
 * яркостью, и этого хватает: оба нужны, только чтобы бросить взгляд на лежащий
 * экраном вниз телефон.
 */
@Composable
private fun AmbientSetting(
    settings: AmbientSettings,
    forecast: RainForecast?,
    busy: Boolean,
    onToggleIdle: (Boolean) -> Unit,
    onSelectIdleMinutes: (Int) -> Unit,
    onToggleRain: (Boolean) -> Unit,
    onSelectPlace: (String) -> Unit,
    onClearPlace: () -> Unit,
) {
    Column {
        Text(text = "Каналы A и B", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "A — время с выключения экрана. B — вероятность дождя. Положите телефон экраном вниз, чтобы видеть подсветку.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "A: экран не включали", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Загорается слабо после выбранного времени и постепенно становится ярче.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.idleEnabled, onCheckedChange = onToggleIdle, enabled = !busy)
        }

        if (settings.idleEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Как зажечь A", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Выберите время ниже, заблокируйте экран и оставьте телефон. " +
                            "Включение экрана гасит A и сбрасывает отсчёт. Движения телефона не учитываются. " +
                            "В режиме сна Android может задержать срабатывание.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Загорается через", style = MaterialTheme.typography.bodyMedium)
            AmbientSettings.ALLOWED_IDLE_MINUTES.forEach { minutes ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { onSelectIdleMinutes(minutes) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = minutes == settings.idleThresholdMinutes,
                        onClick = { onSelectIdleMinutes(minutes) },
                        enabled = !busy,
                    )
                    Text(
                        text = "$minutes мин",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "B: ожидается дождь", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Яркость — вероятность осадков на ближайшие три часа. " +
                        "Ниже 30% канал погашен.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.rainEnabled, onCheckedChange = onToggleRain, enabled = !busy)
        }

        if (settings.rainEnabled) {
            Spacer(modifier = Modifier.height(12.dp))
            PlacePicker(
                place = settings.place,
                busy = busy,
                onSelectPlace = onSelectPlace,
                onClearPlace = onClearPlace,
            )
            forecast?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Последний прогноз: " + it.probabilityPercent + "% · " +
                        UsageFormat.updatedAt(it.fetchedAtMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = """
                Прогноз Open-Meteo обновляется раз в полчаса. Введите город;
                доступ к геолокации и регистрация не нужны.
            """.trimIndent(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            text = "Место не выбрано — канал B будет погашен.",
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
