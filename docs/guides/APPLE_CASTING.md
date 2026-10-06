# Apple Casting — screen mirroring from iPhone, iPad and Mac

**What it is:** the *Apple Casting* card on Hearth's Home screen. It is the screen-mirroring
half of AirPlay: your iPhone/iPad/Mac renders its display, encodes it as H.264 and sends it to the
TV, where Hearth decodes it in hardware and puts it on screen. Everything you can see on the
phone — Photos, Safari, a game, a video app that does not offer AirPlay streaming — appears on the
TV, and the TV remote's play/pause/skip keys are forwarded back to the sender when the app
supports it.

The card is deliberately separate from the **AirPlay** card next to it, even though both are the
same receiver:

| Card | Lights up when |
|------|----------------|
| **AirPlay** | a sender has an AirPlay session open — streaming audio, video, photos, or mirroring |
| **Apple Casting** | a sender is actually sending **screen video** — i.e. something is on the TV right now |

So "AirPlay: Connected · Apple Casting: Waiting" is a truthful report of *"a Mac is playing music
through the TV"*, not a failure.

---

## In-app AirPlay video is a different path

The instructions below describe **Control Centre screen mirroring**. Choosing Hearth from the
YouTube app's own AirPlay picker is app-level video streaming, not screen mirroring and not Google
Cast. That path uses sender-mediated HLS/FCUP and has a separate compatibility status. See
[the 1.9.1 sender-mediated video note](../CASTING-1.9.1.md); its Apple-device-to-Google-TV first-frame
validation is explicitly marked **NOT RUN**.

## Connecting (30 seconds)

1. Make sure the iPhone/iPad/Mac is on the **same network** as the TV. On a wired Google TV that
   means the same router — the TV's Wi-Fi radio being off is fine and normal.
2. Open **Control Centre** on iPhone/iPad (swipe down from the top-right corner) or the
   **Control Centre** / menu-bar item on the Mac.
3. Tap **Screen Mirroring** (macOS: **AirPlay**).
4. Pick the name the TV advertises — shown on Hearth's Home screen as *Visible as: …*
   (default: **Apple TV**). If two devices share a name, Android renames this one to
   "… (2)", and the Home screen shows the name that was really registered.

There is no PIN and no pairing dialog to accept: Hearth advertises itself as a legacy-pairing
AirPlay receiver and any device on your LAN can connect, exactly like an old Apple TV. If someone
else on your network can reach the TV, they can mirror to it — see *Access control* below.

## Stopping

On the phone: **Screen Mirroring → Stop Mirroring**. On the TV: **Back** on the remote closes the
session (Hearth asks the sender to stop). Locking the phone ends it too — the sender's socket
closes and Hearth tears the session down.

---

## When it does not work: read the connection log

Press the **Apple Casting** card on Home. A dialog opens with every step of the most recent
connection, newest last, for example:

```
12:04:01  Connection from 192.168.1.57
12:04:01  Control channel opened by AirPlay/740.4.12
12:04:01  Discovery: sent the txtAirPlay record
12:04:02  Pairing: verify complete — sender is trusted for this session
12:04:02  Encryption: FairPlay key exchange OK (16-byte phase)
12:04:03  Mirroring: stream key decrypted — starting the media session
12:04:03  Mirroring: video stream set up — the sender can start sending frames
12:04:03  Mirroring: waiting for the sender's video connection on port 54321
12:04:04  Mirroring: sender connected the video stream (192.168.1.57)
```

Where the log **stops** is the diagnosis:

| Last line | Meaning | Fix |
|---|---|---|
| Nothing at all | The sender never reached the TV | Network/interface line on Home: phone and TV must be on the same subnet; a guest Wi-Fi or AP isolation blocks it |
| `Connection from …` then nothing | A socket opened but no request arrived | Usually a stale probe; try again — the log will show a second connection |
| `Pairing FAILED …` | The sender wanted a pairing dialect this receiver does not offer | Update Hearth; capture the log and report it |
| `Encryption FAILED at fp-setup` | FairPlay key exchange rejected | Update Hearth; report the log |
| `Mirroring FAILED at SETUP` | The stream request was malformed or keys were missing | Report the log |
| `… waiting for the sender's video connection on port N` as the last line | Keys and streams are set up, but the sender never opened the video socket | Almost always the network path (AP isolation / VLAN / a firewall) or a full Wi-Fi airtime problem; try 5 GHz |
| Video connects, screen stays black | Frames arrive but decode fails | Turn on **Settings → Debug overlay** and look for `fps`/`queue` on the TV |

The same log is mirrored to `adb logcat` if you are collecting device logs
(`tools/collect-device-logs.sh`).

---

## Notes for a wired (Ethernet) Google TV

* AirPlay is TCP/UDP on the LAN. If the TV is on Ethernet and the phone is on Wi-Fi of the **same
  router**, it works — that is the setup Hearth is developed against.
* The Home screen shows the interface and address actually being advertised
  (`Advertising on Ethernet · 192.168.1.42`). If that line is missing or says *Not advertised
  yet*, the TV's mDNS responder refused the record — press **Restart**; the app retries on its own
  and re-advertises whenever the network changes.
* IPv6/IGMP snooping on some switches can drop mDNS. If the TV never appears in the phone's Screen
  Mirroring list but you know the IP, check whether the TV is reachable with a ping from the phone
  first (an app like `Network Analyzer` can do it) — if not, the problem is the router, not the TV.

## Access control

Hearth intentionally runs **open** (no PIN), because a PIN means HomeKit-style pairing, which
third-party AirPlay receivers cannot complete with a modern iPhone. If you need to keep other
people off the TV, put it on a network only your devices can join (or a dedicated SSID/VLAN) —
that is the only access control AirPlay-without-HomeKit can honestly offer.

## What is *not* Apple Casting

* **Google Cast** (the cast icon in Chrome/YouTube) — that is the TV's built-in Chromecast, which
  always owns TCP 8008/8009. Hearth stays out of its way; see [CAST.md](CAST.md).
* **Miracast / Wi-Fi Display** (Android and Windows "wireless display") — removed in 1.6.1.
  Most Google TVs refuse Wi-Fi Direct to apps, so it could never work on the sets people own; the
  card that used to say *Unavailable* is gone.
