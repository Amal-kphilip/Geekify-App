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

5. Configure git hooks (optional but recommended):

   ```bash
   git config core.hooksPath .githooks
   # or run:
   ./scripts/setup-hooks.sh
   ```

6. Commit using conventional commit prefixes with one change per commit.

   ```bash
   git commit -m "feat(scope): concise description"
   ```

7. Push your branch and open a pull request.

   ```bash
   git push origin feature/your-feature
   ```

## Commit conventions and release notes

Geekify uses conventional commit prefixes to automatically generate structured changelogs for tagged releases:

- `feat:` or `feat(scope):` for new features (mapped to **What's New**).
- `fix:` or `fix(scope):` for bug fixes (mapped to **Bug Fixes**).
- `perf:`, `refactor:`, `style:`, or `improve:` for performance, architecture and UX improvements (mapped to **Improvements**). Unprefixed commits also default to Improvements.
- `chore:`, `docs:`, `test:`, `ci:`, and `build:` are internal and skipped from release notes.

Please keep **one change per commit** with a concise, user-facing summary. The `.githooks/commit-msg` hook will check your commit subject and print a helpful warning if the prefix is missing or unrecognized (it will not block your commit).

### Release notes override
If a release requires manually curated release notes instead of automated generation from commits, add `release-notes/vX.Y.Z.md` (e.g. `release-notes/v2.7.8.md`) to the repository. The release workflow will use this file verbatim for the GitHub Release and the in-app update sheet.

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

## Community, security, and releases

- **Code of Conduct:** Please follow our [Code of Conduct](CODE_OF_CONDUCT.md) in all interactions.
- **Security:** Report vulnerabilities privately via [SECURITY.md](SECURITY.md) rather than public issues.
- **Releases:** Releases are cut from version tags (`v*`) by the maintainer.
