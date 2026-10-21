# Hearth 2.1.0 casting status and YouTube video investigation

This record separates what was changed in code from what was observed on a device. A passing unit
test, an accepted `/play`, a parsed playlist or audible audio is not evidence that moving video
rendered on the TV.

## Evidence labels

- **USER-REPORTED PASS** — the user reports the behaviour worked; not independently reproduced here.
- **TESTED PASS** / **TESTED FAIL** — a test ran in this workspace and its result is recorded.
- **NOT TESTED** — no qualifying run exists. Source inspection never promotes a row to PASS.

## Status matrix for 2.1.0

| Path or behaviour | Evidence | Notes |
|---|---|---|
| Gallery still photo via native Photos AirPlay | **USER-REPORTED PASS** (2.0) | Unchanged code path; 2.1.0 does not touch photo handling. |
| Gallery video/image while screen mirroring | **USER-REPORTED PASS** (2.0) | Unchanged; mirroring pipeline only gained telemetry. |
| Screen mirroring (iPhone Control Centre / Mac) | **NOT TESTED in 2.1.0** | Pacing/telemetry changes are covered by JVM policy tests only. |
| YouTube audio-only AirPlay | **USER-REPORTED PASS** (2.0, and the 2.0 defect is that it stays audio-only) | Must keep working; not re-verified here. |
| Rumble audio casting | **USER-REPORTED PASS** (2.0) | Unchanged transport; not re-verified here. |
| **YouTube video** | **NOT TESTED / UNSUPPORTED as of 2.0; NOT FIXED in 2.1.0** | See below. No claim of a video fix. |
| Native Photos `/play` video | **NOT TESTED** | Unchanged. |
| Stable FPS presenting 24/25/30 fps content at its own cadence | **NOT TESTED on hardware** | Policy tests cover the timestamp mapping and presentation decisions; no TV run was available. |
| Debug overlay (top-left, 1 Hz, RENDER/SOURCE/RX separation) | **NOT TESTED on hardware** | Rendering and drawable regressions are covered by Robolectric UI tests; on-glass legibility was not observed. |
| Activity log severity model, filters, redaction, persistence | **JVM TESTS** | `ActivityLogTest`; see the PR's test evidence. |

## YouTube video: what is actually known

**Observed (user, Hearth 2.0):** casting from the YouTube iOS app stays audio-only on the TV. That
is a missing capability, not a regression from 2.0.

**Code-path audit (2.1.0):**

1. The YouTube iOS app does not send a plain `http(s)` media URL. It sends an `mlhls://` location and
   expects the receiver to fetch playlists and segments **through the sender's own reverse channel**
   (FCUP/PTTH). The transport therefore needs two things to line up: a `/play` carrying the
   sender-mediated location *and* a live reverse-channel offer from the sender.
2. Hearth already implements that path (`VideoPlayRequest.BodyParse.SenderMediated`,
   `SenderMediatedHlsCoordinator`, `SenderMediatedHlsBridge`, `ReverseHttpChannel`) and answers 400 —
   never a fake 200 — when the reverse channel or bridge host is missing. No capability bit is
   flipped to advertise support that does not exist.
3. There is **no evidence in this workspace** identifying which of those steps fails on a real
   iPhone + TV pair: no capture, no receiver log and no screen recording from 2.0 were provided.

Because of (3), 2.1.0 makes **no behavioural change to the YouTube video path**. Changing transport
speculatively would risk the audio path the user reports as working, which this brief explicitly
forbids.

**What 2.1.0 does add:** the failure is now recorded with a severity and a stage, in the activity log:

- `INFO` — "Sender-mediated /play received" (so support can tell "no `/play` arrived" from "it
  arrived and failed").
- `ERROR` — "AirPlay video playback failed" with the classified stage
  (`play-negotiation`, `manifest`, `first-frame`, `decoder`, …) and a URL-free detail such as
  "sender reverse channel unavailable" or "player was prepared, but no video frame reached the
  display".

That is the evidence the next device run needs, and it is the limit of an honest code-only change.

## Follow-up needed (not a 2.1.0 blocker)

Record, on the target TV with the iPhone build that reproduces the audio-only result:

1. The exact YouTube app version, iOS version, TV model/OS and Hearth build (`Settings → About`).
2. The activity log from the moment of casting (Settings → Advanced → Activity log → Copy
   diagnostics). Look for `Sender-mediated /play received`.
3. Whether a reverse-channel offer was registered before that `/play` ("Reverse channel
   registered …" in the same export).
4. If `/play` never arrives: the negotiation trace around `GET /info` and `SETUP` — the sender may
   be choosing an audio-only route because of what Hearth advertises.
5. Whether a video track appears in the negotiated SDP (`screen video` in the session summary).

Only a run that produces moving frames, sustained TV audio and working pause/seek on the device may
change this row to **TESTED PASS**.

## Unchanged, deliberately

- Application ID `com.phairplay.googletv`, signing lineage and the `armeabi-v7a` + `arm64-v8a` ABIs.
- Discovery, codec handling, native playback dependencies and the gallery/photo implementation.
- AirPlay capability advertisement: 2.1.0 advertises nothing new.

## 2.1.0 measurement status

Frame pacing and memory numbers are **not** invented here. There is no Android device, `adb`, JDK or
Android SDK in this workspace, so the only measurements produced are the JVM unit tests of the
sampling logic (see the PR). A real baseline-vs-updated comparison (p50/p95 frame time, dropped
frames, start/stop latency, memory growth) still requires a TV run and is listed as outstanding in
the PR checklist.
