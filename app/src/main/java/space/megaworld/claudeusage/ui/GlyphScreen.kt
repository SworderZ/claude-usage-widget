package space.megaworld.claudeusage.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import space.megaworld.claudeusage.R
import space.megaworld.claudeusage.data.*
import space.megaworld.claudeusage.glyph.GlyphSupport
import space.megaworld.claudeusage.glyph.UsageForegroundService

@Composable
fun GlyphScreen(
    state: UsageState, busy: Boolean, message: String?, onDismissMessage: () -> Unit,
    onToggleGlyph: (Boolean) -> Unit, onSelectGlyphMode: (GlyphRenderMode) -> Unit,
    onSelectStripMode: (GlyphStripMode) -> Unit,
    onSelectChannelMode: (AmbientChannel, GlyphChannelMode) -> Unit,
    onTestChannel: (AmbientChannel) -> Unit, onSelectIdleMinutes: (Int) -> Unit,
    onAddPlace: (String) -> Unit, onSelectPlace: (WeatherPlace) -> Unit,
    onRemovePlace: (WeatherPlace) -> Unit,
    supported: Boolean = GlyphSupport.isAvailable,
) {
    Scaffold(topBar = { AppTopBar("Glyph", "Сигналы на задней панели") }) { padding ->
        ScreenColumn(padding) {
            if (!supported) {
                SectionCard {
                    Text("Нужен Nothing Phone", style = MaterialTheme.typography.titleMedium)
                    SupportingText("Подсветка поддерживается на Phone (2a) и (2a) Plus. Лимиты и виджет работают на других телефонах.")
                }
                return@ScreenColumn
            }
            SectionCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Подсветка Glyph", style = MaterialTheme.typography.titleMedium)
                        SupportingText(if (state.glyphEnabled) "Включена · каналы A, B и C" else "Выключена")
                    }
                    Switch(state.glyphEnabled, onCheckedChange = onToggleGlyph, enabled = !busy)
                }
            }
            BusyLine(busy)
            if (!message.isNullOrBlank()) MessageCard(message, onDismiss = onDismissMessage)
            if (state.glyphEnabled) {
                StripCard(state, busy, onSelectStripMode)
                AmbientChannel.entries.forEach { channel ->
                    ChannelCard(channel, state.ambient.modeFor(channel), !busy,
                        onSelect = { onSelectChannelMode(channel, it) }, onTest = { onTestChannel(channel) })
                }
                if (state.ambient.idleEnabled) {
                    SectionCard {
                        Text("Телефон отдыхает", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        CenteredSupportingText("Загорается после выбранного времени с выключения экрана.")
                        ChoiceChips(AmbientSettings.ALLOWED_IDLE_MINUTES.toList(), state.ambient.idleThresholdMinutes,
                            !busy, label = { "$it мин" }, onSelect = onSelectIdleMinutes,
                            horizontalAlignment = Alignment.CenterHorizontally)
                        CenteredSupportingText("Заблокируйте экран и положите телефон экраном вниз. Свет постепенно станет ярче. Включение экрана сбрасывает отсчёт.")
                        IdleAlarmNotice()
                    }
                }
                if (state.ambient.rainEnabled) WeatherCard(state.ambient, state.rainForecast, busy,
                    onAddPlace, onSelectPlace, onRemovePlace)
            }
            ExpandableSection("Настройка и помощь") {
                if (state.glyphEnabled && state.ambient.stripMode != GlyphStripMode.OFF) {
                    Text("Направление полосы C", style = MaterialTheme.typography.titleSmall)
                    GlyphRenderMode.entries.forEach { mode ->
                        Row(Modifier.fillMaxWidth().selectable(selected = mode == state.glyphRenderMode,
                            enabled = !busy, role = Role.RadioButton, onClick = { onSelectGlyphMode(mode) })
                            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(mode == state.glyphRenderMode, onClick = null, enabled = !busy)
                            Text(mode.label, modifier = Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider()
                }
                Text("Если подсветка не включается", style = MaterialTheme.typography.titleSmall)
                SupportingText("Разрешите отладку Glyph с компьютера. Разрешение действует 48 часов:")
                Text("adb shell settings put global nt_glyph_interface_debug_enable 1",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                SupportingText("Причина отказа в доступе появляется в постоянном уведомлении tinyGlyph.")
                SupportingText("Проверка A и B включает полную яркость на 5 секунд. Экран можно оставить включённым; затем возвращается выбранная индикация.")
            }
        }
    }
}

@Composable
private fun ChannelHeading(channel: String, title: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) { Text(channel, style = MaterialTheme.typography.titleMedium) }
        Text(title, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f, fill = false), textAlign = TextAlign.Center)
    }
}

@Composable
private fun CenteredSupportingText(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ChannelCard(channel: AmbientChannel, selected: GlyphChannelMode, enabled: Boolean,
    onSelect: (GlyphChannelMode) -> Unit, onTest: () -> Unit) {
    SectionCard {
        ChannelHeading(channel.name, "Короткий канал")
        ChoiceChips(GlyphChannelMode.entries, selected, enabled, label = { it.label }, onSelect = onSelect,
            horizontalAlignment = Alignment.CenterHorizontally)
        CenteredSupportingText(when (selected) {
            GlyphChannelMode.OFF -> "Этот канал погашен."
            GlyphChannelMode.RAIN -> "Яркость показывает вероятность осадков. Ниже 30% свет погашен."
            GlyphChannelMode.IDLE -> "Свет показывает время с выключения экрана."
        })
        OutlinedButton(onClick = onTest, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Проверить $channel · 5 секунд", textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun StripCard(state: UsageState, busy: Boolean, onSelect: (GlyphStripMode) -> Unit) {
    val mode = state.ambient.stripMode
    val percent = state.ambient.stripPercent(state.snapshot?.fiveHour?.utilization, state.rainForecast?.probabilityPercent)
    SectionCard {
        ChannelHeading("C", "Полоса прогресса")
        ChoiceChips(GlyphStripMode.entries, mode, !busy, label = { it.label }, onSelect = onSelect,
            horizontalAlignment = Alignment.CenterHorizontally)
        CenteredSupportingText(when (mode) {
            GlyphStripMode.USAGE -> "Расход 5-часового окна ${state.provider.displayLabel()}"
            GlyphStripMode.RAIN -> state.ambient.place?.name ?: "Добавьте город в блоке погоды ниже."
            GlyphStripMode.OFF -> "Полоса погашена."
        })
        if (mode != GlyphStripMode.OFF && percent != null) {
            Text("$percent%", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            CenteredSupportingText("Заполнение полосы соответствует проценту.")
        } else if (mode != GlyphStripMode.OFF && (mode != GlyphStripMode.RAIN || state.ambient.place != null)) {
            CenteredSupportingText(if (mode == GlyphStripMode.USAGE) "Данные о лимите ещё не загружены." else "Прогноз ещё не загружен.")
        }
    }
}

@Composable
private fun WeatherCard(settings: AmbientSettings, forecast: RainForecast?, busy: Boolean,
    onAddPlace: (String) -> Unit, onSelectPlace: (WeatherPlace) -> Unit, onRemovePlace: (WeatherPlace) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    // Retain the query after a failed lookup; clear it only when a city was added.
    LaunchedEffect(settings.place?.id, busy) {
        if (!busy && submitted != null && (settings.place?.id ?: "") != submitted) {
            query = ""
            submitted = null
        }
    }
    val add = {
        if (!busy && query.isNotBlank()) {
            submitted = settings.place?.id ?: ""
            onAddPlace(query.trim())
            focus.clearFocus()
        }
    }
    SectionCard {
        Text("Погода и города", style = MaterialTheme.typography.titleMedium)
        if (forecast != null && settings.place != null) {
            Text("${forecast.probabilityPercent}%", style = MaterialTheme.typography.displaySmall)
            Text(settings.place.name, style = MaterialTheme.typography.titleSmall)
            SupportingText("Вероятность осадков в ближайшие 3 часа · " + UsageFormat.updatedAt(forecast.fetchedAtMillis))
        } else SupportingText(if (settings.place == null) "Добавьте город, чтобы включить прогноз осадков." else "Прогноз для ${settings.place.name} ещё не загружен.")
        settings.places.forEach { place ->
            val chosen = settings.place?.id == place.id
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).selectable(selected = chosen, enabled = !busy, role = Role.RadioButton,
                    onClick = { submitted = null; onSelectPlace(place) }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(chosen, onClick = null, enabled = !busy)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(place.name, style = MaterialTheme.typography.bodyMedium)
                        if (chosen) SupportingText("Выбран для всех погодных каналов")
                        if (settings.places.count { it.name == place.name } > 1)
                            SupportingText("%.3f, %.3f".format(place.latitude, place.longitude))
                    }
                }
                IconButton(onClick = { submitted = null; onRemovePlace(place) }, enabled = !busy) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Удалить ${place.name}", modifier = Modifier.size(20.dp))
                }
            }
        }
        OutlinedTextField(query, onValueChange = { query = it; submitted = null }, label = { Text("Добавить город") },
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }))
        OutlinedButton(onClick = add, enabled = !busy && query.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Добавить город")
        }
        SupportingText("Выбранный город общий для A, B и C. Прогноз обновляется раз в полчаса; геолокация не нужна.")
    }
}

@Composable
private fun IdleAlarmNotice() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(UsageForegroundService.canScheduleExact(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = UsageForegroundService.canScheduleExact(context) }
    if (allowed) return
    HorizontalDivider()
    CenteredSupportingText("Разрешите точные будильники, чтобы Android не задерживал включение подсветки во время сна.")
    OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        .setData(Uri.parse("package:" + context.packageName))) }, modifier = Modifier.fillMaxWidth()) { Text("Разрешить будильники") }
}
