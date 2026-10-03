# Google Cast — casting a video from your iPhone

PhairPlay serves Google Cast itself. You do **not** need to register anything with Google.

## What works

| Sender | Casts |
|--------|-------|
| iPhone / iPad apps that cast a **media URL** (VLC, Plex, Infuse, file/photo apps, Chrome) | ✅ |
| Anything using the **Default Media Receiver** (`CC1AD845`) | ✅ |
| Android Cast senders casting a media URL | ✅ |
| **DIAL senders** — Netflix, YouTube, Windows — that find devices with SSDP `M-SEARCH` | ✅ 1.5+ |
| Apps installed on the TV that a DIAL launch can start — **Netflix, YouTube, Spotify, Disney+…** | ✅ 1.5+, see [DIAL hand-off](#dial-hand-off-the-tvs-own-app) |
| Apps that need their own registered receiver *and* speak a private namespace afterwards | ❌ |

That last row is the honest limit, and it is smaller than it was. Some apps launch their own
receiver (YouTube's is `233637DE`) and then speak a private namespace only that receiver
understands. No open-source receiver can decode that. What changed in 1.5 is that PhairPlay now
**tells you that is what happened** instead of sitting on a black screen: the Cast card says the
app is using a private channel and suggests Screen Mirroring.

## DIAL hand-off: the TV's own app

A real smart TV does not try to emulate Netflix. Its DIAL server starts the **Netflix app that is
installed on the TV**, and the phone then drives that app over Netflix's own cloud.

PhairPlay does the same thing. When a sender `POST`s `/apps/<name>` and that app is installed on
the TV, PhairPlay launches it and reports `running` — the sender is talking to the real app from
then on. When it is *not* installed, or the name is a generic receiver (`CC1AD845`, VLC, Plex),
PhairPlay plays the URL itself as before.

Recognised names: Netflix, YouTube, YouTube TV, YouTube Kids, Spotify, Disney+, Prime Video,
HBO Max, Hulu, Peacock, Paramount+, Plex, Twitch, Crunchyroll, VLC, Kodi, TED, Vimeo, Tubi,
Pluto TV, DAZN, SoundCloud, Deezer, TuneIn, Pandora, BBC iPlayer.

Two things follow from that:

- **The app must already be installed on the TV.** Sideload it or install it from the Play Store.
- **The phone controls the TV app, not PhairPlay.** If the app needs a "link with TV code" step
  (YouTube does), the code appears on the TV — that is the app's own flow, working as designed.

## SSDP discovery (why Netflix and Windows could not see the TV before 1.5)

Google Cast senders browse mDNS for `_googlecast._tcp`. **DIAL senders do not.** They send a UPnP
`M-SEARCH` to `239.255.255.250:1900` asking for `urn:dial-multiscreen-org:service:dial:1`, and
only talk to devices that answer.

PhairPlay advertised over mDNS and served DIAL over HTTP, but never answered SSDP — so to every
DIAL sender the TV simply did not exist. It now joins the SSDP multicast group and answers with
a `LOCATION` pointing at its device description on port 8008.

The responder is best-effort: port 1900 is often already bound (the TV's own Chromecast holds it
on a Chromecast with Google TV, and PhairPlay then says so on the Cast card), and some routers
filter multicast. When SSDP cannot be served, Cast keeps working for the senders that use mDNS.

## How it works (and why it used to say "error")

Google's official path is the **Cast Connect SDK**, which refuses to start without a Cast
Application ID issued after registering `com.phairplay.googletv` in the
[Cast SDK Developer Console](https://cast.google.com/publish). Until someone does that,
PhairPlay's Cast card could only ever report an error — which is what you saw in 1.2.

So PhairPlay now speaks the Cast protocol directly, the way a Chromecast does:

```
mDNS  _googlecast._tcp      ← how the iPhone's Cast picker finds the TV
DIAL  HTTP  :8008           ← "what device are you, and what's running?"
castv2  TLS :8009           ← CONNECT → GET_STATUS → LAUNCH → LOAD <url> → PLAY
MediaPlayer                 ← plays the URL on the TV
```

Senders accept any TLS certificate, so the self-signed one shipped in
`app/src/main/assets/castv2-server.p12` is fine.

**Settings → Google Cast Receiver → Built-in Cast bridge** controls it (on by default). If you
later register an app ID and build with `-Pphairplay.castAppId=…`, PhairPlay switches to the
official SDK automatically and the bridge stays off — two receivers advertising the same IP
would make the picker show duplicates.

## What a well-behaved receiver does (all new in 1.5)

- **Replies go to the sender that asked.** Previously every reply was broadcast to every
  connected phone, so a second device was flooded with statuses addressed to the first.
- **Failures are answered, not swallowed.** A `LOAD` with no URL, or a media command with no
  session, now gets `LOAD_FAILED` / `INVALID_REQUEST`. A sender waiting on a reply that never
  arrives shows "connecting…" forever.
- **Queue commands work.** `QUEUE_LOAD`, `QUEUE_NEXT`, `QUEUE_PREV`, `QUEUE_INSERT`,
  `QUEUE_REMOVE`, `QUEUE_UPDATE` and `QUEUE_GET_ITEMS` are implemented, so senders that build a
  playlist rather than loading a single URL get real answers.
- **`MEDIA_STATUS` is pushed once a second while playing.** The phone's progress bar and elapsed
  time are driven entirely by those messages; before, they only arrived on a state change, so the
  scrubber froze mid-playback.
- **Rebuffering is reported.** `BUFFERING` replaces a frozen `PLAYING` while the TV refills.

## Ethernet (wired Google TV)

Nothing in PhairPlay's Cast or AirPlay receivers assumes Wi-Fi — ports are bound to all
interfaces and discovery uses mDNS, which works over Ethernet. **The Home screen shows exactly
which network PhairPlay is advertising on**, e.g. `Network: Ethernet · 192.168.1.42`. Use that
line first:

1. **Is the iPhone on the *same* network as that address?** If the TV is wired and the phone is
   on Wi-Fi, they are only the same network if your router bridges wired and wireless into one
   subnet. On many routers they are separate, or "AP isolation" / "guest network" is on.
2. **Does multicast cross between them?** Cast and AirPlay discovery is multicast DNS. Some
   routers block multicast between the wired and wireless segments, or between VLANs. Nothing
   an app can fix — the phone simply never sees the advertisement.
3. **Is the TV also joined to a Wi-Fi network?** Then it has two addresses. PhairPlay advertises
   on the one it is reachable by, and the Home screen tells you which. The phone must match
   *that* one.

Quick checks from another computer on the same network:

```bash
dns-sd -B _googlecast._tcp          # macOS: list Cast receivers
dns-sd -B _airplay._tcp             # macOS: list AirPlay receivers
avahi-browse -rt _googlecast._tcp   # Linux
```

If PhairPlay appears there but not on the iPhone, it is multicast/segmentation. If it does not
appear at all, PhairPlay is not advertising — press **Restart** on the Home screen and check
the Cast / AirPlay cards for an error.

### The TV's own Chromecast is probably already running

Almost every Google TV ships with **Chromecast built-in**, and that receiver permanently owns
TCP **8008** and **8009**. Senders always dial those exact ports, so PhairPlay cannot simply
move its own receiver elsewhere — and it does not need to: if the built-in receiver is
listening, *this TV is already a Cast target*.

When PhairPlay finds those ports busy it leaves its own receiver off and says so on the Cast
card (green, not red — nothing is broken):

> Ports 8008, 8009 are already in use — this TV's built-in Chromecast is handling Cast, so
> PhairPlay's receiver stays off. To receive through PhairPlay instead, disable
> *Chromecast built-in* in the TV's app settings.

So: if the iPhone already sees the TV as a Cast target, you are done — use it. Only disable
the built-in receiver if you specifically want PhairPlay to be the thing answering.

Some Google TV devices disable their built-in Chromecast while wired over Ethernet; that is
the case where PhairPlay's own receiver is genuinely useful.

## Troubleshooting the Cast card

| Card says | Meaning |
|-----------|---------|
| **Advertising** + "PhairPlay is serving Cast — no Google registration needed" | Working; the iPhone should list it |
| **Advertising** + "Ports 8008, 8009 are already in use — this TV's built-in Chromecast is handling Cast…" | Nothing to fix: the TV is already a Cast target. Disable *Chromecast built-in* only if you want PhairPlay to answer instead |
| **Error** + "Could not advertise Cast on this network" | mDNS registration failed; press Restart |
| **Disabled** + "No Cast app ID is configured and the built-in bridge is turned off" | Turn on the bridge |
| **Advertising** + "Using the official Cast SDK with a registered app ID" | This build has an app ID; Cast Connect is active |
| Cast connects but there is picture-free audio (or a black screen) | PhairPlay draws onto a Surface owned by its Activity. It re-opens itself when a sender connects, but if the TV refuses background launches, open PhairPlay and leave it on screen before casting |
| **Advertising** + "\<app\> is using a private Cast channel (…) that only its own receiver understands" | The app launched here and then spoke a private protocol. Nothing generic can decode it — use **Screen Mirroring** for this app. If the app is in the DIAL table above, install it on the TV and PhairPlay will hand the launch over instead |
| **Advertising** + "\<app\> is being played by this TV's own app" | Working as intended: the DIAL launch was handed to the installed app, and the phone is now talking to that app |
| **Advertising** + "Could not listen for SSDP discovery on port 1900…" | Something else (usually the TV's own Chromecast) owns port 1900. mDNS-based Cast senders still work; DIAL-only senders will not see the TV |
| The phone connects but the progress bar never moves | Fixed in 1.5 — `MEDIA_STATUS` is now pushed every second while playing |
