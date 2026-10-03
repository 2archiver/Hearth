# Miracast (Wi-Fi Display) on PhairPlay

Miracast is the Android/Windows answer to AirPlay mirroring: the sender opens a **Wi-Fi Direct**
connection to the TV, then streams H.264 over it. It is the one protocol here with a hard
platform ceiling, so this page says plainly what works, what cannot, and how to tell the
difference on your own TV.

## What works

| Piece | State |
|-------|-------|
| Wi-Fi Direct service advertisement (`_wfd._tcp`) | ✅ registered and kept alive |
| WFD capability negotiation (RTSP M1–M7 on port 7236) | ✅ complete in 1.5 |
| H.264 video decode to the screen | ✅ |
| WFD audio | ❌ received and discarded — see below |
| HDCP-protected content (Netflix over Miracast, Blu-ray apps) | ❌ by design |

### Why WFD audio is not played

WFD carries audio inside an **MPEG-2 Transport Stream**. Playing it means demuxing TS → PES →
LPCM (or AAC) and then feeding AudioTrack, which is a separate demuxer PhairPlay does not have.
Receiving without decoding would produce noise, so PhairPlay drains and discards the audio
channel and logs that it did so. Video is unaffected. **Use AirPlay mirroring if you need sound.**

## What 1.5 fixed

PhairPlay's WFD negotiation had three concrete protocol bugs, each of which makes a source give
up before a single frame is sent:

1. **`GET_PARAMETER` ignored what was asked for.** The old code always returned one fixed blob.
   A source asks for a *specific list* and validates the reply against it. Windows in particular
   asks for `wfd_uibc_capability` and `wfd_standby_resume_capability`, and drops the session when
   they are missing. PhairPlay now answers exactly the requested parameters, in order, and
   includes both.
2. **`wfd_video_formats` was malformed.** It advertised profile `02` and level `10` — 2 is not a
   defined H.264 profile and 10 is not a defined level. It now reports Constrained Baseline /
   level 4.2, which is what the advertised CEA resolution bitmap (`0001FFFF`, every mode up to
   1080p) actually needs.
3. **`OPTIONS` under-reported its methods.** The `Public:` header listed only
   `org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER`. Sources read that before using `SETUP` and
   `PLAY`; it now lists every method this receiver implements.

`SET_PARAMETER` is also parsed now rather than blindly 200'd, and the source's
`wfd_presentation_URL` / `wfd_trigger_method` are echoed back in later replies. Parameters the
source tries to set that we do not honour — its own video formats, audio codecs or RTP ports —
are deliberately ignored rather than echoed: promising a codec we do not decode would have the
source stream in a format we then drop.

## First, work out whether your TV can do Miracast at all

This is the important part, and it is **not** a PhairPlay bug.

Android's public Wi-Fi P2P APIs can advertise a WFD service and can run discovery, but they
cannot silently accept an incoming P2P connection the way a system Miracast sink does — the
system's Wi-Fi Display implementation lives in the platform and uses hidden APIs
(`WifiDisplayController`) that third-party apps cannot reach.

So:

- **Windows 10/11** — Connect (Win+K) will usually find the TV. The connection may still need to
  be accepted once, and some TV builds show a Wi-Fi Direct invitation dialog.
- **Android phones** — vendor-dependent. Works on a good number, fails on others, and the failure
  is in the Wi-Fi Direct handshake before PhairPlay is involved.
- **If the TV already ships a "Screen mirroring" / "Wireless display" feature**, use it. It has
  the platform access PhairPlay cannot get. PhairPlay's Miracast receiver is for TVs that do not.

Quick checks from `adb logcat -s PhairPlay` right after pressing Start:

```
MiracastReceiver starting
WifiP2pManager initialized — registering P2P service
Miracast WFD P2P service advertised        ← good: the TV can advertise
Wi-Fi Direct discovery running
```

If instead you see:

| Log line | Meaning |
|----------|---------|
| `WifiP2pManager not available on this device` | The TV has no Wi-Fi Direct stack at all. Nothing an app can do. |
| `… missing Wi-Fi Direct permission` | Grant location / nearby-devices to PhairPlay and restart it |
| `WFD P2P service registration failed, reason=N` | Wi-Fi Direct is present but refusing; usually Wi-Fi is off or the TV is in a bad P2P state |
| `WFD RTSP server listening on port 7236` but nothing after | The TV is advertising; the source is not completing the P2P connection |

## Troubleshooting

**The TV never appears in the source's list**

1. Wi-Fi must be **on** on the TV, even when it is wired over Ethernet — Wi-Fi Direct needs the
   Wi-Fi chip.
2. Grant the location/nearby-devices permission PhairPlay asks for on first run. Android requires
   it for Wi-Fi Direct, and without it `addLocalService()` fails silently.
3. Press **Restart** on PhairPlay's Home screen. Wi-Fi Direct discovery stops itself
   periodically; PhairPlay re-triggers it every 30 s while advertising.

**The source connects but the screen is black**

- Turn on **Settings → Debug overlay** and start the session again. The HUD shows
  `DECODE ready` once the decoder is configured, plus `fps`, `kbps` and the queue depth. If it
  stays on `waiting for SPS/PPS + surface` with a non-zero `kbps`, the TV is receiving video it
  cannot decode.
- `SRC Miracast · mm:ss` with `DECODE ready` and a rising `in` counter means video is flowing and
  the problem is elsewhere.

**It worked once and now it doesn't**

Wi-Fi Direct groups are sticky on some TV firmware. Press **Restart** on PhairPlay's Home screen,
then toggle Wi-Fi off and on once on the source.

## Still stuck?

Open an issue with the TV model, Android TV OS version, the source (Windows version / phone
model), and `adb logcat -s PhairPlay` output from one connection attempt —
`tools/collect-device-logs.sh` captures it.
