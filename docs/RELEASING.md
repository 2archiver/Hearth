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
| push / merge to `main` | rolling | tag `latest` (moved to the new commit, assets replaced in place) | `PhairPlay-googletv.apk`, `SHA256SUMS.txt`, `version.json` |
| push a `v*` tag | versioned | permanent release for that tag | `PhairPlay-<tag>-googletv.apk`, `PhairPlay-googletv.apk`, `SHA256SUMS.txt`, `version.json` |
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

Set `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` to sign with your own key;
otherwise the committed community key is used. Omit the `-P` flags to use the base version from
`gradle.properties` and a clock-derived versionCode.

## What the release workflow publishes now

Alongside the APK and `SHA256SUMS.txt`, every release carries **`version.json`** — that is what
PhairPlay's in-app updater reads:

```json
{
  "versionName": "1.4.0-main.131",
  "versionCode": 20432100,
  "apk": "PhairPlay-googletv.apk",
  "sha256": "…",
  "size": 12345678,
  "tag": "latest",
  "commit": "…",
  "builtAt": "…"
}
```

The workflow also **verifies the APK signature** with `apksigner verify` and, for rolling builds,
checks that the new `versionCode` is strictly greater than the published one — a code that does
not grow is how an update quietly stops being installable.
