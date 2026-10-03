---
name: Bug Report
about: Something is not working as expected
title: "[BUG] "
labels: bug
assignees: ''
---

## Bug Description

<!-- A clear, concise description of what the bug is. -->

## Steps to Reproduce

1. Go to '...'
2. Click on '...'
3. See error

## Expected Behavior

<!-- What you expected to happen. -->

## Actual Behavior

<!-- What actually happened. -->

## Device Information

**TV Device (receiver):**
- Device model: (e.g., Google TV Streamer 4K, Chromecast with Google TV)
- Android TV OS version: (e.g., Android TV OS 14)
- TV output resolution: [ ] 4K  [ ] 1080p  [ ] don't know
- PhairPlay version (Settings → Version on the TV): (e.g., 1.6.0-main.43-googletv)
- Where the APK came from: [ ] rolling `latest` release  [ ] a `v…` release  [ ] CI artifact  [ ] built it myself
- **What the AirPlay card says on the Home screen, verbatim** — "Advertising on Ethernet ·
  192.168.1.42" / "Advertising on Wi-Fi · …" / an error line. This is the single most useful line
  in a report: it is the interface and address the advertisement is really live on.
- Network is [ ] wired only (TV Wi-Fi off)  [ ] Wi-Fi  [ ] both

**Sender device:**
- Type: [ ] iPhone  [ ] iPad  [ ] Mac  [ ] Windows (Miracast)  [ ] Android (Miracast)
- Model and OS version: (e.g., iPhone 14, iOS 27.0.1 · MacBook Pro, macOS 15.3)
- What you were doing: [ ] screen mirroring  [ ] photos  [ ] audio only
- Same subnet as the address on the TV's card? [ ] yes  [ ] no  [ ] don't know

> **Cast buttons are not a PhairPlay bug.** The app has no Google Cast receiver: the TV's built-in
> Chromecast owns ports 8008/8009 and `_googlecast._tcp`, and PhairPlay neither binds nor
> advertises them. If the problem is "the cast icon in an app does not list PhairPlay", that is by
> design — see `docs/guides/CAST.md`, and use iOS Screen Mirroring instead.

**Network:**
- Connection type: [ ] Wi-Fi 2.4 GHz  [ ] Wi-Fi 5 GHz  [ ] Ethernet
- Router model (if known):

## Logs

<!--
If you can reproduce the bug, please attach the Android logcat output.
Filter by "PhairPlay" to get relevant logs:
  adb logcat -s PhairPlay:* | head -100
For discovery problems these two lines are worth including even if nothing else is:
  adb logcat -d | grep -E "mDNS:"          # advertisement, multicast lock, retries
  adb shell dumpsys wifi | grep -i phairplay-mdns   # the multicast lock, held while advertising
-->

```
Paste logcat output here
```

## Additional Context

<!-- Any other context, screenshots, or information that might be helpful. -->
