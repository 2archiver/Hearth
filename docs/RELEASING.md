# Releasing Hearth (Google TV)

Every successful build from `main` publishes a GitHub Release. Nothing is overwritten: old
releases, tags and APK assets stay downloadable forever, and GitHub's **Latest release** designation
is the only rolling pointer. The in-app updater, the direct download link and the release page
therefore always resolve to the newest build without any tag ever being moved.

There are two publication kinds, chosen automatically by the same job that runs every gate:

| Kind | When | Tag | Asset |
|---|---|---|---|
| **Stable milestone** | `CHANGELOG.md` has a `## [<base version>]` section **and** no `v<base>` tag exists yet | `v2.1.0` | `Hearth-v2.1.0-android-tv.apk` |
| **Per-run development build** | every other push to `main` | `v<base>-main.<run number>` | `Hearth-<base>-main.<run>-googletv.apk` |

The milestone is published from the *same* job that ran the protocol tests, the Robolectric TV layout
tests, lint, the APK metadata check, the signing-fingerprint check and the versionCode
monotonicity check — the tag is created only after all of them pass. That means a merge that bumps
`phairplay.versionName` and adds its changelog section produces the stable release directly, with no
second dispatch and no duplicate release for the same commit. A failed milestone build leaves no
`v<base>` tag behind, so its rerun retries the milestone rather than publishing a stray dev build.

## Download the current APK

- **The current release (GitHub resolves this to the newest build):** <https://github.com/2archiver/Hearth/releases/latest>
- **A specific immutable release:** `https://github.com/2archiver/Hearth/releases/tag/v<version>` —
  for example `v2.0.0-main.23`.
- **Optional download page:** GitHub Pages is not enabled for `2archiver/Hearth` (checked via
  repository metadata on 2026-10-07). Use the GitHub release page above; do not rely on a Pages URL
  until it is enabled.

A release carries **exactly one file**. Stable milestones use the predictable
`Hearth-v<version>-android-tv.apk`; per-run development builds are named after the `versionName`
embedded in them, `Hearth-<version>-googletv.apk` (the workflow reads the embedded version back out
of the built APK with `aapt`). The in-app updater accepts both suffixes, and still refuses an
ambiguous release that carries two compatible APKs. Nothing else is uploaded — no `SHA256SUMS.txt`, no `version.json`. What those files used to carry
is written into the release body, which is where a human reads it and where
`ReleaseParser.scrapeVersionCode` / `scrapeSha256` find it for the in-app updater.

Why one file: a release with three assets invites a TV to download the wrong one, and an
un-versioned `Hearth-googletv.apk` cannot tell you which build it is once it lands in the
Downloads folder. Both problems disappear when the file name *is* the version and it is alone.

## Immutable release names

| Thing | Value for run 23 of the 2.0.0 train |
|---|---|
| Base version (`phairplay.versionName`) | `2.0.0` |
| Embedded APK version / asset name | `2.0.0-main.23-googletv` / `Hearth-2.0.0-main.23-googletv.apk` |
| Git tag and GitHub Release | `v2.0.0-main.23` |
| Direct APK URL | `https://github.com/2archiver/Hearth/releases/download/v2.0.0-main.23/Hearth-2.0.0-main.23-googletv.apk` |

The workflow run number is stable across reruns of the same run, so a rerun rebuilds the *same*
release instead of minting a second one — that is what makes reruns idempotent.

## How publishing works

`.github/workflows/release.yml` runs on pushes to `main` and manual runs selected on `main`.
After the APK builds and every verification gate passes, it:

1. Derives `version`, the immutable `tag` (`v<version>`) and a monotonic `versionCode`.
2. Creates the tag with a normal, non-forced push **only if it does not already exist**. There is
   no force-push and no moving tag anywhere in the repository.
3. Creates a GitHub Release for that tag with the single version-named APK, or — on rerun — finds
   the existing release and skips an upload that is already present. Releases and assets are never
   deleted, pruned or overwritten; an unexpected same-named asset with a different size fails the
   run loudly instead of replacing history.
4. Marks the release GitHub **Latest** only when the run's commit is still the tip of `main`. A
   queued older run still publishes its immutable release, but without taking the Latest
   designation. A fixed concurrency group (`release-publish`, no in-progress cancellation)
   serializes publishing so the designation cannot race.
5. Also uploads the signed APK and `build.log` as a workflow artifact (30-day retention). Artifacts
   are separate from GitHub Releases: the retention policy never touches published release assets.

The historical release tagged `latest` (the 1.9.3 build) is deliberately **left untouched** as a
downloadable historical artifact. It is no longer the updater's source of truth: no workflow
publishes to a tag named `latest` anymore, and GitHub's Latest designation on the newest immutable
release is what `/releases/latest` returns.

### Publishing an update

1. Update `CHANGELOG.md` with the user-visible changes. Use `## [Unreleased]` for in-progress
   work, or a version heading matching the release version (for example, `## [2.1.0]`).
2. When starting a new release train, bump `phairplay.versionName` in `gradle.properties`.
   **A base version with a changelog section and no `v<base>` tag becomes the stable release on the
   next merge to `main`** — check that the notes are accurate before merging, because the release
   body is generated from that section.
3. Merge or push to `main`. GitHub Actions builds and publishes a new immutable release
   automatically. To publish deliberately, choose **Actions → Release → Run workflow** and select
   the `main` branch.

No manual tag or release editing is needed. The contract is enforced offline by
`tools/test_release_workflow.py` (no destructive release commands, immutable naming, Latest
designation, artifact retention, rerun idempotency) and on the JVM by
`app/src/test/kotlin/com/phairplay/update/ImmutableReleaseTest.kt`.

### Version numbers

- **ABIs:** the APK must contain both `armeabi-v7a` and `arm64-v8a`. The release job reads the
  `lib/<abi>/` entries out of the built APK and fails the run if either is missing, because a build
  that silently lost 32-bit support still installs on 64-bit TVs and would otherwise go unnoticed.
- **Version name:** a stable milestone embeds exactly `<phairplay.versionName>`; a per-run
  development build embeds `<phairplay.versionName>-main.<run number>`. The Google TV flavor then
  adds the `-googletv` suffix, so the milestone APK reports `2.1.0-googletv`. A development build's
  whole string becomes its asset file name (`Hearth-2.1.0-main.26-googletv.apk`), while the
  milestone uses the fixed `Hearth-v2.1.0-android-tv.apk`; either way the file on GitHub and the
  installed app agree about which version they are.
- **Version code:** a clock-based integer that is forced above the code in the release currently
  designated Latest. Android uses this number to determine whether an APK can update an installed
  app. A local `./gradlew` build derives it from the clock; `-Pphairplay.versionCode=…` overrides
  it. A rerun of the release that already owns the Latest designation is exempt from the gate
  rather than failed against its own code.

## Signing: why updates install in place

Android only installs a new APK over an installed one when **both are signed with the same
key**. Get this wrong and the TV reports:

> **App not installed as package conflicts with an existing package**

That message means "different signing key", not "different version". It used to happen on every
Hearth update, because a build without signing secrets fell back to a throw-away debug key
that differed on every CI run — so every update had to be preceded by an uninstall.

Hearth fixes this by signing **every** build with one key:

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
published a malicious "Hearth update" signed with this key, Android would install it over
Hearth without complaint. That is the cost of updates that Just Work for a sideloaded app
with no Play Store in the loop.

Mitigations built in:

- Hearth's own updater **verifies the downloaded APK's signing certificate against its own**
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
install steps, and a current-version summary. It reads the current release from GitHub's
`/releases/latest` endpoint in the browser and lists the immutable releases in a collapsed history
section; older release links stay valid because their tags are never moved. Before the GitHub API
responds — or if it is unavailable — the button opens the current GitHub release page. Once the API
responds, the page uses the actual compatible asset URL returned for that release; it does not
invent a fixed APK filename.

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

## What each release publishes

One asset, named after the version read back out of the built APK:

```
Hearth-2.0.0-main.23-googletv.apk
```

and a release body that carries everything the side files used to:

```markdown
- **Version:** `2.0.0-main.23-googletv` · `versionCode 21021542`
- **Size:** 13.2 MB · **SHA-256:** `…64 hex…`
```

That is the whole contract with the in-app updater (`update/ReleaseParser`): one request to
`/releases/latest` (the release GitHub designates newest), the version name from the asset file
name, the code and digest scraped from the body, and the asset's own immutable
`/releases/download/v<version>/…` URL. Releases published before the one-asset change also had
`version.json` and `SHA256SUMS.txt`, and those parsers are still in place, so an old release
resolves too — but nothing publishes them.

Before publishing, the workflow verifies the APK's embedded version name and code, checks the
signing certificate with `apksigner verify`, and refuses any build whose `versionCode` is not
higher than the release currently designated Latest (except an idempotent rerun of that release
itself).
