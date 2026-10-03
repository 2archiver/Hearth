# Renamed to Hearth

**Status:** applied in 1.6.1. PhairPlay is now **Hearth** — the same app, the same signing key,
the same settings. Nothing about how it runs on the TV changed.

## Why the name changed

`PhairPlay` said the protocol, badly: *Air* and *Play* are Apple's words, the leading `Ph` made it
look like a knock-off of itself, and "PhairPlay (archiver)" was the launcher label users actually
saw. It also could not be said out loud in a sentence without spelling it.

**Hearth** is the warm centre of the home — which is where the TV already is. It describes the
TV's *role* rather than the technology, it is one syllable, and it is nobody else's word in
streaming or living-room software. The lockup used in the README:

> **Hearth** — *the TV your Apple devices come home to*

The runner-up and the full shortlist (Mira, Perch, Foyer, Aviary, Tessera) are preserved in
[archive/RENAME_IDEAS_2026-10-04.md](archive/RENAME_IDEAS_2026-10-04.md).

## What changed

| Changed | Value |
|---|---|
| Launcher label / app name (`app_name`, all three languages + the Google TV flavour) | **Hearth** |
| User-visible strings that named the app (notifications, update cards, troubleshooting text) | Hearth |
| Release asset name | `Hearth-<version>-googletv.apk` |
| Release title | `Hearth <version> for Google TV` |
| Gradle root project | `Hearth` |
| Download page (`site/`), README, docs, workflows, tools | Hearth |

## What deliberately did **not** change

These are the reasons an already-installed Hearth updates in place and keeps its configuration:

* **`applicationId` is still `com.phairplay.googletv`.** It is the install identity; changing it
  would make the next update install *next to* the existing app instead of over it.
* **The signing key is unchanged** (`app/signing/phairplay.p12`). A new key would force every
  install through the one-time uninstall dance the in-app updater exists to avoid.
* **Gradle property keys stay `phairplay.*`** (`phairplay.versionName`, `phairplay.versionCode`,
  `phairplay.updateRepo`) — forks and CI scripts pass them by name.
* **The Kotlin package stays `com.phairplay.*`**, so no source file moved.
* **DataStore preference keys are untouched** (`settings/SettingsRepository.kt`), so a TV keeps
  its display name, toggles and skipped-update choice.

So: the name on the launcher and in the release notes is new; the update path, the settings, the
AirPlay identity the sender sees and the repository are the same.

## If you had `PhairPlay` installed

Do nothing. The next release installs over it as a normal update (`Hearth 1.6.1`), keeping your
settings — the applicationId and signing key did not change.

## For contributors

* Release/APK names use `Hearth-…`; the repository name is unchanged (`phairplay-archiver-fork-`),
  and so is `phairplay.updateRepo` by default.
* Historical documents (`docs/archive/`, `docs/superpowers/`, `CHANGELOG.md`) keep the old name
  where they describe the app as it was at the time.
