# AirPods Companion

Native Android app for using Apple AirPods more comfortably on Android phones.

## Plan

- Give the user one screen that clearly says whether AirPods are paired, connected, or missing.
- Request only the Bluetooth permission needed for paired/connected device status on modern Android.
- Surface battery level when Android exposes it through the Bluetooth stack, and say "Battery unknown" when it does not.
- Provide a home-screen widget for left earbud, right earbud, and case battery when those split values are available.
- Show experimental in-ear, charging, and case-lid state when AirPods BLE frames expose enough information.
- Identify common AirPods and Beats models from reverse-engineered BLE model codes.
- Show likely active microphone side and whether one/both earbuds appear to be in the case.
- Show an optional case-open popup while the app is open.
- Offer opt-in experimental auto-pause/resume while the app is open, based on BLE in-ear state.
- Provide direct shortcuts to Bluetooth settings for pairing and Sound settings for output routing.
- Keep the app lightweight, local, and dependency-free.

## Design

- Dark skin is the first-launch default and the selected skin is saved.
- Top controls include moon, sun, and About buttons.
- Status is grouped into scan-friendly sections: overall state, profile state, audio state, devices, and checklist.
- Device rows prioritize likely Apple earbuds by names such as AirPods, Beats, Powerbeats, or Beats Fit.
- Widget values are saved locally so the widget can keep showing the most recent known battery state.
- The auto-pause switch is off by default because AirPods BLE ear-state can vary by model and firmware.
- The case-open popup is local to the running app; background popups would require a foreground scanning service.
- The launcher icon uses a dark AirPods-style mark with a green status accent.

## Android and AirPods limits

Android can pair and route audio to AirPods through normal Bluetooth audio profiles. Features tied to Apple's private ecosystem, such as automatic iCloud switching, full earbud/case battery breakdown, firmware management, and Apple-only spatial controls, are not available through public Android APIs.

This app reads public paired-device, profile, battery, and BLE advertisement signals. Some phones expose a single Bluetooth battery value for AirPods; some expose AirPods BLE manufacturer advertisements that include left/right/case battery in 10% steps; others do not expose battery at all.

Experimental ear detection and case-lid state are inferred from AirPods BLE status bytes. They are useful enough for display and opt-in media pause/resume, but they are not equivalent to Apple's private AirPods settings on iPhone.

Reverse-engineering references used for the current BLE feature set:

- CAPod source and documentation for public Android AirPods behavior and payload interpretation: `https://github.com/d4rken-org/capod`
- Apple Continuity proximity-pairing message notes: `https://github.com/furiousMAC/continuity/blob/master/messages/proximity_pairing.md`
- Academic continuity-protocol background: `https://petsymposium.org/popets/2020/popets-2020-0003.pdf`

## Build

```powershell
.\gradlew.bat --% :app:assembleDebug :app:lintDebug copySamsungDebugApk --no-daemon -Dorg.gradle.problems.report=false
```

The named APK is written to:

```text
artifacts/AirPodsCompanion-1.0.4-build5-arm64-v8a-debug.apk
```

## App Identity

- Package: `com.andre.airpodscompanion`
- Version: `1.0.4`
- Build: `5`
- Developer: Andrei Efremuahkin
- Email: `andrei.efr@gmail.com`
- Repo URL used in About: `https://github.com/efremandrei/AirPodsCompanion`
