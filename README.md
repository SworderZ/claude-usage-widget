# Claude Usage Widget

Виджет на рабочий стол Android, показывающий текущий расход лимитов подписки claude.ai:
5-часовое и недельное окно в процентах и время до сброса. Данные берутся с сервера,
а не из локальных логов.

## Сборка

Нужны JDK 17 и Android SDK с платформой `android-35`.

```
./gradlew assembleDebug
```

APK окажется в `app/build/outputs/apk/debug/app-debug.apk`.

Путь к SDK берётся из `local.properties` (`sdk.dir`). На этой машине toolchain лежит на `E:`,
поэтому для сборки из свежей консоли:

```
export JAVA_HOME=E:/android-tools/jdk17
export ANDROID_HOME=E:/android-tools/sdk
export GRADLE_USER_HOME=E:/android-tools/gradle-home
./gradlew assembleDebug
```

Тесты и линт: `./gradlew testDebugUnitTest lintDebug`.

## Как пользоваться

1. Запустить приложение, нажать «Войти» — откроется WebView с обычной страницей входа
   claude.ai. После успешного логина приложение само подхватит cookie и закроет WebView.
2. Добавить виджет на рабочий стол. Поддерживаются два размера:
   2x1 (только проценты) и 4x2 (полосы прогресса, время до сброса, время обновления).
3. Тап по виджету — немедленное обновление. Если сессии нет или она протухла,
   тап открывает экран логина.

Цвет полосы: до 70 % обычный, 70–90 % жёлтый, свыше 90 % красный.
Когда данные устарели (нет сети или истекла сессия), последние значения показываются
приглушённо, а в подписи появляется причина.

## Архитектура

```
data/     ApiClient, модели, UsageRepository (сеть + кеш), CredentialStore, SettingsStore
worker/   UsageRefreshWorker — периодическое и разовое обновление
widget/   UsageWidget (Glance), UsageWidgetReceiver, RefreshWidgetAction
ui/       MainActivity, MainScreen, SettingsScreen, LoginActivity, форматирование
```

Слой данных не зависит от Compose/Glance: тот же `UsageRepository` позже сможет кормить
индикацию Glyph Interface на Nothing Phone (2a) без изменений.

DI ручной — `AppGraph`, ленивый синглтон. Hilt/Retrofit намеренно не используются.

### Обновление

`PeriodicWorkRequest` с уникальным именем и constraint «есть сеть»; интервал берётся
из настроек (15/30/60 минут, меньше 15 WorkManager не разрешает). `updatePeriodMillis`
в `appwidget-provider` равен 0 — системный таймер провайдера не используется.
Перезагрузку периодическая работа переживает сама: WorkManager подключает свой
`RescheduleReceiver` на `BOOT_COMPLETED` (видно в merged manifest).

### Хранение сессии

Cookie и User-Agent лежат в DataStore, зашифрованные AEAD-ключом (AES256-GCM) из Tink,
мастер-ключ которого живёт в Android Keystore. `EncryptedSharedPreferences` не
используется — он deprecated.

Хранится **весь** набор cookie домена claude.ai, а не только `sessionKey`: без
`cf_clearance` и совпадающего с WebView User-Agent Cloudflare отдаёт челлендж вместо JSON.

## Известные ограничения

- **API неофициальный.** `GET /api/organizations` и `GET /api/organizations/{uuid}/usage`
  не документированы и могут измениться или исчезнуть в любой момент. Разбор ответа
  сделан нестрого: неизвестные ключи игнорируются, окном лимита считается любой
  вложенный объект с полем `utilization`, отсутствие `resets_at` не ошибка. Поэтому
  новые окна (`seven_day_opus` и подобные) появятся в приложении сами — на главном
  экране; в виджете по-прежнему только `five_hour` и `seven_day`.
- **Cloudflare.** Запрос может вернуть HTML-страницу проверки вместо JSON. Такой ответ
  (как и 401/403) трактуется как «нужен перелогин», а не как сетевая ошибка.
- **Сессия протухает.** Срок жизни cookie claude.ai не контролируется приложением;
  рано или поздно потребуется повторный вход через WebView. Автоматического продления нет.
- **Проценты берутся как есть.** Предполагается, что `utilization` приходит в диапазоне
  0–100; значение только зажимается в эти границы. Если формат сменится на долю 0–1,
  проценты станут показываться неправильно.
- **Не проверено на устройстве.** Сборка, юнит-тесты и линт проходят; поведение WebView,
  Cloudflare и реальные ответы API на железе не тестировались.
- Релизная сборка идёт без R8 (`isMinifyEnabled = false`): правила для Glance,
  WorkManager и Tink не выверялись, выпускать такой APK не стоит.
