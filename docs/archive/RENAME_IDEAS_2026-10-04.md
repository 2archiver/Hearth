# Rename ideas — shortlist and a recommendation

> **Archived 2026-10-04.** The shortlist below was the input to the rename; **Hearth** was chosen
> and applied. See [RENAME.md](../RENAME.md) for the decision and what actually changed.

**Status:** superseded — kept for the reasoning, not as a live proposal. The rename *mechanics*
checklist at the bottom lists everything that has to change when a name is chosen, because the
cheapest moment to pick one is before the next release train.

## Where the name has to work

PhairPlay today is a **Google TV app that makes a TV an Apple-device target**: AirPlay streaming
plus Apple Casting (screen mirroring). The name has to

* be short enough to read from a sofa and say out loud ("mirror to the …"),
* avoid Apple's and Google's marks — `Air`, `Play`, `Cast` are the obvious traps, and combining
  them (`AirCast`, `PlayCast`) invites both a trademark complaint and store confusion,
* survive being printed under an app icon on a launcher row, and
* sound like *your* app, not like a protocol.

## The shortlist

| # | Name | Why it works | Watch out for |
|---|------|--------------|---------------|
| 1 | **Hearth** | The warm centre of the home — where the TV already is. Says "this TV belongs to the household", not "this app speaks a protocol". Short, pronounceable, warm, gender-neutral. | Hearth is used by a smart-home/fintech startup; nothing in streaming or Android apps. |
| 2 | **Mira** | Spanish for *look*, one letter from *mirror*. Lovely for a mirroring-first product and effortless in a sentence: *"just Mira it to the TV."* | Mira is also a women's-health brand and an AI company — check the Play Store listing before committing. |
| 3 | **Perch** | You *perch* a phone's screen on the TV; a bird perches too. One syllable, physical, memorable. | Several small apps; no large media brand. |
| 4 | **Foyer** | The entrance hall where guests arrive — exactly what the app is: the place your devices walk into. Slightly elegant, unusual in software. | Pronounceable mostly in English/French; some might spell it *Foyay*. |
| 5 | **Aviary** | A home for birds — and for everything Apple's *Air* sends. The story writes itself ("your devices come home to the aviary"). | Adobe's discontinued photo editor used the name; low current conflict. |
| 6 | **Tessera** | A single tile in a mosaic: small, precise, part of a bigger picture. Distinctive, unlikely to collide, sounds like a product. | Longer; less obvious meaning at a glance. |

**Rejected on purpose:** anything containing `Air`, `Cast`, `Play`, `TV`, `Mirror` or `Screen`
(descriptor, trademark risk, or both); `Nest`, `Tether`, `Lumen`, `Magnet`, `Ember`, `Relay`,
`Beacon`, `Sidecar` (all owned by — or hopelessly tangled with — bigger products); `Cider` (an
existing Apple Music client); `Bridge` (the app just removed a Cast bridge, and the word is
everywhere).

## Recommendation

**Hearth.** It is the only candidate that describes *the TV's role in the home* rather than the
technology, which matters because PhairPlay's hardest, most valuable work is not visible: the
receiver that just works, that shows its network and its connection log, and that does not ask a
wired TV for Wi-Fi Direct permissions. A protocol name would date it; a room name will not.

If the goal is instead to lead with mirroring, **Mira** is the stronger name — but only after a
store search: the mark is busier than the other five.

Suggested lockup if the rename happens:

> **Hearth** — *the TV your Apple devices come home to*

## If a name is chosen: the mechanical checklist

1. `app/src/main/res/values/strings.xml` → `app_name` (and the `values-de` / `values-fr`
   variants); the launcher label comes from here.
2. `applicationId` in `app/build.gradle.kts` (`com.phairplay.googletv`) — **this changes the
   install identity**, so an existing install cannot be updated in place; it must be installed
   once alongside (or after an uninstall) only if the applicationId changes. Keeping the same
   `applicationId` keeps self-updates working, so it is fine to rename *only* the UI label first.
3. `gradle.properties` → `phairplay.versionName` / `phairplay.updateRepo` (the repo name itself,
   and `README`, `docs/`, `.github/workflows/release.yml`).
4. `settings.gradle.kts` → `rootProject.name`.
5. The signing key must **not** change (`app/signing/phairplay.p12`): a new key means every
   install needs a one-time uninstall, which is exactly what the in-app updater exists to avoid.
6. DataStore preference keys (`settings/SettingsRepository.kt`) can keep their old names —
   renaming them would silently reset every TV's settings.
7. `CHANGELOG.md`: a rename is a breaking change for users' muscle memory; announce it as its own
   entry with the old name in the title.
