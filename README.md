# Графіки світла (blackout-widget)

Android app with a free 2×2 home-screen widget that shows the power outage schedule from
[DTEK Odesa Oblast](https://www.dtek-oem.com.ua/ua/shutdowns).

The widget shows the current period (no power / power is on) and the next one, the time of the last
update, and your own name for the place (for example "Дім"). Orange means power is on, gray means off.
If there are no outages today it says "Світло є весь день". Dark and light themes each have their own
background transparency slider. If a refresh fails, the last received schedule stays on screen and the
update time is marked with ⚠.

## Install

1. Open the **Releases** page of this repository and download `blackout-widget.apk` from the
   **Latest build** release (every push to `main` replaces it).
2. Open the file on the phone and allow installing from this source. Android 8.0+ is required.
3. Open the app, enter your street and house number exactly as on the DTEK site (or just your queue,
   e.g. `6.2`), press **Save and refresh**, then **Add widget to home screen**.

Tap the widget to refresh it; tap the name to open the app.

## In the app

- **Schedule screen** with *Today* and *Tomorrow* tabs: the 24 hours as two rows of 12 cells (green = power
  on, red = off, a cell split in halves means a half-hour outage, the current hour is outlined), a countdown
  to the next change, and the list of periods with their length. Past periods are dimmed.
- **Notifications** (Settings → Notifications):
  - the schedule for a day changed;
  - tomorrow's schedule was published;
  - optional reminders **1 hour / 30 / 15 minutes before** power goes off or comes back, and/or at the
    moment of the change, separately for power-off and power-on.
  Use *Send a test notification* to check that they reach you. On Android 13+ the app asks for the
  notification permission the first time you switch something on.

## How it works

- DTEK's site is behind bot protection, so the app loads the schedule page in a hidden WebView and reads
  `DisconSchedule.fact` and calls the site's own `getHomeNum` ajax method from inside the page.
- Your queue (e.g. `GPV6.2`) is found from the address; you can also type it in manually.
- Data refreshes every 30 minutes in the background (WorkManager); the widget repaints at :00 and :30 so
  periods switch on time.
- The widget is built with Jetpack Glance. Kotlin, minSdk 26.
- Change notifications compare each download with the previous one (same queue only, on/off hours only).
  Reminders use one exact alarm that is always armed for the next due reminder and re-armed after every
  refresh, settings change and reboot. A reminder that would arrive more than 10 minutes late is dropped.

## Build

CI (`.github/workflows/build.yml`) runs the unit tests and builds the APK. Locally:
`./gradlew testDebugUnitTest assembleRelease` (JDK 17 and the Android SDK are needed).

The APK is signed with the public debug key in `app/debug.keystore` so that new builds install over old
ones. This is for sideloading only, not for the Play Store (the exact-alarm permission used for reminders,
`USE_EXACT_ALARM`, is restricted there to alarm and calendar apps).
