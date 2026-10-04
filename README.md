<div align="center">

<img src="docs/icon/icon.png" alt="Metrolist app icon" width="200" />

# Geekify

<br/>

[![Latest release](https://img.shields.io/github/v/release/Amal-kphilip/Geekify-App?style=for-the-badge&labelColor=0d1117)](https://github.com/Amal-kphilip/Geekify-App/releases)
[![License](https://img.shields.io/github/license/Amal-kphilip/Geekify-App?style=for-the-badge&labelColor=0d1117)](https://github.com/Amal-kphilip/Geekify-App/blob/main/LICENSE)
[![Download APK](https://img.shields.io/badge/Download-APK-0d1117?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Amal-kphilip/Geekify-App/releases/latest/download/Geekify-latest.apk)

<br/>

[**Download**](#download-now) · [**Features**](#features) · [**Tech Stack**](#tech-stack) · [**Local Setup**](#run-locally) · [**Roadmap**](#roadmap)

</div>

> [!NOTE]
> **Geekify** is an independent personal and educational project. It is not affiliated with, authorized, or endorsed by YouTube, YouTube Music, Spotify, Google LLC, or any of their respective owners.

---

<div align="center">

<h1><a id="screenshots"></a>Screenshots</h1>

<img src="docs/screenshots/home.jpg" alt="Geekify home screen" width="22%" />
<img src="docs/screenshots/now-playing.jpg" alt="Geekify now playing screen" width="22%" />
<img src="docs/screenshots/search.jpg" alt="Geekify search screen" width="22%" />
<img src="docs/screenshots/library.jpg" alt="Geekify library screen" width="22%" />

</div>

---

<div align="center">

<h1><a id="features"></a>Features</h1>

<table>
  <tr>
    <td width="50%" valign="top">

#### Playback & Audio
- Background playback with MediaSession controls
- Persistent queue, shuffle, repeat, and seeking controls
- Full-screen Now Playing view
- In-app extractor health checks for stream diagnostics

</td>
    <td width="50%" valign="top">

#### Search & Discovery
- Search songs, albums, artists, and playlists
- Genre and language collections (Malayalam, Tamil, Hindi, Telugu, Punjabi)
- Personal mixes based on history, likes, and language preferences

</td>
  </tr>
  <tr>
    <td width="50%" valign="top">

#### Library & Playlists
- Liked songs and custom local playlists
- Local data persistence with Room database
- Track management: add any searchable track to Liked or playlists

</td>
    <td width="50%" valign="top">

#### Accounts & Sync
- Optional Firebase account sync (Email/Password or Google Sign-In)
- Cloud Firestore sync across devices
- Offline-first Guest Mode (no account required)

</td>
  </tr>
</table>

</div>

---

<div align="center">

<h1><a id="tech-stack"></a>Tech Stack</h1>

| Area | Technology |
| :--- | :--- |
| **Language & UI** | Kotlin, Jetpack Compose, Material 3 |
| **Playback** | Media3, ExoPlayer, MediaSessionService |
| **Music Data** | YouTube Music InnerTube, OkHttp, Kotlin Serialization |
| **Storage** | Room, DataStore |
| **Images** | Coil |
| **Accounts & Sync** | Firebase Authentication, Cloud Firestore, Credential Manager |
| **Build & CI** | Gradle, GitHub Actions |

</div>

---

<div align="center">

<h1><a id="download-now"></a>Download Now</h1>

<h2>Stable Release</h2>

<a href="https://github.com/Amal-kphilip/Geekify-App/releases/latest/download/Geekify-latest.apk">
  <img src="docs/badges/github-stable.svg" alt="Download latest stable APK" width="260" />
</a>

</div>

---

## Run locally

### Prerequisites

- Android Studio with JDK 17
- Android SDK 26 or newer
- An Android device or emulator

### Steps

1. **Clone the repository**

   ```bash
   git clone https://github.com/Amal-kphilip/Geekify-App.git
   cd Geekify-App
   ```

2. **Open & Sync**

   Open the project in Android Studio and let Gradle complete syncing.

3. **Build Debug APK**

   ```bash
   ./gradlew :app:assembleDebug
   ```

   The generated APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

4. **Optional: Firebase Setup**

   - Create/open a Firebase project.
   - Register Android app ID `com.geekify.android` and place `google-services.json` in `app/`.
   - Enable Email/Password and Google sign-in in Firebase Authentication.
   - Create Firestore rules restricting `users/{uid}` documents to their owners.
   - Register debug and release SHA-1 fingerprints.

> Guest mode runs locally without Firebase setup.

---

## Tests

Run the testing and compilation suites with:

```bash
./gradlew testDebugUnitTest
./gradlew :app:compileDebugKotlin
```

---

## Roadmap

- [ ] Improve device and network playback-resilience tests.
- [ ] Add automated lint, unit-test, and release checks on pull requests.
- [ ] Add Firebase Crashlytics and release-health monitoring.
- [ ] Improve offline-friendly queue and metadata caching.
- [ ] Expand accessibility coverage and large-screen layouts.
- [ ] Improve language-aware recommendations when metadata uses Latin script.
- [ ] Add contributor issue templates and `good first issue` tasks.

---

## Contributing

Contributions are welcome—bug fixes, accessibility improvements, test coverage, documentation, and UI polish are all appreciated!

1. Open an issue for substantial changes to align on direction first.
2. Fork the repository and create a dedicated topic branch.
3. Apply changes, update tests, and run local build checks.
4. Open a pull request with a clear description and screenshots (for UI updates).

*Please do not commit credentials, keystores, `google-services.json`, or generated build files.*

---

## Project Notes & Disclaimer

Audio URLs are resolved directly on the device and may change as upstream services evolve. If playback fails, run Geekify's **Health screen** first and include the message in a GitHub issue.

This project is **not affiliated with, authorized, or endorsed by** YouTube, YouTube Music, Spotify, or Google LLC. All product names, logos, and brands belong to their respective owners.

---

## License

Geekify is distributed under the [GNU General Public License v3.0](LICENSE).

**Made with ❤️ by [Amal K Philip](https://github.com/Amal-kphilip)**