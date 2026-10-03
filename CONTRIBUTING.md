# Contributing to Geekify

Thanks for helping make Geekify better. Contributions to the Android app, tests, documentation, and design are welcome.

## Before you start

- Search existing [issues](https://github.com/Amal-kphilip/Geekify-App/issues) before opening a new one.
- Open an issue or start a discussion before working on a large feature, architectural change, or dependency migration.
- Do not include account credentials, private tokens, keystores, `google-services.json`, or downloaded media in a commit.

## Development setup

1. Fork the repository and clone your fork.

   ```bash
   git clone https://github.com/YOUR-USERNAME/Geekify-App.git
   cd Geekify-App
   ```

2. Create a focused branch.

   ```bash
   git checkout -b feature/your-feature
   ```

3. Open the project in Android Studio, allow Gradle to sync, then make your change.

4. Run the relevant checks.

   ```bash
   ./gradlew testDebugUnitTest
   ./gradlew :app:compileDebugKotlin
   ```

5. Commit using a clear, imperative message.

   ```bash
   git commit -m "Add: concise description"
   ```

6. Push your branch and open a pull request.

   ```bash
   git push origin feature/your-feature
   ```

## Pull request checklist

- Keep the change focused; avoid unrelated formatting or refactors.
- Explain the problem and how you verified the solution.
- Include screenshots or a short recording for visible UI changes.
- Add or update tests when the logic is testable.
- For player changes, test on a real device: play/pause, seeking, screen-off playback, notification controls, a slow network, and app restart.

## Good first contributions

- Improve empty states, loading states, and error messages.
- Fix a focused UI or accessibility issue.
- Add unit tests for parsing, recommendation, or queue behavior.
- Improve setup and troubleshooting documentation.
- Improve playback resilience or performance with a reproducible test case.

## Code style

- Follow the existing Kotlin and Compose conventions in the surrounding code.
- Prefer small composables and ViewModel methods with clear responsibilities.
- Keep network and disk work off the main thread.
- Preserve the app's dark UI design system and accessible content descriptions.
