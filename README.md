# Time Tables

Android app (Kotlin) that wraps the Nested Timetable web app in a WebView.
Everything works offline; your data stays on the phone.

## Get the debug APK
Open **Releases → Debug build (latest)** on this repo from your phone, download
`TimeTables-debug.apk` and install it (allow "Install unknown apps" for your browser).
Each push to `main` rebuilds it. Run history and 30-day artifacts are under the **Actions** tab.

## Layout
- `web/app.html` – the timetable web app (single file)
- `tools/make_index.py` – wraps it with a small Android shim into `app/src/main/assets/index.html`
- `app/` – Kotlin shell (`MainActivity.kt`): file chooser, saving files to Downloads/Time Tables,
  notifications, microphone/location permissions, Back button handling
- `.github/workflows/build.yaml` – builds the debug APK and publishes it

## Updating the web app
Replace `web/app.html` with the new version and push. CI regenerates `index.html` automatically.
To build locally: `python3 tools/make_index.py && gradle :app:assembleDebug`
(needs JDK 17 and the Android SDK).

## What the native layer adds
- **Alarms when the app is closed:** the page sends every upcoming box alarm (including repeats and snoozes),
  reminder, daily digest, wrap-up and Pomodoro end to `Scheduler.kt`, which wakes the phone with
  `AlarmManager.setAlarmClock` (exempt from battery restrictions). Alarms are re-armed after reboot, app update
  and clock or time-zone changes, and each notification has a Snooze button. While the app is on screen the page
  rings its own alarms, so nothing rings twice.
- **Speech:** `window.SpeechRecognition` is backed by Android's `SpeechRecognizer`, so voice quick-add, dictation
  into boxes and "Transcribe" work. Offline works when the language pack is downloaded
  (Settings > System > Languages > Voice input), and the "On-device" option asks for offline recognition.
- **Files:** backups, reports and exports of any size are saved to Downloads/Time Tables.
- **Links:** http(s), tel:, sms:, mailto: and geo: links open the right app.
- **Permissions:** microphone, camera, location and notifications are requested when first needed.

## Still true
- The screens are the web app's, running inside the Kotlin shell. They are not rewritten in Jetpack Compose.
- Location-based alarms are checked while the app is open. Fixing that needs Google Play Services geofencing.
- "Transcribe" on an existing recording plays it aloud and listens with the microphone, because Android's recogniser
  only accepts live microphone input from apps.
- On some phones (Xiaomi, Oppo, Vivo, Samsung "sleeping apps") the maker's battery saver can still stop alarms:
  set Time Tables to "Unrestricted" battery use.
