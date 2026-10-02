package space.megaworld.claudeusage.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import space.megaworld.claudeusage.R
import space.megaworld.claudeusage.data.AmbientSettings
import space.megaworld.claudeusage.data.SettingsStore
import space.megaworld.claudeusage.data.GlyphRenderMode
import space.megaworld.claudeusage.data.RainForecast
import space.megaworld.claudeusage.data.UsageState
import space.megaworld.claudeusage.data.WeatherPlace
import space.megaworld.claudeusage.glyph.GlyphSupport
import space.megaworld.claudeusage.glyph.UsageForegroundService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UsageState,
    busy: Boolean,
    onBack: () -> Unit,
    onSelectOrganization: (String) -> Unit,
    onSelectInterval: (Int) -> Unit,
    onReloadOrganizations: () -> Unit,
    onToggleGlyph: (Boolean) -> Unit,
    onSelectGlyphMode: (GlyphRenderMode) -> Unit,
    onToggleIdle: (Boolean) -> Unit,
    onSelectIdleMinutes: (Int) -> Unit,
    onToggleRain: (Boolean) -> Unit,
    onSelectPlace: (String) -> Unit,
    onClearPlace: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = "Назад",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(text = "Организация", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (state.organizations.isEmpty()) {
                Text(
                    text = "Список пуст — войдите в claude.ai или обновите список.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                state.organizations.forEach { organization ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = organization.uuid == state.organizationUuid,
                                enabled = !busy,
                                onClick = { onSelectOrganization(organization.uuid) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = organization.uuid == state.organizationUuid,
                            onClick = { onSelectOrganization(organization.uuid) },
                            enabled = !busy,
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = organization.name ?: organization.uuid,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = organization.uuid,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onReloadOrganizations, enabled = !busy) {
                Text(text = "Обновить список")
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Интервал обновления", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = """
                    15 минут и реже — обычное фоновое обновление, ничего не висит
                    в шторке.

                    5 и 10 минут столько не ждут: такие интервалы WorkManager не
                    принимает, их обслуживает фоновая служба, а ей нужно постоянное
                    уведомление. Расход батареи выше, и сервер опрашивается чаще —
                    API неофициальный, злоупотреблять им не стоит.
                """.trimIndent(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsStore.ALLOWED_INTERVALS.forEach { minutes ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { onSelectInterval(minutes) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = minutes == state.refreshIntervalMinutes,
                        onClick = { onSelectInterval(minutes) },
                        enabled = !busy,
                    )
                    Text(
                        text = "$minutes мин",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            ExactAlarmNotice(intervalMinutes = state.refreshIntervalMinutes)

            // Настройка есть только там, где есть сама полоса.
            if (GlyphSupport.isAvailable) {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                GlyphSetting(
                    enabled = state.glyphEnabled,
                    mode = state.glyphRenderMode,
                    busy = busy,
                    onToggle = onToggleGlyph,
                    onSelectMode = onSelectGlyphMode,
                )

                // Каналы A и B — часть той же сессии Glyph, поэтому без полосы
                // они не работают и настраивать их нечего.
                if (state.glyphEnabled) {
                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                    AmbientSetting(
                        settings = state.ambient,
                        forecast = state.rainForecast,
                        busy = busy,
                        onToggleIdle = onToggleIdle,
                        onSelectIdleMinutes = onSelectIdleMinutes,
                        onToggleRain = onToggleRain,
                        onSelectPlace = onSelectPlace,
                        onClearPlace = onClearPlace,
                    )
                }
            }
        }
    }
}

/**
 * Короткие каналы A и B.
 *
 * Процент на одиночном канале не покажешь, поэтому каждому досталось состояние:
 * A — сколько телефон лежит нетронутым, B — ждать ли дождя. Отличаются они
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
            text = "Два коротких канала рядом с полосой C. Когда любой из них " +
                "включён, полоса C рисуется сегментами вручную: подмешать A и B " +
                "в родной прогресс SDK нельзя, он занимает кадр целиком.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "A: телефон не трогали", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Канал тлеет после порога и разгорается дальше. Отсчёт " +
                        "идёт с того момента, как погас экран.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.idleEnabled, onCheckedChange = onToggleIdle, enabled = !busy)
        }

        if (settings.idleEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
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
                Прогноз берётся у Open-Meteo: без ключа, без регистрации и без
                доступа к геолокации — место задаётся названием, координаты
                сервис отдаёт сам. Обновляется не чаще раза в полчаса, на три
                часа вперёд осадки чаще не пересматривают.
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

/**
 * Без разрешения на точные будильники система в Doze прореживает их примерно до
 * одного срабатывания в 9–15 минут, и короткий интервал при спящем экране не
 * соблюдается. Показываем, только когда это действительно мешает.
 */
@Composable
private fun ExactAlarmNotice(intervalMinutes: Int) {
    val context = LocalContext.current
    if (intervalMinutes >= UsageForegroundService.WORKMANAGER_FLOOR_MINUTES) return
    if (UsageForegroundService.canScheduleExact(context)) return

    Spacer(modifier = Modifier.height(16.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Интервал будет соблюдаться не всегда",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "При выключенном экране Android растягивает обновление примерно " +
                    "до 9–15 минут. Чтобы интервал соблюдался, разрешите приложению " +
                    "точные будильники.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:" + context.packageName))
                        )
                    }
                },
            ) {
                Text(text = "Разрешить")
            }
        }
    }
}

@Composable
private fun GlyphSetting(
    enabled: Boolean,
    mode: GlyphRenderMode,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onSelectMode: (GlyphRenderMode) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Glyph: полоса C", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Показывать 5-часовое окно на полосе C, в том числе в фоне",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle, enabled = !busy)
        }

        // Как именно полоса заполняется, зависит от прошивки — вариант подбирается глазами.
        if (enabled) {
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

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = """
                Пока полоса горит, в шторке висит служебное уведомление — без него
                Android выгрузит индикацию через пару минут. То же уведомление
                обслуживает интервалы обновления короче 15 минут.

                Nothing пишет, что Glyph доступен только приложению на переднем плане,
                но на Phone (2a) фоновая служба эту проверку проходит. На другой
                прошивке может и отказать — тогда причина будет в том же уведомлении.

                С отладочным ключом нужно один раз выполнить с компьютера:
                adb shell settings put global nt_glyph_interface_debug_enable 1
                Разрешение сбрасывается через 48 часов.
            """.trimIndent(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
