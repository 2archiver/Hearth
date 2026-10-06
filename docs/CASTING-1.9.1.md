# Sender-mediated AirPlay video — 1.9.1 compatibility note

This note covers **video sent through an app's AirPlay picker**, specifically the sender-mediated
HLS / FCUP path used by the iOS YouTube app. It is separate from Control Centre screen mirroring,
Google Cast, YouTube TV-code pairing and ordinary direct HTTP(S) playback.

## What the code path does

The sender can send `/play` with an internal `mlhls://…/master.m3u8` location that Hearth cannot
fetch directly. The receiver must use the sender's already-authenticated AirPlay session:

1. The sender upgrades a socket with `POST /reverse` and `101 Switching Protocols` (PTTH).
2. Hearth writes a `POST /event` FCUP request to ask the sender for a playlist or media item.
3. The sender answers that request on the reverse socket with an HTTP/EVENT response frame, and
   separately posts the FCUP result to `/action`.
4. Hearth routes the reply to the matching bridge, rewrites the playlist for Media3, prepares a
   player on a valid surface, and waits for the first rendered frame before reporting video as
   playing.

The defect found in the checked-out 1.9.0 code was in step 3: after `/reverse`, the connection loop
continued to use the RTSP request reader for sender response bytes. The verified UxPlay reference
for this wire behavior is pinned at
[`3dbf7ceee65932154e85a2f83963d53520a799fa`](https://github.com/FDH2/UxPlay/tree/3dbf7ceee65932154e85a2f83963d53520a799fa).
Only the relevant protocol behavior is adapted; no UxPlay implementation code is copied.

1.9.1 adds a bounded HTTP/EVENT frame reader, gates FCUP writes until the 101 response is sent,
stages a replacement bridge before its master fetch, and routes replies by session/request ID. It
also keeps preparation, audio setup, manifest loading and first-frame evidence distinct in traces
and failure state. A prepared player, an active audio stream or an accepted `/play` is **not** proof
that a video frame reached the screen.

## Compatibility status

| Path | 1.9.1 status |
|---|---|
| YouTube iOS app → its AirPlay picker → Hearth | This is the targeted sender-mediated HLS path. Protocol/state tests were added, but Apple-device-to-Google-TV first-frame and audio/control validation are **NOT RUN**. Do not treat this release as an end-to-end verified fix. |
| AirPlay audio-only | Existing audio-only behavior is preserved; audio-only or renderer readiness is not reported as video playback. |
| Control Centre screen mirroring | Separate AirPlay mirroring path; not evidence for the YouTube in-app video path. |
| Safari, Rumble and other apps | No compatibility claim is made from the YouTube/FCUP path. Test each separately. |
| Live HLS streams | **NOT RUN**; no live-stream support claim. |
| Google Cast / YouTube TV-code pairing | Not implemented or used as a substitute. |

## Playback diagnosis

The Home AirPlay card and connection log distinguish:

- sender `/play` negotiation and reverse-channel selection;
- FCUP master-manifest response;
- player/audio setup and preparation;
- first frame rendered (the only evidence that video is actually visible);
- later network, decoder/protection, seek and playback failures.

Failure details are deliberately URL-free so signed URLs and session tokens do not appear in the UI
or new diagnostic messages. A failure stage should be read with the connection log; `Audio only`
means the player found no video track, not that the video path succeeded.

## Validation required before calling this fixed

- Build and run the JVM tests, Android lint and Google TV APK build.
- On hardware, select Hearth from the **YouTube app's AirPlay picker** on an iPhone/iPad and record
  `/play`, FCUP master/media responses, Media3 preparation and first-frame evidence.
- Verify audio ownership, pause/resume, seek, stop, replacement casts and teardown on that same
  YouTube path; separately verify audio-only AirPlay and Control Centre mirroring still work.
- Test Safari, Rumble and live streams independently before making any compatibility statement.

In the current sandbox both the JVM/Android build checks and hardware checks are **NOT RUN**: Java,
`JAVA_HOME`, `adb` and a Google TV test device are unavailable. The project keeps its existing
application ID, updater repository, release signing identity and clock-derived `versionCode` policy.
