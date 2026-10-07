# Keeping Hearth up to date

Three ways, easiest first.

## 1. From the TV (Hearth 1.4+)

**Settings → Updates → Check for updates.**

The updater requests `https://api.github.com/repos/2archiver/Hearth/releases/latest`, compares
numeric Android `versionCode` values, and downloads only the actual compatible Google TV APK
asset returned by that release. It does not construct a guessed fixed-name APK URL.

| Setting | Default | What it does |
|---------|---------|--------------|
| **Check automatically** | on | Look for updates in the background and post a notification when one is found |
| **Download automatically** | on | Fetch and verify the APK as soon as one is found, so installing is one tap |
| **Install automatically** | off | Ask Android to install a verified update without another Hearth prompt |

The update card shows both the installed and offered builds. **Later** keeps the offer; **Skip this
version** suppresses that build on this TV until a newer one appears. The preference survives
receiver restarts and updates.

### What must verify before an install is offered

Hearth rejects the update instead of guessing when release metadata is missing, conflicting or
ambiguous. Before it stages an APK, it checks:

1. The API response identifies one `-googletv.apk` asset and provides its actual GitHub download
   URL, positive file size, a positive `versionCode`, and a full 64-hex SHA-256 digest. The APK
   URL must belong to the canonical Hearth release path. The current workflow puts the version
   code and SHA-256 in the release notes; GitHub's asset record supplies the actual file URL and
   size.
2. The streamed download has the reported exact size and SHA-256. Missing or malformed checksum
   metadata is a **blocking error**; Hearth does not proceed on size-only verification.
3. Android can read the downloaded package. Its `applicationId` must remain
   `com.phairplay.googletv`; its embedded `versionCode` must match the release metadata and be
   strictly newer than the installed build. For version-named releases, its `versionName` must
   also agree with the APK filename.
4. The APK signing certificate must match the installed Hearth signing identity. A mismatch or an
   unreadable signature blocks installation and reports an actionable reason.
5. Before the staged APK is handed to Android, package/version/signature checks are repeated. The
   user sees **Installing** only while Android processes the request. A queued `PackageInstaller`
   request is **not** described as installed; success is recorded only after Android confirms it.

The release page is also available at
<https://github.com/2archiver/Hearth/releases/latest>. It lists the actual version-named APK;
choose the Google TV asset rather than relying on an old fixed filename.

## 2. Install the APK over the old one

Open <https://github.com/2archiver/Hearth/releases/latest>, download the version-named
`Hearth-<version>-googletv.apk`, then use Downloader on the TV or:

```bash
adb install -r Hearth-<version>-googletv.apk
```

`-r` asks Android to replace the existing package while preserving app data. This works only when
the APK has the same package identity and compatible signing key. Do not uninstall as a first
troubleshooting step: uninstalling can erase Hearth's settings.

## 3. Update the repository used by older builds

The canonical repository is now `2archiver/Hearth`. New builds use its GitHub API and release
pages. The 1.9.2 updater also normalizes the former `2archiver/phairplay-archiver-fork-` setting
before constructing the API request, with a unit test for that migration.

That does **not** certify automatic migration for every already-installed older APK. Older
binaries keep their old code and asset assumptions; do not assume a repository redirect or a
changed release asset will be enough for them to update. If an older build cannot find or verify
the release, use the canonical release page above and install the current APK manually with `-r`.
The package name, preferences/data and signing practices remain unchanged in this patch; if Android
reports a signing-key conflict, stop and read the section below rather than uninstalling blindly.

Fork maintainers can configure `phairplay.updateRepo` in `gradle.properties`, or pass
`-Pphairplay.updateRepo=you/your-fork`. Official release CI explicitly builds with
`2archiver/Hearth`, regardless of stale runner environment values.

---

## A different signing key / one-time reinstall

Android only allows an APK to replace an existing install when both use a compatible signing
identity. Hearth cannot bypass that platform rule. If a signed APK is not accepted, do not keep
retrying and do not uninstall immediately: first confirm that the source is trusted and compare the
signing source. Uninstalling may erase app data.

Hearth's updater reads the APK certificate before staging and explains a mismatch instead of
launching an install it knows Android will reject. Builds from this repository are expected to keep
using the established `app/signing/phairplay.p12` community key unless release configuration is
intentionally changed. Release CI verifies the configured certificate fingerprint; signing identity
changes are not part of the 1.9.2 patch.

If you intentionally switch to a differently signed build, back up anything important, then follow
the release maintainer's one-time migration instructions. After switching, keep using builds from
that same source/key.

---

## How the updater decides

1. Make one HTTPS request to the canonical latest-release API endpoint. Read only the actual
   compatible APK asset and its metadata from that response/release body; no guessed download path
   or non-GitHub asset URL is trusted.
2. Require a positive `versionCode`, exact published APK size, and full SHA-256. A missing or
   conflicting value blocks the offer; no unverified, size-only APK is handed to Android.
3. Offer only a release with a strictly higher code than the installed `BuildConfig.VERSION_CODE`:

   | Published vs installed | Result |
   |---|---|
   | published **newer** | **Update available** |
   | equal | **Up to date** |
   | published **older** | **Up to date**; never offer a downgrade |
   | release code missing or unreadable | **Update check failed**; never guess from a version string |

4. Stream the actual GitHub asset and verify its exact byte count and SHA-256 before inspecting it as
   an APK.
5. Verify the package name, embedded version fields, and signing certificate; reject any mismatch.
6. Ask `PackageInstaller` to proceed. Hearth says **Installing** while awaiting Android's result;
   it does not call a launched or queued installer request “installed.”

The updater sends no telemetry or analytics. Manual checks make HTTPS requests to GitHub when
selected; automatic checks run about every six hours while the receiver service is running and the
setting is on.
