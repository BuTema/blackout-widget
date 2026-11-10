# Графіки світла (blackout-widget)

Android app with a free 2×2 home-screen widget that shows the power outage schedule from
[DTEK Odesa Oblast](https://www.dtek-oem.com.ua/ua/shutdowns).

The widget shows your own name for the place (for example "Дім"), the time of the last update, the
current state right below it (green "Світло Є" / red "Світла немає"), then the current period and the
next one in large type. Orange times mean power is on, gray means off.
If there are no outages today it says "Світло є весь день". Dark and light themes each have their own
background transparency slider. If a refresh fails, the last received schedule stays on screen and the
update time is marked with ⚠.

## Install

1. Open the **Releases** page of this repository and download `blackout-widget.apk` from the
   **Latest build** release (every push to `main` replaces it).
2. Open the file on the phone and allow installing from this source. Android 8.0+ is required.
3. Open the app and add a place (you can add several: home, parents, a dacha...). For each one choose how to find your queue: **By address** (type the street and house, the app
   suggests them from DTEK's own lists and checks that the address exists) or **By queue** (pick e.g.
   `6.2` yourself). Press **Save and refresh**, then **Add widget to home screen**.

Tap the widget to refresh it; tap the name to open the app on that place. Each widget shows one place:
when you add a widget the app asks which one (long-press a widget to change it later), or use
**Add a widget for this place** in the place's editor. The size of the period times can be changed in
Settings → Widget.

## In the app

- **Places** (Settings → My places): add, edit and delete addresses. With more than one, chips at the top of the
  schedule screen switch between them. Notifications can be turned on or off per place.
- **Schedule screen** with *Today* and *Tomorrow* tabs: the 24 hours as two rows of 12 cells (green = power
  on, red = off, a cell split in halves means a half-hour outage, the current hour is outlined), a countdown
  to the next change, and the list of periods with their length. Past periods are dimmed.
- **Notifications** (Settings → Notifications):
  Texts use "вимкнуть" for power-off and "повернуть" for power-on.
  - the schedule for a day changed;
  - tomorrow's schedule was published;
  - optional reminders **1 hour / 30 / 15 minutes before** power goes off or comes back, and/or at the
    moment of the change, separately for power-off and power-on.
  Use *Send a test notification* to check that they reach you. On Android 13+ the app asks for the
  notification permission the first time you switch something on.

## How it works

- DTEK's site is behind bot protection, so the app loads the schedule page in a hidden WebView and reads
  `DisconSchedule.fact` and calls the site's own `getHomeNum` ajax method from inside the page.
- Your queue (e.g. `GPV6.2`) is found from the address, or picked by hand (radio switch in Settings).
  Address suggestions come from DTEK's `getStreets` and `getHomeNum` calls, so names always match the site;
  the street list is cached on the phone for two weeks.
- Data refreshes every 30 minutes in the background (WorkManager); the widget repaints at :00 and :30 so
  periods switch on time.
- The widget is built with Jetpack Glance. Kotlin, minSdk 26.
- Change notifications compare each download with the previous one (same queue only, on/off hours only).
  Reminders use one exact alarm that is always armed for the next due reminder and re-armed after every
  refresh, settings change and reboot. A reminder that would arrive more than 10 minutes late is dropped.

## Build

CI (`.github/workflows/build-apk.yml`) runs the unit tests, builds the APK and publishes it as the Latest build release. Locally:
`./gradlew testDebugUnitTest assembleRelease` (JDK 17 and the Android SDK are needed).

The APK is signed with the public debug key in `app/debug.keystore` so that new builds install over old
ones. This is for sideloading only, not for the Play Store (the exact-alarm permission used for reminders,
`USE_EXACT_ALARM`, is restricted there to alarm and calendar apps).
