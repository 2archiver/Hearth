# Keeping PhairPlay up to date

Three ways, easiest first.

## 1. From the TV (PhairPlay 1.4+)

**Settings → Updates → Check for updates.**

PhairPlay asks GitHub what the newest published build is, compares **versionCode** (a number,
not a version string — `1.10.0` sorts before `1.9.0` as text), and if something newer exists
downloads it and offers to install.

| Setting | Default | What it does |
|---------|---------|--------------|
| **Check automatically** | on | Look for updates in the background, a few times a day, and post a notification when one is found |
| **Download automatically** | on | Fetch and verify the APK as soon as one is found, so installing is one tap |
| **Install automatically** | off | Install a verified update without another prompt. Turn this on for zero-tap updates |

Android 12+ normally lets an app replace **itself** without showing a confirmation dialog, so
with all three on the update is genuinely hands-off: PhairPlay updates itself and restarts.
On older Android, or if the TV's policy disagrees, the standard "Install?" confirmation is
shown. PhairPlay never installs anything you did not ask for in that case.

The first time, Android may need you to allow installs from PhairPlay:
**Settings → Apps → Special access → Install unknown apps → PhairPlay → Allow**.

## 2. Install the APK over the old one

```
https://github.com/2archiver/phairplay-archiver-fork-/releases/download/latest/PhairPlay-googletv.apk
```

Paste it into **Downloader** on the TV, or `adb install -r PhairPlay-googletv.apk` from a
computer. Android treats it as an update — **no uninstall** — because every PhairPlay build is
signed with the same key.

## 3. Let `adb` do it

```bash
adb install -r PhairPlay-googletv.apk
```

---

## "App not installed as package conflicts with an existing package"

This message means **the APK is signed with a different key than the installed app**. It is
about the key, never about the version.

It used to happen on every PhairPlay update, because builds published without signing secrets
fell back to a throw-away debug key that differed on every CI run. As of 1.4:

- Every build — CI or local, `debug` or `release` — is signed with
  `app/signing/phairplay.p12`, the public "community build" key committed to this repository
  ([the trade-off, stated plainly](RELEASING.md#signing-why-updates-install-in-place)).
- **The in-app updater refuses to install an APK whose certificate does not match the running
  app's**, so it can never hand your TV a build that would be rejected. If it ever finds one
  it says so and tells you what to do, instead of reproducing the error.
- The release workflow runs `apksigner verify` on every APK and prints the certificate
  fingerprint, so a build signed with an unexpected key is caught before it is published.

**If you still see it,** the APK you are installing came from somewhere that does not use this
key (a fork, or a build made with someone else's keystore). Either uninstall PhairPlay once and
install that APK — accepting that its future updates will need the same dance — or install a
build from this repository, which will update cleanly forever after.

---

## How the updater decides

1. `GET https://api.github.com/repos/2archiver/phairplay-archiver-fork-/releases/latest`
2. Download that release's `version.json` asset and read `versionCode`. (Falls back to
   scraping `versionCode <n>` out of the release notes for releases published before 1.4.)
3. Compare with the installed `BuildConfig.VERSION_CODE`. Higher = update available.
4. Download the APK, checking it against the published SHA-256 as it streams.
5. Read the downloaded APK's signing certificate and compare it with PhairPlay's own.
   **Mismatch → refuse**, because that is exactly the "package conflicts" case.
6. Hand it to `PackageInstaller`.

Forks: set `phairplay.updateRepo` in `gradle.properties` (or pass
`-Pphairplay.updateRepo=you/your-fork`) so the app checks *your* releases, not upstream's.

Nothing about this is hidden: no telemetry, no analytics, one HTTPS call to api.github.com, and
it only happens when you open the app or turn the setting on.
