# Hearth Testing Guide

This document explains how to run tests, what is tested, and how to perform manual testing on real devices.

---

## How to Run Tests

### Unit Tests (no device required)

Unit tests run on your development machine. They mock Android APIs and test the logic in isolation.

```bash
# Run all unit tests
./gradlew test

# Run unit tests for the Google TV flavor
./gradlew testGoogletvDebugUnitTest

# Run a single test class
./gradlew test --tests "com.phairplay.airplay.RtspHandlerTest"
```

Test results are in: `app/build/reports/tests/`

### Instrumented Tests (requires a device or emulator)

Instrumented tests run on a real Android TV device or emulator. They test UI and system integration.

```bash
# Run all instrumented tests (requires connected device via ADB)
./gradlew connectedAndroidTest

# Verify ADB connection first:
adb devices
```

### Lint

```bash
# Run Android Lint
./gradlew lint

# Lint results: app/build/reports/lint-results-googletv-debug.html
```

### Full CI Check (same as GitHub Actions)

```bash
./gradlew :test-runner:test
./gradlew :app:lintGoogletvDebug :app:assembleGoogletvDebug
./gradlew :app:testGoogletvDebugUnitTest --tests 'com.phairplay.ui.HearthUiTest'
```

GitHub Actions runs the same checks on `main`:

- `JVM protocol tests`: fast protocol/parser/media-unit coverage through `:test-runner:test`
- `Android lint & debug APKs`: Android lint, both debug APKs, and `HearthUiTest`
  (`:app:testGoogletvDebugUnitTest --tests 'com.phairplay.ui.HearthUiTest'`), which inflates the
  real TV layouts under Robolectric's native graphics mode and uploads rendered PNGs to
  `app/build/reports/hearth-ui/`

Everything else under `:app:test*UnitTest` stays out of the CI gates: those tests exercise Android
framework classes that are unstable on a host JVM. Use `:test-runner:test` for CI-grade
protocol/unit coverage, `HearthUiTest` for layout/focus/bitmap regressions, and real devices for
hardware behavior — a rendered preview is not a compatibility claim (see
[docs/UPSTREAM-REVIEW.md](UPSTREAM-REVIEW.md)).

---

## What Is Tested

### Unit Tests

| Test Class | Tests | What Is Verified |
|---|---|---|
| `MdnsServiceTest` | 5 | mDNS registration, correct number of services, idempotency, stop behavior, port number |
| `RtspHandlerTest` | 5 | OPTIONS/RECORD/TEARDOWN responses, callback triggering, unknown method handling, SDP parsing |
| `VideoDecoderTest` | 4 | Pre-init safety, double-release safety, empty input handling, basic construction |
| `NetworkUtilsTest` | 5 | Device name reading, fallback behavior, special character sanitization, MAC address format |
| `MainActivityTest` | 3 | Activity startup, WaitingScreen visibility, StreamingScreen hidden at startup |
| `HearthUiTest` | 4 | Real layout inflation on a TV viewport, D-pad focus order, Settings rows growing to fit text, bounded artwork decoding |
| `NtpReplyValidatorTest` | 5 | Stale, truncated, wrong-type and clockless AirPlay timing replies are rejected before they can move the clock |

### What Is NOT Unit Tested (and why)

| Component | Reason | How It's Tested Instead |
|---|---|---|
| MediaCodec H.264 decode | Requires GPU hardware — cannot run on CI | Manual testing on real device (see below) |
| AudioTrack playback | Requires audio hardware | Manual testing on real device |
| NsdManager mDNS broadcast | Requires real network stack | Manual testing: observe macOS AirPlay menu |
| Full RTSP session (network) | Requires real TCP socket pair | Integration tested manually with a Mac |
| AES-CTR encryption (end-to-end) | Requires real audio stream | Manual: verify audio plays correctly |

---

## Manual Test Scenarios

For acceptance testing before a release, perform all scenarios below on **Google TV** (Android 14 is the primary target).

### Before You Start

Capture a clean baseline before each device run:

```bash
adb devices -l
adb logcat -c
```

Install the correct debug APK:

```bash
# Google TV — the only build there is
./gradlew :app:assembleGoogletvDebug

adb install -r app/build/outputs/apk/googletv/debug/app-googletv-debug.apk
```

There is no build flag that turns Google Cast on: Hearth has no Cast receiver, so there is no
Cast App ID to register and no Cast SDK in the dependency graph. Verifying "Cast still works on the
TV" means verifying the TV's own built-in receiver, which Hearth neither binds nor competes with.

After a failed run, collect diagnostics before restarting the app:

```bash
tools/collect-device-logs.sh

```

The script writes ADB device details, package info, memory stats, process CPU, and filtered logcat output under `device-test-logs/`.

### Scenario 1: App Startup (Milestone 1)
**Goal:** App starts and shows the WaitingScreen without crashing.

1. Install the debug APK via `adb install`
2. Launch Hearth from the TV app list
3. **Expected:** WaitingScreen appears with the TV's device name
4. **Check:** No crash dialog, no FATAL in `adb logcat`
5. **Pass condition:** App remains stable for 30 seconds after launch

---

### Scenario 2: mDNS Discovery (Milestone 2)
**Goal:** macOS discovers Hearth in the AirPlay menu within 3 seconds.

1. Ensure Mac and TV are on the same network — **cable the TV to Ethernet for this scenario**,
   because a wired TV is the shipping target and the case that used to fail silently
2. Launch Hearth on the TV
3. Check the AirPlay card on the TV's Home screen first: it must read
   `Advertising on Ethernet · <the TV's IP>`. If it shows a Wi-Fi address while the cable is
   plugged in, that is the bug — the sender will be looking on the wrong interface.
4. Start a timer on your phone
5. On your Mac, click the AirPlay icon in the menu bar (or System Preferences → Displays → AirPlay Display)
6. **Expected:** TV name appears in the AirPlay menu within 3 seconds
7. Close Hearth (press Back on TV)
8. **Expected:** TV name disappears from the AirPlay menu within 10 seconds

On Android 13 and below the platform filters multicast unless the app holds a
`WifiManager.MulticastLock`, so a discovery failure is a lock failure. Confirm the lock is held
while advertising:

```bash
adb shell dumpsys wifi | grep -i "phairplay-mdns"   # listed while the receiver is advertising
adb logcat -d | grep -E "mDNS:"                     # lock + registration lines
```

---

### Scenario 3: Screen Mirroring Connection (Milestone 3)
**Goal:** Successful RTSP handshake — macOS connects without errors.

1. Launch Hearth on the TV
2. On your Mac, select the TV from the AirPlay menu
3. **Expected:** StreamingScreen appears on TV (transitions from WaitingScreen)
4. Check `adb logcat` for RTSP messages:
   - You should see: OPTIONS → ANNOUNCE → SETUP → SETUP → RECORD (all `200 OK`)
   - **Fail condition:** Any `4xx` or `5xx` RTSP error codes in the log
5. On your Mac, stop screen sharing
6. **Expected:** WaitingScreen reappears on TV within 2 seconds

---

### Scenario 4: Video Quality (Milestone 4)
**Goal:** Video plays at ≥25fps with ≤100ms latency.

1. Connect macOS to Hearth as in Scenario 3
2. On your Mac, open a terminal and run: `watch -n 0.1 date +%T.%3N` (shows a millisecond clock)
3. Look at the TV screen
4. **Expected:**
   - Frame rate: ≥25fps (video looks smooth, not choppy)
   - Latency: the time shown on TV is ≤100ms behind the time on your Mac
   - No black flashes, no frozen frames during 5 minutes

**Tip:** You can also use macOS built-in Screen Sharing quality tool:
- Hold Option while clicking the AirPlay icon → "Quality" diagnostics

---

### Scenario 5: Audio Sync (Milestone 5)
**Goal:** Audio plays in sync with video.

1. Find an A/V sync test video (search for "A/V sync test clapper board")
2. Connect macOS to Hearth
3. Play the test video on your Mac (it should be mirrored to the TV)
4. **Expected:** When the clapper board snaps shut, the sound happens at the same moment visually
5. **Fail condition:** Audio is more than ~40ms ahead or behind the video

---

### Scenario 6: Stability (Milestone 6)
**Goal:** 30-minute continuous stream without disconnect or crash.

1. Connect macOS to Hearth
2. Start a timer
3. Keep the Mac active and streaming (move the mouse occasionally, watch something)
4. **Expected after 30 minutes:**
   - TV still shows the streaming screen
   - No crash on TV
   - `adb logcat` shows no FATAL or reconnection events
5. Check RAM: `adb shell dumpsys meminfo com.phairplay.*` should show < 150MB

---

### Scenario 7: Reconnect After Disconnect (Milestone 6)
**Goal:** Automatic reconnect works.

**Test 7a: Sender disconnects:**
1. Connect macOS to Hearth
2. On your Mac, click the AirPlay icon and select "Turn Off AirPlay Mirroring"
3. **Expected:** TV shows WaitingScreen within 2 seconds
4. Wait 5 seconds
5. On your Mac, select the TV from AirPlay again
6. **Expected:** Streaming resumes without restarting the app

**Test 7b: Network interruption:**
1. Connect macOS to Hearth
2. Briefly disable and re-enable Wi-Fi on your Mac (or unplug/replug Ethernet)
3. **Expected:** Hearth reappears in the macOS AirPlay menu within 5 seconds of network restoration

---

### Scenario 8: Wired Google TV 4K (Ethernet)
**Goal:** the shipping configuration works with no Wi-Fi at all on the TV. This is the regression
test for the version where AirPlay stopped appearing on wired sets.

Hardware: Google TV 4K, Android TV OS 14, Ethernet only — **disable the TV's Wi-Fi** in
Settings → Network so nothing can quietly fall back to it.

1. Launch Hearth and read the Home screen:
   - **Expected:** AirPlay card = *Advertising*, detail names `Ethernet` and the TV's wired IP.
   - **Expected:** Apple Casting card = *Advertising* (idle) and only *Connected* while mirror
     video is on screen — grey/waiting, never a false "Connected"; no
     permission prompt. A wired TV has no usable radio for it; that is a limitation, not a failure.
2. From a Mac on the same subnet, connect over AirPlay and mirror for two minutes.
   - **Expected:** picture at the panel's native size when
     **Settings → Higher resolution (up to 4K)** is on (the default). Check
     `adb logcat -d | grep -E "advertising|2160"` names 3840x2160, not 1920x1080.
3. Renew the TV's lease without restarting the app — on the router, release/renew, or toggle the
   TV's Ethernet off and on for 10 seconds.
   - **Expected:** the card briefly re-registers and the Mac still finds the TV within 5 seconds
     (`readvertise reason=network available` in the log). Losing the advertisement here is the bug
     this scenario exists for.
4. Kill and relaunch Hearth, then repeat step 2 with an iPhone on Wi-Fi (phone on Wi-Fi, TV on
   the cable, same subnet).
   - **Expected:** discovery works across the router's wired/wireless boundary. If the phone alone
     cannot see it, the multicast lock is not being granted or the router filters multicast —
     check `dumpsys wifi` above before blaming the app.
5. Leave it mirroring for 30 minutes.
   - **Expected:** audio does not go silent (RTSP keep-alive) and no `FATAL`/`ANR` in
     `adb logcat -d`.

---

## 1.9.2 iOS app-video release gates

Run the native **Photos in-video AirPlay** check and the **YouTube iOS app AirPlay picker** check
as separate scenarios on an iPhone and Google TV. For Photos, identify downloaded-local versus
iCloud-only media. For both apps, verify moving video and sound on the TV, pause/seek, Stop, and
reconnect. Control Centre audio routing and screen mirroring do not count as substitutes. Record the
last URL-free per-session failure stage and actual codec/device details; do not infer a codec matrix
from source inspection.

The exact steps, evidence fields and current pending status are in
[docs/CASTING-1.9.2.md](CASTING-1.9.2.md). Until those real-device checks pass, keep both app paths
marked pending regardless of unit-test or CI results.

## Performance observations

When real hardware is available, record the measured values and test conditions in the release
notes; these are observations, not pass/fail latency or performance targets:

```bash
# RAM usage during streaming
adb shell dumpsys meminfo com.phairplay.googletv | grep "TOTAL"

# CPU usage (5-second sample) — replace PID with actual process ID
adb shell top -n 5 -p $(adb shell pidof com.phairplay.googletv) | tail -5
```

For latency, describe the source, display, network and measurement method, and report the observed
value/range rather than claiming a target was met. For URL video, record time from Stop request to
local silence and ready-state transition as separate measurements.

---

## Known Test Limitations

| Limitation | Impact | Workaround |
|---|---|---|
| No CI hardware for video tests | MediaCodec tests can't run in CI | Manual testing required before release |
| FairPlay content can't be tested | Protected content (Netflix, etc.) is blocked by Apple — expected behavior | Document as known limitation in README |
| Wi-Fi quality varies | Latency tests may fail on congested 2.4 GHz | Test on 5 GHz or Ethernet |
