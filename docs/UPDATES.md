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

When the signing key matches, Android 12+ normally lets an app replace **itself** without a
confirmation dialog, so with all three on the update is hands-off. On older Android, or if the
TV's policy disagrees, the standard "Install?" confirmation is shown. A different signing key
is never auto-installed; PhairPlay stops and explains the one-time transition instead.

The first time, Android may need you to allow installs from PhairPlay:
**Settings → Apps → Special access → Install unknown apps → PhairPlay → Allow**.

## 2. Install the APK over the old one

```
https://github.com/2archiver/phairplay-archiver-fork-/releases/download/latest/PhairPlay-googletv.apk
```

Paste it into **Downloader** on the TV, or `adb install -r PhairPlay-googletv.apk` from a
computer. Once the TV is running a build signed with this repository's community key, Android
treats later APKs as updates — no uninstall. Older or differently signed installs need the
one-time transition described above.

## 3. Let `adb` do it

```bash
adb install -r PhairPlay-googletv.apk
```

---

## A different signing key / one-time reinstall

Android only allows an app to update an existing install when both APKs are signed with the
same key. This is a platform security rule; PhairPlay cannot bypass it or silently remove
itself. It is about the signing certificate, not the version number.

The first public 1.4 rolling builds were published before the repository's long-lived community
key was added. If your installed copy was signed with that earlier CI key, it cannot be replaced
in place by a build signed with the new key. The updater now identifies this case before install
and shows **One-time reinstall required** instead of a misleading "Update check failed" or an
Android package-conflict error.

To switch signing sources once:

1. Open the release page from the updater and download the APK to the TV (or use Downloader).
2. Note any PhairPlay settings you want to keep. Android may erase app data when you uninstall.
3. Uninstall the old PhairPlay, then install the downloaded APK.
4. Keep using builds from the same signing source. Future updates signed with that key install
   over the app normally.

After the migration, all builds from this repository use `app/signing/phairplay.p12`, the
public "community build" key committed intentionally
([the trade-off, stated plainly](RELEASING.md#signing-why-updates-install-in-place)). Every
release is checked by CI, and the in-app updater verifies both the published SHA-256 and APK
signing certificate before offering installation.

The same one-time transition is needed when switching between a fork/private build and another
source. If you are not switching sources and see this again, do not uninstall yet: confirm that
the APK came from the same repository/key as the installed app, then report the two build sources
in a [bug report](../.github/ISSUE_TEMPLATE/bug_report.md).

---

## How the updater decides

1. `GET https://api.github.com/repos/2archiver/phairplay-archiver-fork-/releases/latest` — one
   request. A PhairPlay release is that JSON plus a single asset, so nothing else is fetched.
2. Read the version: `versionCode` is scraped from the release body (`versionCode <n>`), the
   version name from the asset's file name, `PhairPlay-1.6.0-main.43-googletv.apk`. A release that
   still publishes a `version.json` descriptor (anything before 1.6) is read from that instead,
   because a descriptor beats a scrape.
3. Compare with the installed `BuildConfig.VERSION_CODE`. Higher = update available.
4. Download the APK, checking it against the SHA-256 in the release body as it streams. The digest
   must be a full 64 hex characters to be trusted; a release whose notes carry none is installed
   after a size check only, and says so.
5. Read the downloaded APK's signing certificate and compare it with PhairPlay's own.
   **Mismatch → refuse**, because that is exactly the "package conflicts" case.
6. Hand it to `PackageInstaller`.

Forks: set `phairplay.updateRepo` in `gradle.properties` (or pass
`-Pphairplay.updateRepo=you/your-fork`) so the app checks *your* releases, not upstream's.

Nothing about this is hidden: no telemetry and no analytics. Manual checks make HTTPS requests
to GitHub when you select **Check for updates**; automatic checks run about every six hours while
the receiver service is running and the setting is on.
