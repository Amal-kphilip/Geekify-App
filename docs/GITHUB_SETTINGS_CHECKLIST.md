# GitHub Settings Checklist — Geekify-App

> Run these steps **after** the `chore/repo-standards` PR is merged into `main` and CI is green, **except where noted as "do now"**.

---

## 1. Enable Security Features (do now, before merging)

### Via GitHub UI

1. Go to **Settings → Security** (or **Settings → Code security and analysis**).
2. Enable **Dependabot alerts** → click **Enable**.
3. Enable **Dependabot security updates** → click **Enable**.
4. Enable **Secret scanning** → click **Enable**.
5. Enable **Push protection** (under Secret scanning) → click **Enable**.
6. Under **Private vulnerability reporting**, click **Enable**.

### Via `gh` CLI

```bash
gh api -X PATCH /repos/Amal-kphilip/Geekify-App \
  -f security_and_analysis.dependabot_alerts.status=enabled \
  -f security_and_analysis.dependabot_security_updates.status=enabled \
  -f security_and_analysis.secret_scanning.status=enabled \
  -f security_and_analysis.secret_scanning_push_protection.status=enabled
```

> Private vulnerability reporting cannot be enabled via the REST API; use the UI.

---

## 2. Enable Discussions

1. Go to **Settings → General**.
2. Scroll to the **Features** section.
3. Tick **Discussions** → Save.

> The issue template config and SECURITY.md link to Discussions and to private advisories; Discussions must be enabled for those links to work.

---

## 3. Merge Settings

### Via GitHub UI

1. Go to **Settings → General → Pull Requests**.
2. Under **Allow merge commits**, **Allow squash merging**, **Allow rebase merging**:
   - **Uncheck** Allow merge commits.
   - **Check** Allow squash merging (set default commit message to **Pull request title and description**).
   - **Uncheck** Allow rebase merging.
3. Enable **Automatically delete head branches**.

### Via `gh` CLI

```bash
gh api -X PATCH /repos/Amal-kphilip/Geekify-App \
  -f allow_merge_commit=false \
  -f allow_squash_merge=true \
  -f allow_rebase_merge=false \
  -f delete_branch_on_merge=true
```

---

## 4. Repository About Box

### Via GitHub UI

1. On the repo home page, click the ⚙️ gear icon next to **About**.
2. Set a **Description** (e.g. "Geekify — an independent Android music player for YouTube Music").
3. Add **Topics**: `android`, `kotlin`, `jetpack-compose`, `music-player`, `exoplayer`, `innertube`.
4. Set a **Website** if applicable (e.g. your release page or a landing page URL).

### Via `gh` CLI

```bash
gh api -X PATCH /repos/Amal-kphilip/Geekify-App \
  -f description="Geekify — an independent Android music player for YouTube Music" \
  -f homepage="https://github.com/Amal-kphilip/Geekify-App/releases" \
  -f topics='["android","kotlin","jetpack-compose","music-player","exoplayer","innertube"]'
```

---

## 5. Create Labeler Labels

Create the labels that `labeler.yml` will apply. Run each command, or create them in **Settings → Labels**.

```bash
# app — changes to the Android app module
gh label create "app" --color "0075ca" --description "Changes in app/" --repo Amal-kphilip/Geekify-App

# innertube — changes to the innertube module
gh label create "innertube" --color "e4e669" --description "Changes in innertube/" --repo Amal-kphilip/Geekify-App

# backend — changes to the Python recommendation backend
gh label create "backend" --color "d93f0b" --description "Changes in backend/" --repo Amal-kphilip/Geekify-App

# docs — documentation and markdown changes
gh label create "docs" --color "0052cc" --description "Documentation changes" --repo Amal-kphilip/Geekify-App

# ci — CI/CD and GitHub Actions changes
gh label create "ci" --color "bfd4f2" --description "Changes to .github/ workflows" --repo Amal-kphilip/Geekify-App

# dependencies — dependency version bumps
gh label create "dependencies" --color "84b6eb" --description "Dependency updates" --repo Amal-kphilip/Geekify-App
```

> The `dependencies` label is already referenced in `dependabot.yml`; create it before the first Dependabot PR arrives.

---

## 6. Branch Ruleset on `main` (LAST — after CI is green on `main`)

> Do this **only after** the PR is merged, CI is passing on `main`, and the labeler labels exist.

### Via GitHub UI

1. Go to **Settings → Rules → Rulesets → New ruleset → New branch ruleset**.
2. Name: `Protect main`.
3. Enforcement: **Active**.
4. Target branches: **Include by pattern** → `main`.
5. Enable the following rules:
   - **Require a pull request before merging** → Required approvals: **0**.
   - **Require status checks to pass** → Add:
     - `Test, Lint and Assemble Debug`
     - `Backend: Python tests`
   - Tick **Require branches to be up to date before merging**.
   - **Block force pushes**.
   - **Restrict deletions**.
6. Click **Save changes**.

### Via `gh` CLI (REST API)

```bash
gh api -X POST /repos/Amal-kphilip/Geekify-App/rulesets \
  --input - <<'JSON'
{
  "name": "Protect main",
  "target": "branch",
  "enforcement": "active",
  "conditions": {
    "ref_name": {
      "include": ["refs/heads/main"],
      "exclude": []
    }
  },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false
      }
    },
    {
      "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": true,
        "required_status_checks": [
          { "context": "Test, Lint and Assemble Debug" },
          { "context": "Backend: Python tests" }
        ]
      }
    }
  ]
}
JSON
```

---

## 7. Optional Tag Ruleset (restrict `v*` tag creation)

Pushing a `v*` tag triggers the release workflow which signs and publishes an APK to users. Restrict this to admins only.

### Via GitHub UI

1. Go to **Settings → Rules → Rulesets → New ruleset → New tag ruleset**.
2. Name: `Restrict v* tags`.
3. Enforcement: **Active**.
4. Target tags: **Include by pattern** → `v*`.
5. Under **Bypass list**, add: **Repository admin** role.
6. Enable **Restrict creations**.
7. Click **Save changes**.

### Via `gh` CLI

```bash
gh api -X POST /repos/Amal-kphilip/Geekify-App/rulesets \
  --input - <<'JSON'
{
  "name": "Restrict v* tags",
  "target": "tag",
  "enforcement": "active",
  "bypass_actors": [
    { "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always" }
  ],
  "conditions": {
    "ref_name": {
      "include": ["refs/tags/v*"],
      "exclude": []
    }
  },
  "rules": [
    { "type": "creation" }
  ]
}
JSON
```

> `actor_id: 5` is the built-in **Admin** repository role. Verify this is correct for your org/account via `gh api /repos/Amal-kphilip/Geekify-App/roles`.

---

## 8. Android Signing Secrets

The following repository secrets power the release workflow (`.github/workflows/release.yml`). Go to **Settings → Secrets and variables → Actions** to manage them.

| Secret name | What it holds |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The release keystore (`.jks`) encoded as base64 |
| `ANDROID_KEYSTORE_PASSWORD` | The keystore store password |
| `ANDROID_KEY_ALIAS` | The key alias inside the keystore |
| `ANDROID_KEY_PASSWORD` | The key password |
| `GOOGLE_SERVICES_JSON_BASE64` | `app/google-services.json` encoded as base64 |
| `GEEKIFY_FCM_SERVICE_ACCOUNT_JSON` | Firebase FCM service-account JSON (optional; enables instant update push notifications) |

> [!CAUTION]
> **Keep an offline backup of your release keystore.** If the keystore is lost, you cannot sign a new APK with the same certificate, which means existing users on Android cannot install updates (Android enforces certificate continuity for app updates). Store the keystore file securely offline (e.g., encrypted external drive or password manager attachment) in addition to the GitHub secret.
>
> Never post or attach the keystore, its passwords, `google-services.json`, or service-account JSON in GitHub issues, pull requests, or Discussions.
