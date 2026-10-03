# Google Cast — why PhairPlay does not receive it

**PhairPlay has no Google Cast receiver. That is deliberate, and since 1.6 it is not even an option.**

## The TV already owns Cast

Every Google TV ships with Chromecast built-in, and that system receiver permanently owns:

| What | Owner |
|------|-------|
| TCP **8008** (DIAL) and **8009** (castv2) | the TV's built-in Chromecast |
| the `_googlecast._tcp` mDNS record | the TV's built-in Chromecast |
| UDP **1900** (SSDP) | the TV's built-in Chromecast |

Senders dial those exact ports and that exact service name. A third-party app cannot take them
over, and binding them "anyway" is what used to make PhairPlay show a permanent red card —
*"Ports 8008, 8009 are already in use"* — while its mDNS registration collided with the TV's own.

An earlier release tried to be a Cast receiver as well (the built-in DIAL/castv2 bridge, plus a
Play-Services Cast Connect receiver with a hand-registered Cast App ID). It never worked on a real
Google TV for the reason above, and it did harm to the one protocol PhairPlay is actually for, so
1.6 removed it: the bridge, the receiver, the app-ID build flag, the Settings toggles and the third
card on Home.

## What to do instead

| You want to… | Use |
|---|---|
| Mirror an iPhone/iPad/Mac screen (including any app's video) | **AirPlay** — Control Centre → Screen Mirroring → *Apple TV* (the name PhairPlay advertises). This is what PhairPlay is for |
| Play a URL/audio from an iOS app on the TV | AirPlay audio (the same picker, or the app's AirPlay button) |
| Cast from an Android phone or a Chrome "cast" button | the TV's **built-in Chromecast** — it is already a Cast target; PhairPlay stays out of the way |
| Netflix/YouTube/Disney+ on the big screen | the **app installed on the TV**, or its own cast button → the built-in receiver |
| Mirror an iPhone/iPad/Mac screen *and* keep using the TV | **Apple Casting** — the same AirPlay mirroring session, reported by its own card. [APPLE_CASTING.md](APPLE_CASTING.md) |

The one thing PhairPlay cannot do is appear inside another app's cast button: that button searches
for the TV's Chromecast, not for PhairPlay. Any app that offers a cast icon is therefore aimed at
the built-in receiver; anything that offers **Screen Mirroring** is aimed at PhairPlay.

## If mirroring from an iPhone finds nothing

That is an AirPlay discovery question, not a Cast one — the same ports and the same mDNS, but the
other service type. Start with the network line on PhairPlay's Home card
(`Advertising on Ethernet · 192.168.1.42`) and read
[TROUBLESHOOTING.md → Ethernet (wired Google TV)](TROUBLESHOOTING.md#ethernet-wired-google-tv).
