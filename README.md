# DnD Universe TV (Android TV app)

A thin fullscreen WebView shell around the dnd-universe **`/tv`** page. All the real work
happens on that page: pairing with a campaign via a code the GM approves, the live board,
initiative, music and chat toasts. The app adds only:

- a launcher entry on the Android TV home screen (`LEANBACK_LAUNCHER` + banner) and on regular launchers,
- music autoplay (`mediaPlaybackRequiresUserGesture = false`),
- screen kept on (`FLAG_KEEP_SCREEN_ON`) and immersive fullscreen,
- persisted cookies: the TV stays paired through the HttpOnly first-party cookie `dnd_display`,
  and the app flushes cookies to disk in `onPause`,
- the remote's Back button goes back in WebView history, or asks before exiting,
- an offline screen (when the main page fails to load or returns 5xx) with a D-pad-focusable
  **Retry** button and an automatic retry every 15 s.

The WebView user agent ends with `DndUniverseTV/<version>` so the page can detect the shell.

| Build type | Loads | Cleartext HTTP | Package |
|---|---|---|---|
| `release` | `https://dnd-universe.ru/tv` | blocked | `ru.dnduniverse.tv` |
| `debug` | `-PtvUrl=…` (default `http://192.168.1.100:5173/tv`) | allowed | `ru.dnduniverse.tv.debug` ("DnD Universe TV (dev)") |

Both build types can be installed on the same TV side by side.

## Requirements

- JDK 17 or newer (to run Gradle).
- Android SDK with `platforms;android-36` and a 36.x build-tools. Set `ANDROID_HOME` or put
  `sdk.dir=/path/to/Android/Sdk` in `local.properties` (gitignored). If the SDK licenses are
  accepted, AGP downloads missing packages by itself on the first build.
- Toolchain: Gradle 9.9.1 (wrapper), AGP 9.5.1 (built-in Kotlin), Kotlin 2.4.21, compileSdk/targetSdk 36, minSdk 23.
  No AndroidX dependencies: the app uses only platform APIs.

## Build

Debug build pointing to a frontend dev server on your LAN:

```bash
./gradlew assembleDebug -PtvUrl=http://192.168.1.50:5173/tv
```

The default `tvUrl` is in `gradle.properties`. To set it once for your machine, add
`tvUrl=http://…/tv` to `~/.gradle/gradle.properties`. The Vite dev server must listen on the
LAN (`vite --host`) and serve `/backend/*` on the same origin as `/tv`, as in production, or
the pairing cookie won't be first-party.

Release build (loads `https://dnd-universe.ru/tv`):

```bash
cp keystore.properties.example keystore.properties   # then fill it in
./gradlew assembleRelease
```

`keystore.properties` (gitignored) holds `storeFile`, `storePassword`, `keyAlias` and
`keyPassword`. `storeFile` is relative to this folder. To create a key:

```bash
keytool -genkeypair -v -keystore release.jks -alias dnd-universe-tv -keyalg RSA -keysize 4096 -validity 10000
```

If `keystore.properties` is missing, `assembleRelease` still builds, but the APK is unsigned
(`app-release-unsigned.apk`) and won't install.

APKs end up in `app/build/outputs/apk/{debug,release}/`.

## Sideload onto the TV

1. On the TV, turn on **Developer options**: Settings → Device preferences → About → press
   *Build* 7 times. Then turn on **USB debugging** / **Network debugging** under Developer options.
   Wording varies by vendor.
2. Connect and install (the TV shows an "Allow debugging?" prompt the first time):

```bash
adb connect 192.168.1.60:5555
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

On Android 11+ with wireless debugging, pair first with `adb pair <tv-ip>:<pair-port>` using the
code shown on the TV, then `adb connect` to the port it lists.

3. Start the app from the home screen ("DnD Universe TV"), or from the command line:

```bash
adb shell am start -n ru.dnduniverse.tv/.MainActivity
```

Debug builds allow remote inspection: open `chrome://inspect` in desktop Chrome while the TV is
connected over adb.

## Known risks

- **YouTube in a WebView.** Music played through the YouTube IFrame player may be blocked:
  YouTube sometimes refuses embedded playback inside Android WebViews (error 150/152/153, or
  "sign in to confirm you're not a bot"), and autoplay with sound is not guaranteed there.
  Uploaded and built-in music (plain `<audio>` from our own origin) is not affected and
  autoplays normally.
- **Outdated system WebView.** Old TVs often ship an old Android System WebView. If the page
  renders badly, update "Android System WebView" (or "Chrome") from the Play Store on the TV.
- **Back button.** `/tv` is a single-page app, so `goBack()` follows its history entries
  (`pushState`) before it asks whether to exit.

## Layout

```
app/src/main/java/ru/dnduniverse/tv/MainActivity.kt   the whole app
app/src/main/res/layout/activity_main.xml             WebView + offline overlay
app/src/main/res/xml/network_security_config.xml      release: HTTPS only
app/src/debug/res/xml/network_security_config.xml     debug: cleartext allowed
app/src/main/res/drawable-xhdpi/banner.png            320x180 TV banner (source: art/banner.svg)
```

To regenerate the banner after editing `art/banner.svg`:

```bash
rsvg-convert -w 320 -h 180 art/banner.svg -o app/src/main/res/drawable-xhdpi/banner.png
```
