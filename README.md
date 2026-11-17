# Pixel Island

A Material You / Material 3 Expressive "Dynamic Island" for Android 12+ (minSdk 31, targetSdk 35).

## Build

**Option A — GitHub (no local SDK needed)**
1. Push this folder to a new GitHub repo.
2. Actions → *Build APK* runs automatically.
3. Download `pixel-island-debug-apk` from the run's artifacts.

**Option B — Android Studio**
Open the folder, let Gradle sync, then *Build → Build APK(s)*.

**Option C — command line**
```bash
export ANDROID_HOME=~/Android/Sdk        # or write sdk.dir=... into local.properties
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: AGP 8.7.3 · Gradle 8.11.1 · Kotlin 2.0.21 · Compose BOM 2024.12.01 · JDK 17+.

## First run

1. Open **Pixel Island** and tap **Grant** on *Island overlay* → enable the Pixel Island accessibility service.
   - On Android 13+ a sideloaded APK shows "Restricted setting". Open **App info → ⋮ → Allow restricted settings**, then enable it again.
2. Tap **Grant** on *Media access* → allow notification access (needed for Spotify / YouTube Music metadata and controls).
3. Tap **Auto-detect cutout**, then fine-tune X / Y / width / height with the sliders.
4. Turn on **Demo player** to try the player, squiggly seekbar and waveform with no music playing.

## Gestures

| Gesture on the pill | Action |
|---|---|
| Tap / long-press | Expand to the card |
| Tap outside | Collapse |
| Swipe left / right (media playing) | Next / previous track |
| Swipe (nothing playing) | Hide for 1 minute |

## Architecture

```
app/src/main/java/com/pixelisland/
├── PixelIslandApp.kt                 init settings, battery, media
├── data/IslandSettings.kt            StateFlow + SharedPreferences settings store
├── media/
│   ├── MediaListenerService.kt       NotificationListenerService (grants session access)
│   ├── MediaRepository.kt            MediaSessionManager + MediaController, Palette, Demo player
│   └── MediaState.kt
├── overlay/
│   ├── IslandAccessibilityService.kt TYPE_ACCESSIBILITY_OVERLAY window over the status bar
│   ├── OverlayLifecycleOwner.kt      Lifecycle/ViewModelStore/SavedStateRegistry owner for ComposeView
│   ├── IslandRootLayout.kt           catches ACTION_OUTSIDE → collapse
│   └── IslandController.kt           expanded / snoozed UI state
├── system/SystemRepository.kt        battery level + charging
├── timer/TimerRepository.kt          stopwatch + countdown timer
└── ui/
    ├── island/IslandRoot.kt          idle / compact / split / expanded states
    ├── island/Components.kt          waveform, squiggly slider, morphing buttons, haptics
    ├── island/IslandIcons.kt
    ├── main/MainActivity.kt, SettingsScreen.kt, Permissions.kt
    └── theme/Theme.kt                dynamic (Monet) color schemes
```

The island is a single container whose size, corner radius (pill `height/2` ↔ card `36.dp`) and color
animate with `spring(DampingRatioLowBouncy, StiffnessMediumLow)`. When media is playing, the expanded card's
container and accent colors come from the album art via `androidx.palette`.
