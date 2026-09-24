# aautoradio

Internet radio for Android and Android Auto. Streams the stations in `stations.tsv`.

- **Kotlin + Jetpack Compose + Material 3** (Material You dynamic color on Android 12+)
- **Media3** `MediaLibraryService`: foreground playback that keeps going with the screen off (CPU + Wi-Fi wake locks), a media notification, lock screen and Bluetooth controls
- **Android Auto**: Favorites / All stations / Genres browse tree with a tile grid, voice search ("play FIP on aautoradio"), a favorite (heart) button, next/previous station, resumes the last station
- **5-band graphic equalizer** (60 Hz · 230 Hz · 910 Hz · 3.6 kHz · 14 kHz, ±12 dB) with presets and a live response curve. It is implemented as an ExoPlayer `AudioProcessor`, so it sounds the same on every phone and also applies when playing through Android Auto
- **Audio effects** (in the equalizer sheet, each with a toggle): bass enhancer, stereo width, virtual surround and crossfeed for headphones, reverb (Room/Club/Hall), volume leveler, mono, and Android's own spatial audio. All the DSP is plain Kotlin in the same processor chain as the EQ, ending in a safety limiter. Headphone-only effects pause automatically while Android Auto is connected
- MP3/AAC Icecast and HLS streams, ICY "now playing" song titles, automatic reconnection with backoff (tunnels, cell handovers)

## Build

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
./gradlew installDebug         # with a device connected
```

Requires JDK 17+ and the Android SDK (compileSdk 37). The release build is signed with the debug key; set up a real signing config before publishing.

## Stations

Edit `stations.tsv` in the repo root (`name<TAB>url<TAB>genre<TAB>favorite(0/1)`). The build copies it into
`app/src/main/assets/`. The favorite column only seeds favorites on first launch; after that, favorites are stored by the app.

## Testing Android Auto without a car

1. Install **Desktop Head Unit (DHU)** from the SDK Manager (SDK Tools → Android Auto Desktop Head Unit Emulator).
2. On the phone, open Android Auto settings, tap the version 10× to enable developer mode, then choose ⋮ → *Start head unit server*.
3. Under developer settings, enable **Unknown sources**. Sideloaded apps only appear in Auto when this is on.
4. `adb forward tcp:5277 tcp:5277 && $ANDROID_HOME/extras/google/auto/desktop-head-unit`

## Layout

```
data/      Station, StationRepository (TSV parsing, favorites, last played)
audio/     Biquad (RBJ filters), EqStore / EffectsStore (settings), AudioEffectsProcessor (the chain), dsp/ (EQ + effect stages)
playback/  RadioService (ExoPlayer + MediaLibrarySession), MediaTree (Auto browse tree), Artwork
ui/        MainActivity, RadioViewModel (MediaController), RadioScreen, EqualizerPanel
```
