# Releasing PhairPlay (Google TV)

PhairPlay ships **one APK, for Google TV** (Android TV OS 10+; developed and tested against
Google TV 4K running Android TV OS 14). Releases are built and published by GitHub Actions —
**merging to `main` is enough**. No tag, no manual release, no Pages switch.

## Where is the APK?

| You want | Get it here |
|----------|-------------|
| **The newest build, always** | <https://github.com/2archiver/phairplay-archiver-fork-/releases/download/latest/PhairPlay-googletv.apk> |
| Its checksum | <https://github.com/2archiver/phairplay-archiver-fork-/releases/download/latest/SHA256SUMS.txt> |
| What that build actually is (version, versionCode, commit) | <https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest> |
| A specific numbered version | <https://github.com/2archiver/phairplay-archiver-fork-/releases/download/v1.2.0/PhairPlay-googletv.apk> |
| Any release, newest first (`/releases/latest/…` redirects here) | <https://github.com/2archiver/phairplay-archiver-fork-/releases/latest/download/PhairPlay-googletv.apk> |
| A quick debug APK without waiting for a release | **Actions → CI** on any push/PR → artifact `debug-apk-googletv` |
| One you built yourself | `./gradlew :app:assembleGoogletvRelease` → `app/build/outputs/apk/googletv/release/app-googletv-release.apk` |
| The pretty download page (optional) | <https://2archiver.github.io/phairplay-archiver-fork-/> |

Both release links serve a file named `PhairPlay-googletv.apk`, so Downloader and `adb install -r`
work with either. The `latest` tag link is the one to bookmark: it is rebuilt on every merge to
`main`, whereas `/releases/latest/…` follows whichever release GitHub considers newest.

## How publishing works

`.github/workflows/release.yml` runs one job in two modes:

| Trigger | Mode | Release | Assets |
|---------|------|---------|--------|
| push / merge to `main` | rolling | tag `latest` (moved to the new commit, assets replaced in place), titled `PhairPlay v<base> — latest build (Google TV)` so the version is visible on the releases page | `PhairPlay-googletv.apk`, `SHA256SUMS.txt` |
| push a `v*` tag | versioned | permanent release for that tag | `PhairPlay-<tag>-googletv.apk`, `PhairPlay-googletv.apk`, `SHA256SUMS.txt` |
| **Actions → Release → Run workflow** | either | enter `latest`, or a tag such as `v1.2.0` (created from the selected branch if missing) | as above |

Tags containing a dash (`v1.2.0-beta.1`) are published as pre-releases. A failed run writes the
last 150 lines of the Gradle output into the job summary, so you can see why without downloading
runner logs.

### Publish a numbered release

```bash
# 1. bump the base version (single source of truth)
sed -i 's/^phairplay.versionName=.*/phairplay.versionName=1.3.0/' gradle.properties
# 2. update CHANGELOG.md, commit, merge to main
git tag v1.3.0
git push origin v1.3.0
```

That's it — the workflow builds and publishes. `phairplay.versionName` in `gradle.properties`
also drives rolling builds from `main` (published as `1.3.0-main.<run number>`).

### Version numbering

- **versionName** — `gradle.properties` (`phairplay.versionName`) for the base; CI passes
  `<base>-main.<run number>` for rolling builds and the tag's version for `v*` releases.
  The `googletv` flavor appends `-googletv`.
- **versionCode** — minutes since 2024-01-01T00:00:00Z. It grows with *every* build, on both
  paths, so a new APK always updates the installed one instead of being rejected as a downgrade.
  A local `./gradlew` build derives the same way; `-Pphairplay.versionCode=…` overrides it.

  A version-derived code (`1.2.3` → `10203`) cannot do this: rolling builds from `main` would
  outrank or collide with the numbered release of the same version.

## Sign releases (do this once)

Android only lets a new APK update an old one if both are signed with the same key. Without
secrets the workflow signs with a throw-away debug key that differs on every runner: it installs
fine, but the TV will demand an uninstall before taking the next build.

```bash
keytool -genkey -v -keystore phairplay-release.jks -alias phairplay \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 phairplay-release.jks   # macOS: base64 -i phairplay-release.jks
```

Add these in **Settings → Secrets and variables → Actions**: `KEYSTORE_BASE64` (output above),
`KEYSTORE_PASSWORD`, `KEY_ALIAS` (`phairplay`), `KEY_PASSWORD`. Keep the `.jks` backed up and
**never commit it**. Once they exist, every rolling build updates in place.

## The download page

`site/index.html` is a static page with a big **Download APK** button, Downloader/ADB install
steps, and a list of all releases. It reads the release list from the GitHub API in the browser,
so publishing a release updates it automatically, and its button points at the rolling `latest`
APK even if the API call fails.

One-time setup: **Settings → Pages → Build and deployment → Source: GitHub Actions**. The
`Releases page` workflow passes `enablement: true`, so a run from `main` flips that switch for
you; if the token lacks the right, set it by hand once. **The APK download does not depend on
Pages at all** — the release links above work regardless.

## Local release build

```bash
./gradlew :app:assembleGoogletvRelease \
  -Pphairplay.versionName=1.2.0 -Pphairplay.versionCode=10200
# app/build/outputs/apk/googletv/release/app-googletv-release.apk
```

Set `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` to sign with your release
key. Omit the `-P` flags to use the base version from `gradle.properties` and a clock-derived
versionCode.
