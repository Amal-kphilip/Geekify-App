# Security Policy

## Supported Versions

Only the latest stable release of **Geekify** is supported with security updates.

| Version | Supported |
| ------- | --------- |
| Latest release | :white_check_mark: |
| Older releases | :x: |

## Reporting a Vulnerability

Please report security vulnerabilities **privately** using GitHub Private Vulnerability Reporting:

- https://github.com/Amal-kphilip/Geekify-App/security/advisories/new

Do **not** open public GitHub issues, pull requests, or Discussions for security vulnerabilities.

### Response Expectations

Geekify is maintained by a single developer on a best-effort basis:

- **Acknowledgment:** within 72 hours.
- **Triage & status update:** within 7 days.
- **Fix & coordinated disclosure:** once a fix is verified and released, an advisory may be published after users have had a reasonable window to update.

## Scope

This policy covers:

- The **Android application** (`app/`)
- The **InnerTube module** (`innertube/`)
- The **recommendation backend** (`backend/`)

## Sensitive Materials Warning

Never post or attach any of the following in GitHub issues, pull requests, Discussions, or comments:

- Release signing keystores (`.jks`, `.keystore`) or signing passwords
- Firebase configuration (`google-services.json`)
- Firebase Cloud Messaging (FCM) service-account keys
- Account credentials, OAuth tokens, session cookies, or logs containing personal/account data

## Data, Network, and Firebase Architecture

- **Local data:** Library items, playlists, playback history, cached shelves, and recommendation logs/feedback are stored locally on the device using Room (`GeekifyDatabase`) and DataStore.
- **Guest Mode:** Works locally without creating an account or requiring Firebase setup.
- **Firebase (optional):** When configured, Geekify uses Firebase Authentication (Email/Password or Google Sign-In via Credential Manager), Cloud Firestore (`users/{uid}` sync across devices), and Firebase Cloud Messaging (subscribing to the `geekify_updates` topic for release notifications, with WorkManager as a fallback).
- **Network requests:** Audio metadata and stream URLs are resolved directly on the device via HTTPS requests to YouTube / YouTube Music InnerTube endpoints (and BotGuard/PoToken WebView requests) using OkHttp and the `innertube` module.
