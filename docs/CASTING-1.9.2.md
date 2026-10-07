# 1.9.2 real-device acceptance: native Photos and YouTube AirPlay video

These are two different iOS sender paths and two separate release gates. Neither can be called
fixed from a unit test, a rendered UI, an accepted `/play`, an audio stream, the YouTube TV-code
flow, Control Centre audio routing, or screen mirroring. The TV must show moving video and play its
sound continuously for the relevant in-app AirPlay path.

## Release priorities

### Priority 1 — native iOS Photos video

The reported failure is pressing the **AirPlay button inside a video in Apple's Photos app** and
getting no playback. Test that exact control and source app; do not substitute the Control Centre
picker, mirroring, Music, still-photo display, or another app.

For each run, record separately:

- sender iPhone/iOS version, TV model/Android TV OS, Hearth build, Wi-Fi/Ethernet topology;
- whether the asset is **downloaded locally** or **iCloud-only** (record whether Photos finished
  downloading it before the AirPlay attempt);
- container, video codec/profile, resolution/frame rate/HDR, audio codec/channel count when known;
- whether Photos' in-video AirPlay picker lists Hearth and whether selection connects;
- moving picture and audible sound on the TV, continuous playback for at least five minutes,
  pause/resume, seek in both directions, Stop, and a fresh reconnect;
- the last URL-free per-session trace stage if any step fails.

Do not combine local and iCloud-only results: they may exercise different retrieval paths. Report
codec/device observations, not an unverified blanket statement that a codec or all Photos videos are
supported.

### Priority 2 — YouTube iOS app video

Test the **YouTube app's own AirPlay picker** on the iPhone and select Hearth on the Google TV.
Do not use the YouTube TV-code/pairing feature, ordinary Control Centre audio output, or screen
mirroring as a substitute. A connected audio stream without a rendered moving frame is a failure
for this gate.

Record the same device/network/build details and verify a visible first frame, continuing motion and
sound, pause/resume, seek, Stop, and a new cast. Where available, export the per-session trace and
note the last completed `/play`, reverse/PTTH, FCUP, manifest, player-prepare, decoder or first-frame
stage. Test Safari, Rumble and live streams separately; no result here implies support for them.

## Stop semantics to check on both paths

1. **Stop playback** ends the current cast, silences local output promptly and returns the TV to its
   ready state. Hearth remains open, discoverable and listening for another sender.
2. Reconnect and start a cast again without restarting Hearth.
3. **Stop receiver** is the separate shutdown action: stop the receiver/service and confirm it no
   longer advertises or accepts new connections.
4. If a session has separate audio/video transports, stopping one partial stream must not tear down
   the still-active stream. A terminal session Stop must release the whole session.
5. Exercise Stop while preparation or network I/O is still pending as well as during playback.
   Record observed Stop-to-silence and Stop-to-ready durations; these are measurements, not pass/fail
   latency targets.

## Diagnostics and evidence

The 1.9.2 trace is bounded and scoped to a session, with session ID, connection ID and media role
attached through URL-player creation. It records protocol/player lifecycle stages and preserves the
last failure across Stop so it can still be exported. It must not contain signed URLs, tokens,
credentials, pairing secrets or media bodies. Export the trace before a new attempt if that would
replace the last failure.

For each attempt, capture the stage names and reason, not request URLs or secret-bearing headers.
If the first frame is absent, distinguish sender negotiation, reverse-channel/FCUP response,
manifest/media fetch, player/audio setup, preparation, decoder/protection and first-frame timeout.
A `PLAYING` state is only evidence after a rendered first frame; it still does not replace checking
continuous playback and sound on the actual TV.

## Current status

| Gate | Status in this checkout |
|---|---|
| Native Photos video, in-video AirPlay control, moving picture + sound | **PENDING — no iPhone/TV run observed** |
| Photos downloaded-local vs iCloud-only | **PENDING — test both separately** |
| YouTube iOS app AirPlay video, moving picture + sound | **PENDING — no iPhone/TV run observed** |
| Stop, reconnect and long enough continuous playback on either app path | **PENDING — hardware run required** |
| Supported codec/container combinations on target TVs | **NOT ESTABLISHED — report actual device/codec results** |

The code changes and protocol/unit tests improve lifecycle ownership, trace context, first-frame
truthfulness, update verification and cleanup. They are not evidence that either iOS app path is
compatible. Do not change either row to PASS until its exact sender-app control has been exercised
on a real iPhone and Google TV/Android TV and all required behaviors above have been recorded.
