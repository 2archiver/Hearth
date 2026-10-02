# Releasing PhairPlay (Google TV)

PhairPlay ships **one APK, for Google TV** (Android TV OS 10+; developed and tested against Android 14).
Releases are built and published by GitHub Actions — you only push a tag.

## Publish a release

```bash
git tag v1.2.0
git push origin v1.2.0
```

`.github/workflows/release.yml` then:

1. builds `:app:assembleGoogletvRelease` (version name/code come from the tag: `v1.2.3` → `1.2.3` / `10203`),
2. creates a GitHub Release with auto-generated notes and these files:

| File | Purpose |
|------|---------|
| `PhairPlay-googletv.apk` | **The one to download.** Fixed name, so `https://github.com/2archiver/phairplay-archiver-fork-/releases/latest/download/PhairPlay-googletv.apk` always points at the newest release |
| `PhairPlay-vX.Y.Z-googletv.apk` | Same APK, versioned name |
| `SHA256SUMS.txt` | Checksums |

Tags containing a dash (`v1.2.0-beta.1`) are published as pre-releases. You can also run the
workflow by hand from **Actions → Release → Run workflow**.

## Sign releases (do this once)

Android only lets a new APK update an old one if both are signed with the same key. Without
secrets the workflow signs with a throw-away debug key (installs fine, but you'd have to uninstall
before installing a release signed with a different key).

```bash
keytool -genkey -v -keystore phairplay-release.jks -alias phairplay \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 phairplay-release.jks   # macOS: base64 -i phairplay-release.jks
```

Add these in **Settings → Secrets and variables → Actions**: `KEYSTORE_BASE64` (output above),
`KEYSTORE_PASSWORD`, `KEY_ALIAS` (`phairplay`), `KEY_PASSWORD`. Keep the `.jks` backed up and
**never commit it**.

## The releases page

`site/index.html` is a static page (served by GitHub Pages) with a big **Download APK** button,
Downloader/ADB install steps, and a list of all releases. It reads the latest release from the
GitHub API in the browser, so publishing a release updates it automatically.

One-time setup: **Settings → Pages → Build and deployment → Source: GitHub Actions**.
After the `Releases page` workflow runs, it lives at
<https://2archiver.github.io/phairplay-archiver-fork-/>.

## Local release build

```bash
./gradlew :app:assembleGoogletvRelease \
  -Pphairplay.versionName=1.2.0 -Pphairplay.versionCode=10200
# app/build/outputs/apk/googletv/release/app-googletv-release.apk
```
Set `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` to sign with your release key.
