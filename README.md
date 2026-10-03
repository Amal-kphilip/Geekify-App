# Geekify Android

Native Android music streaming app (Kotlin, Jetpack Compose, Media3) that runs entirely on-device with zero intermediate servers. Directly communicates with YouTube Music (InnerTube) and proxies no audio through third-party backends.

---

## Architecture Overview

```
data/
  source/        MusicSource (interface) + YouTubeMusicSource (impl)
    innertube/   InnerTubeClient (OkHttp), request builders, response parsers
  model/         Track, Card, Shelf, SearchResponse, HomeResponse, Mix, etc.
  cache/         TtlLruCache (in-memory 15-minute TTL cache)
  local/         Room DB (liked tracks, history cap 40, local playlists) + DataStore preferences
  auth/          AuthRepository (Firebase Auth + friendly error handling)
  sync/          SyncRepository (Firestore 2-way cloud sync mirroring web cloudSync.ts)
domain/
  recommend/     Recommender.kt (pure Kotlin port of taste/mix recommendation engine)
player/
  StreamResolver (direct audio stream format picker & URL expiry resolver)
  QueueManager   (queue state, shuffle, repeat, DataStore persistence)
  PlaybackService (Media3 MediaSessionService + ExoPlayer foreground playback)
  PlayerController (UI control binding)
ui/
  theme/         Geekify violet/mint design tokens & dark theme
  components/    AuroraBackground canvas, GlassSurface, TrackRow, ShelfRow, CardItem
  home/          HomeScreen & HomeViewModel
  search/        SearchScreen & SearchViewModel
  library/       LibraryScreen, LikedScreen, PlaylistScreen & LibraryViewModel
  details/       ArtistScreen, CollectionScreen & DetailsViewModel
  player/        NowPlayingBar (mini player), ExpandedPlayerScreen, QueueScreen
  account/       AccountSheet & AccountViewModel
  health/        HealthScreen (in-app extractor & endpoint diagnostic check)
```

---

## Firebase Setup (Manual Steps)

To enable cloud synchronization of your liked tracks and playlists across devices:

1. Go to [Firebase Console](https://console.firebase.google.com/) and open your existing Geekify project (or create one).
2. Add an **Android Application**:
   - Package name: `com.geekify.android`
   - Register your debug SHA-1 fingerprint (run `./gradlew signingReport` to find it).
3. Download `google-services.json` and place it in the `app/` directory (replacing the placeholder).
4. In Firebase Authentication:
   - Enable **Email/Password** provider.
   - Enable **Google** sign-in provider.
5. In Firestore Database:
   - Ensure the `firestore.rules` from the web app are deployed (`users/{uid}` read/write permissions for authenticated users).

> **Note**: Guest mode works completely offline and requires no Firebase configuration. All liked songs and playlists persist locally in Room DB.

---

## Building the App

### Debug Build
```bash
./gradlew :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release Build (R8 Minification Enabled)
```bash
./gradlew :app:assembleRelease
```
Output: `app/build/outputs/apk/release/app-release-unsigned.apk`

## GitHub Releases and in-app updates

Push a tag such as `v0.1.1` to publish a GitHub Release. The included workflow builds a signed APK named `Geekify-v0.1.1.apk`, uploads it to that release, and keeps its Android version in sync with the tag. The app checks the latest release on launch and, when a newer version is available, can download and open the APK with Android's package installer.

Before publishing, add these GitHub Actions repository secrets:

- `ANDROID_KEYSTORE_BASE64` — your release keystore, Base64 encoded
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The APK must remain signed with the same key across releases or Android will reject the update. On Android 8+, users may also need to allow Geekify to install unknown apps when prompted.

### Run Unit Tests
```bash
./gradlew testDebugUnitTest
```

---

## Terms of Service & License Notice

- **Terms of Service**: This application is intended strictly for personal, educational, and experimental use. Directly accessing streams without the official YouTube clients may violate YouTube's Terms of Service.
- **Privacy**: No user telemetry, third-party trackers, or analytics are embedded in the app.

## Playback maintenance note

Audio URLs come from the InnerTube **VISIONOS** player client (`InnerTubeClient.playerStreams`) and are fetched
with the same User-Agent (`InnerTubeClient.PLAYER_USER_AGENT`). YouTube changes which clients work without a PO
token; if songs stop playing, open the Health screen to see the exact reason, then check yt-dlp's current
`visionos` client values and bump `VISIONOS_VERSION` in `InnerTubeClient.kt`.
