# Hearth release procedure (3.7)

## Publication intent

- Pull requests run CI without publication.
- Main pushes run the full Release verification pipeline and create immutable **development prereleases** named `v3.7-main.<run>`. These are download-only; GitHub's stable `/releases/latest` updater does not offer them.
- A preparation commit marked `[stable-dispatch]` skips the automatic development job; the subsequent manual stable dispatch runs every verification gate once.
- A maintainer explicitly runs Release on `main` with the **stable** input enabled only after hardware acceptance. That creates `v3.7`, titled `Hearth 3.7`. A changelog heading alone never triggers stable publication.
- On 1 November 2026 the maintainer authorized pushing 3.7 to main and publishing a new release. Existing release titles, notes and tags remain immutable.

One naming rule applies to both kinds: tag `v<version>`, title `Hearth <version>`, embedded version `<version>-googletv`, APK `hearth<version>.apk` (for example `hearth3.7.apk`). The installed updater recognizes this case-insensitive, version-bearing filename. The 3.7 APK contains **only armeabi-v7a**, as requested for the Chromecast's 32-bit userspace; it is not a dual-ABI build. Its package remains `com.hearth.tv`, minSdk29, targetSdk35, compileSdk36.

## Verification

Use JDK17, Gradle8.11.1, AGP8.10.1, Kotlin2.2.21, NDK28.2.13676358 and CMake3.22.1. All Media3 artifacts resolve to1.11.1. Do not suppress Kotlin metadata checks.

```bash
python3 tools/test_extract_release_notes.py
python3 tools/test_release_workflow.py
python3 tools/check-workflows.py .github/workflows/*.yml
./gradlew :test-runner:test :app:testGoogletvDebugUnitTest \
  --tests 'com.phairplay.ui.HearthUiTest' \
  --tests 'com.phairplay.airplay.VideoDecoderSpsTest' \
  --tests 'com.phairplay.airplay.MediaHttpDataSourceTest' \
  :app:lintGoogletvDebug :app:assembleGoogletvRelease
./gradlew :app:dependencies --configuration googletvReleaseRuntimeClasspath
```

The workflow verifies one APK, package, embedded versions, exact ARMv7 ABI set and `apksigner` certificate before creating a tag. Use a monotonically increasing versionCode above every published build, including development builds. Report artifacts are retained separately from release assets. Never attach credentials or unredacted sender logs.

See [3.7 validation](HEARTH-3.7-VALIDATION.md) for test evidence and remaining physical-device gates. CI is not evidence that rotation, Spotify, Rumble, long-session stability or latency passed on a Chromecast.

## Immutable retries and Latest

A retry reuses the existing released binary rather than rebuilding a potentially different ZIP/signature. It verifies its recorded SHA-256, package, versionCode, signing certificate and ABI, and checks that the existing tag resolves to the same source commit. It requires exactly one expected APK asset and compares actual downloaded SHA-256 again before skipping upload. A mismatch fails closed. It never renames, rewrites, deletes or replaces old release notes/assets/tags.

Only an explicit stable build at the current main commit may take Latest. Publishing is serialized and main freshness is checked again after upload. Prereleases never take Latest; retries do not demote old releases. Existing TVs continue checking the stable endpoint. A development APK can be sideloaded manually and must retain the same package/certificate.

## Signing and upgrades

Keep the existing certificate for in-place upgrades. The checked-in community PKCS12 key is public, so it provides compatibility rather than a private security boundary. The expected SHA-256 certificate is:

`b9cb7e4b4d5179870e672e850f5d3661f02c248f943dbaf10cd78cd0c047eaae`

Private maintainer credentials can be supplied through the existing keystore secret settings. Do not rotate the certificate on this package silently. A new private key for new installations requires an explicitly separate package/channel or a separately verified Android signing-lineage migration; neither is implemented here. Replacing the certificate with a fresh private key breaks ordinary updates from the community-signed app.

Before installation, confirm the actual installed package and signing identity, then use `adb install -r` to preserve settings. Do not uninstall as a first troubleshooting step.
