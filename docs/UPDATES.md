# Keeping Hearth up to date

Three ways, easiest first.

## 1. From the TV (Hearth 1.4+)

**Settings → Updates → Check for updates.**

Hearth asks GitHub what the newest published build is, compares **versionCode** (a number,
not a version string — `1.10.0` sorts before `1.9.0` as text), and if something newer exists
downloads it and offers to install.

| Setting | Default | What it does |
|---------|---------|--------------|
| **Check automatically** | on | Look for updates in the background, a few times a day, and post a notification when one is found |
| **Download automatically** | on | Fetch and verify the APK as soon as one is found, so installing is one tap |
| **Install automatically** | off | Install a verified update without another prompt. Turn this on for zero-tap updates |

The update card always shows **both builds** — what is installed and what is on offer — so
"update" never means "something changed, I don't know what". While an update is on offer there are
two dismissals, and they mean different things:

- **Later** — ask me again; the card keeps the offer.
- **Skip this version** — do not offer *this* build again. The next release is still offered
  normally; skipping is remembered on the TV and survives restarts.

When the signing key matches, Android 12+ normally lets an app replace **itself** without a
confirmation dialog, so with all three on the update is hands-off. On older Android, or if the
TV's policy disagrees, the standard "Install?" confirmation is shown. A different signing key
is never auto-installed; Hearth stops and explains the one-time transition instead.

The first time, Android may need you to allow installs from Hearth:
**Settings → Apps → Special access → Install unknown apps → Hearth → Allow**.

## 2. Install the APK over the old one

```
https://github.com/2archiver/phairplay-archiver-fork-/releases/download/latest/Hearth-googletv.apk
```

Paste it into **Downloader** on the TV, or `adb install -r Hearth-googletv.apk` from a
computer. Once the TV is running a build signed with this repository's community key, Android
treats later APKs as updates — no uninstall. Older or differently signed installs need the
one-time transition described above.

## 3. Let `adb` do it

```bash
adb install -r Hearth-googletv.apk
```

---

## A different signing key / one-time reinstall

Android only allows an app to update an existing install when both APKs are signed with the
same key. This is a platform security rule; Hearth cannot bypass it or silently remove
itself. It is about the signing certificate, not the version number.

The first public 1.4 rolling builds were published before the repository's long-lived community
key was added. If your installed copy was signed with that earlier CI key, it cannot be replaced
in place by a build signed with the new key. The updater now identifies this case before install
and shows **One-time reinstall required** instead of a misleading "Update check failed" or an
Android package-conflict error.

To switch signing sources once:

1. Open the release page from the updater and download the APK to the TV (or use Downloader).
2. Note any Hearth settings you want to keep. Android may erase app data when you uninstall.
3. Uninstall the old Hearth, then install the downloaded APK.
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
   request. A Hearth release is that JSON plus a single asset, so nothing else is fetched.
2. Read the version: `versionCode` is scraped from the release body (`versionCode <n>`), the
   version name from the asset's file name, `Hearth-1.6.0-main.43-googletv.apk`. A release that
   still publishes a `version.json` descriptor (anything before 1.6) is read from that instead,
   because a descriptor beats a scrape.
3. Compare with the installed `BuildConfig.VERSION_CODE` — a strictly higher number is the *only*
   thing that counts as an update:
   | Published vs installed | What the card says |
   |---|---|
   | published **newer** | **Update available** (with both build numbers) |
   | equal | **Up to date** — the release matches this install |
   | published **older** | **Up to date**, and says the published build is older than this install |
   | version number missing from the notes | **Up to date (could not identify the published build)** — never a guess |
   Nothing that is not strictly newer is ever offered, announced, or downloaded. This is what
   stops the "update" that would actually have been a downgrade: installing an older code over a
   newer one is rejected by Android (`INSTALL_FAILED_VERSION_DOWNGRADE`) even after the download.
4. Download the APK, checking it against the SHA-256 in the release body as it streams. The digest
   must be a full 64 hex characters to be trusted; a release whose notes carry none is installed
   after a size check only, and says so.
5. **Read the downloaded APK's own `versionCode`.** If it is not strictly newer than the installed
   build — a mislabelled release, a stale asset on the `latest` tag — the download is discarded and
   the card explains why. This is the last line of defence before Android's installer.
6. Read the downloaded APK's signing certificate and compare it with Hearth's own.
   **Mismatch → refuse**, because that is exactly the "package conflicts" case.
7. Hand it to `PackageInstaller`.

Forks: set `phairplay.updateRepo` in `gradle.properties` (or pass
`-Pphairplay.updateRepo=you/your-fork`) so the app checks *your* releases, not upstream's.

Nothing about this is hidden: no telemetry and no analytics. Manual checks make HTTPS requests
to GitHub when you select **Check for updates**; automatic checks run about every six hours while
the receiver service is running and the setting is on.
