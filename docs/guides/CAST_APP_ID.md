# Google Cast App ID

PhairPlay cannot invent a Google Cast App ID locally. The ID is assigned by
Google after registering a receiver application in the Google Cast SDK Developer
Console.

## Get the ID

1. Open the Google Cast SDK Developer Console:
   `https://cast.google.com/publish`
2. Register or sign in with the account that should own the receiver.
3. Add a receiver application.
4. For development, register the Google TV / Android TV test device in the same
   console entry before publishing.
5. Copy the Application ID shown by the console.

Google's current registration docs:
`https://developers.google.com/cast/docs/registration`

For an Android TV native receiver, also associate the Android TV package name
with the Cast App ID in the console. PhairPlay's Google TV package is:

```text
com.phairplay.googletv
```

Google's Android TV receiver overview:
`https://developers.google.com/cast/docs/android_tv_receiver/`

## Build with the ID

Use either a Gradle property:

```bash
./gradlew assembleGoogletvDebug -Pphairplay.castAppId=<APP_ID>
```

or an environment variable:

```bash
PHAIRPLAY_CAST_APP_ID=<APP_ID> ./gradlew assembleGoogletvDebug
```

If no ID is supplied, the Google TV build still succeeds — and Cast still works, because
PhairPlay 1.4+ falls back to its own built-in Cast receiver (mDNS `_googlecast._tcp` + DIAL on
8008 + castv2 on 8009), which needs no registration at all. That is the default, and it is
controlled by **Settings → Built-in Cast bridge** (on by default).

With a valid ID, the Google TV flavour starts the official Cast Connect SDK **instead of** the
bridge, and the Cast card says so. Media load/playback handling with the official SDK still
requires hardware validation with matching Cast sender apps and DRM-free test media.

See [CAST.md](CAST.md) for what the built-in bridge can and cannot play (in short: apps that
cast a media URL work; apps like YouTube that need their own registered receiver do not).
