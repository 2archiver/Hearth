# Releasing PhairPlay (Google TV)

PhairPlay has **one current GitHub release**, tagged `latest`. A successful
build from `main` updates it in place, and GitHub marks that same release as **Latest**. The
in-app updater, direct download link, and release page therefore point to the same APK. Older
numbered releases are kept as history; new builds do not create competing release entries.

## Download the current APK

- **The release (one asset, the APK):** <https://github.com/2archiver/phairplay-archiver-fork-/releases/latest>
- **Release title, update notes, version, checksum and build details:** <https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest>
- **Optional download page:** <https://2archiver.github.io/phairplay-archiver-fork-/>

A release carries **exactly one file**: `PhairPlay-<version>-googletv.apk`, named after the
`versionName` embedded in it (the workflow reads that back out of the built APK with `aapt`).
Nothing else is uploaded — no `SHA256SUMS.txt`, no `version.json`. What those files used to carry
is now written into the release body, which is where a human reads it and where
`ReleaseParser.scrapeVersionCode` / `scrapeSha256` find it for the in-app updater.

Why one file: a release with three assets invites a TV to download the wrong one, and an
un-versioned `PhairPlay-googletv.apk` cannot tell you which build it is once it lands in the
Downloads folder. Both problems disappear when the file name *is* the version and it is alone.

## How publishing works

`.github/workflows/release.yml` runs on pushes to `main` and manual runs selected on `main`.
It builds, verifies, and publishes the APK only when that commit is still the current tip of
`main`. A fixed concurrency group prevents two builds from racing over the `latest` tag.

The workflow moves the `latest` tag to the current commit, deletes every asset the release already
has, and publishes the single version-named APK. It uses the version read from the built APK for the
file name and the title, and marks the release as GitHub's **Latest**. Notes show
commit titles since the previous build plus the matching `CHANGELOG.md` summary. Older releases
are left untouched as history.

### Publishing an update

1. Update `CHANGELOG.md` with the user-visible changes. Use `## [Unreleased]` for in-progress
   work, or a version heading matching the release version (for example, `## [1.6]`).
2. When starting a new release train, bump `phairplay.versionName` in `gradle.properties`.
3. Merge or push to `main`. GitHub Actions builds and publishes the current APK automatically.

No release tag or manual release editing is needed. To run the workflow manually, choose
**Actions → Release → Run workflow** and select the `main` branch.

### Version numbers

- **Version name:** CI builds `<phairplay.versionName>-main.<run number>`; the Google TV APK
  adds the `-googletv` flavor suffix — and that whole string becomes the release's asset file name,
  so the file on GitHub and the file Android installs agree about which version they are.
- **Version code:** a clock-based integer that is forced above the code in the current release.
  Android uses this number to determine whether an APK can update an installed app. A local
  `./gradlew` build derives it from the clock; `-Pphairplay.versionCode=…` overrides it.

## Signing: why updates install in place

Android only installs a new APK over an installed one when **both are signed with the same
key**. Get this wrong and the TV reports:

> **App not installed as package conflicts with an existing package**

That message means "different signing key", not "different version". It used to happen on every
PhairPlay update, because a build without signing secrets fell back to a throw-away debug key
that differed on every CI run — so every update had to be preceded by an uninstall.

PhairPlay fixes this by signing **every** build with one key:

```
app/signing/phairplay.p12        the public "community build" key, committed on purpose
```

| | |
|---|---|
| Alias / passwords | `phairplay` / `phairplay` (not a secret — see the trade-off below) |
| Type | PKCS12, RSA 2048, self-signed, 30 years |
| Used by | every build — `debug` **and** `release`, CI **and** local clones |

Because `debug` and `release` share the key, a debug APK downloaded from a CI run and a release
APK from the release workflow also replace each other.

### The trade-off, stated plainly

A committed private key is **not** a secret: anyone can build an APK signed with it. If someone
published a malicious "PhairPlay update" signed with this key, Android would install it over
PhairPlay without complaint. That is the cost of updates that Just Work for a sideloaded app
with no Play Store in the loop.

Mitigations built in:

- PhairPlay's own updater **verifies the downloaded APK's signing certificate against its own**
  before installing, and refuses anything that does not match — so the in-app path can never
  install a differently-signed build ([docs/UPDATES.md](UPDATES.md)).
- CI **verifies the APK signature after building** and pins the certificate SHA-256. The
  checked-in community key fingerprint is the default; if you use a private `KEYSTORE_BASE64`
  override, set the repository variable `EXPECTED_APK_CERT_SHA256` to that key's fingerprint.
  A signer change then fails the release instead of silently breaking in-place updates.
- Installing the APK by hand is still your call about which URL you trust.

If you publish builds to other people and want a key only you hold, override the committed key:

```bash
tools/make-signing-key.sh phairplay.p12 phairplay        # write your own keystore
KEYSTORE_PATH=/path/to/your.p12 \
KEYSTORE_PASSWORD=… KEY_ALIAS=phairplay KEY_PASSWORD=… \
  ./gradlew :app:assembleGoogletvRelease
```

In GitHub Actions, set **Settings → Secrets and variables → Actions**:

| Secret | Value |
|--------|-------|
| `KEYSTORE_BASE64` | `base64 -w0 your.p12` (macOS: `base64 -i your.p12`) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `phairplay` |
| `KEY_PASSWORD` | key password |
| `KEYSTORE_TYPE` | optional; `pkcs12` for `.p12`/`.pfx` (default), `jks` for `.jks` |

Keep your `.jks`/`.p12` backed up — lose it and you can never publish an in-place update again.
Gradle properties `phairplay.keystorePath`, `phairplay.keystoreType`, `phairplay.keystorePassword`,
`phairplay.keyAlias`, `phairplay.keyPassword` do the same thing without environment variables.

## The download page

`site/index.html` is a static page with one prominent **Download latest APK** button, Downloader/ADB
install steps, and a current-version summary. It reads the current release from GitHub in the
browser; older release links are tucked into a collapsed history section. The button keeps working
even if the GitHub API is unavailable because it uses the stable `latest` download URL.

One-time setup: **Settings → Pages → Build and deployment → Source: GitHub Actions**. The
`Releases page` workflow passes `enablement: true`, so a run from `main` flips that switch for
you; if the token lacks the right, set it by hand once. **The APK download does not depend on
Pages at all** — the release links above work regardless.

## Local release build

```bash
./gradlew :app:assembleGoogletvRelease
# app/build/outputs/apk/googletv/release/app-googletv-release.apk
```

Set `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` to sign with your own key;
otherwise the committed community key is used. Omit the `-P` flags to use the base version from
`gradle.properties` and a clock-derived versionCode.

## What the release workflow publishes

One asset, named after the version read back out of the built APK:

```
PhairPlay-1.5.0-main.131-googletv.apk
```

and a release body that carries everything the side files used to:

```markdown
- **Version:** `1.5.0-main.131-googletv` · `versionCode 1449351`
- **Size:** 24.9 MB · **SHA-256:** `…64 hex…`
```

That is the whole contract with the in-app updater (`update/ReleaseParser`): one request to
`/releases/latest`, the version name from the asset file name, the code and digest scraped from the
body. Releases published before this change also had `version.json` and `SHA256SUMS.txt`, and those
parsers are still in place, so an old release resolves too — but nothing publishes them, and the
publish step deletes every pre-existing asset so a re-run of an old release cannot leave them
behind.

Before publishing, the workflow verifies the APK's embedded version name and code, checks the
signing certificate with `apksigner verify`, and refuses any build whose `versionCode` is not
higher than the published APK.
