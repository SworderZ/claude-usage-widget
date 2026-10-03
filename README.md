# tinyGlyph

![tinyGlyph](branding/tinyGlyph.svg)

tinyGlyph shows Claude and Codex usage in Android home-screen widgets. On Nothing
Phone (2a) and Phone (2a) Plus, the Glyph lights also show usage, rain probability,
and time with the screen off.

[Download the latest version](https://github.com/SworderZ/claude-usage-widget/releases/latest)

## Features

- Home-screen widgets show five-hour and weekly usage, reset times, and elapsed time.
- Choose the widget's AI provider independently from the account open in the app.
- Claude uses orange accents; GPT uses white accents on a dark background.
- Assign rain probability, screen-off time, or Off independently to channels A and B.
- Use strip C for AI usage, rain probability, or Off.
- Save multiple cities and switch between their weather forecasts.
- A persistent notification shows the current Glyph state and last update.

## Getting started

Install the APK from the latest release and open tinyGlyph. When updating, install
it over the existing app to keep your accounts, cities, widgets, and Glyph settings.

### Connect Claude

Choose Claude on the Limits tab and sign in. If the embedded sign-in page does not
work, use the manual-key option to open Claude in a browser and paste a `sessionKey`
or cookie header from your signed-in session.

Add the widget from your launcher's widget picker. Set its provider under Settings.
Tap the refresh button to update it; tap the provider name to open that account.

### Connect Codex

Choose GPT on the Limits tab and import `.codex/auth.json` from a computer where
Codex is signed in. The app stores the access token and account ID in encrypted
storage. When that token expires, import a fresh file.

The file contains account secrets. Delete the transferred copy after import and
keep it out of repositories and messages.

**The GPT option shows Codex usage limits.** A remaining-message counter for ordinary
ChatGPT conversations is not available in this app.

If your VPN routes selected apps, include tinyGlyph (`space.megaworld.claudeusage`).
Browser access alone does not confirm that tinyGlyph uses the same route. The
connection screen includes an OpenAI connectivity check and reports network or
access errors separately from an expired session.

## Glyph setup

Glyph indicators are supported on Nothing Phone (2a) and Phone (2a) Plus. The app
also runs on other Android phones, where the AI widgets remain available.

The current Glyph build uses developer access. Connect your phone to a computer
with ADB and enable it:

```sh
adb shell settings put global nt_glyph_interface_debug_enable 1
```

This permission expires after 48 hours. If the lights stop working, check the
service notification and renew developer access.

Open the Glyph tab and turn on the main switch. All channel settings are on this tab.

### Channels A and B

Each short channel has three choices:

| Mode | Behavior |
| --- | --- |
| Off | Keeps the channel dark. |
| Rain | Uses brightness to show rain probability for the selected city. |
| Idle | Starts glowing after the screen has been off for the chosen duration. |

Idle thresholds are 15, 30, 60, or 120 minutes. The light grows brighter after the
threshold and reaches full brightness at four times that duration. Turning the
screen on resets the timer; movement is not tracked.

Use either channel's five-second test button to check its light without waiting
for the timer. Allow exact alarms when prompted for more timely screen-off
indication while the phone sleeps.

### Strip C

Choose Usage, Rain, or Off. In Usage mode, the strip shows five-hour usage for the
provider selected on the Limits tab. In Rain mode, a 70% probability fills roughly
70% of the strip, including values below the short channels' 30% threshold.

The rendering options let you reverse the fill direction or use individual
segments if the default progress display looks wrong on your phone.

### Cities and weather

Add cities in the weather section and tap a saved city to select its forecast.
A newly added city becomes selected immediately. The selected city applies to
all channels using weather.

Each city keeps its own cached forecast. Switching shows its saved data immediately
when available and refreshes it when stale. Deleting the selected city chooses the
first remaining one; deleting the last city turns off weather indication.

Weather uses the highest hourly precipitation probability in the next three hours
from Open-Meteo. Successful forecasts are cached for 30 minutes; refresh timing
also depends on your chosen interval and Android's background restrictions.
No location permission or weather account is required.

## Requirements and limitations

- Android 8.0 or newer for the app and widgets; Android 14 or newer for Glyph.
- Claude and Codex usage endpoints are unofficial and can change without notice.
- Session expiry, network access, and service-side checks can interrupt updates.
  Cached values remain visible with a stale-data status.
- Glyph access depends on Nothing OS and its developer permission. The service
  notification displays access errors.
- Available refresh intervals are 5, 10, 15, 30, and 60 minutes. Short intervals
  use a persistent background notification and can consume more battery.

## Development

Use JDK 17 and Android SDK platform 35. Configure the SDK path in `local.properties`.
The Nothing Glyph SDK is bundled in `app/libs/glyph-matrix-sdk-2.0.aar`.

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds both variants and runs unit tests and lint on pushes to `main`
and pull requests. Debug APKs are available as workflow artifacts. A version tag
such as `v0.14.0` builds and publishes the signed release APK automatically.

For a new release, update `versionCode` and `versionName` in `app/build.gradle.kts`,
add concise English user-facing notes in `release-notes/vVERSION.md`, and push the
matching tag. Use the manual **Android design previews** workflow to capture actual
Compose screens and Glance widgets on Android 15 with regular and larger text.
Its fixtures are debug-only and never included in the release APK.

Only the APK is attached to the release. The publishing workflow can
also be run manually for an existing tag.

Release signing uses these repository Actions secrets: `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Keep
an independent backup of the signing key. Its certificate must match existing
installs; the packaging check rejects debug APKs and different signing identities.

To build a signed release locally, set `ANDROID_KEYSTORE_PATH`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`, then
run `./gradlew assembleRelease`. Without these variables the release variant is
unsigned, for CI validation only. Signing files and credentials must stay outside
the repository.

The app uses Kotlin, Compose, Glance, WorkManager, DataStore, and Tink. `UsageRepository`
feeds both the widgets and Glyph; `AppGraph` supplies shared dependencies. Credentials
are encrypted with an Android Keystore-backed key.

The package remains `space.megaworld.claudeusage` so tinyGlyph can update previous
versions. The vector logo is in [`branding/tinyGlyph.svg`](branding/tinyGlyph.svg).
