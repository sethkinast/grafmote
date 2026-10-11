# Graf Remote

Native Android remote control for Samsung Smart TVs (Tizen, 2016+). LAN-only.
No ads, no analytics, no trackers, no cloud, no account.

Replaces a Hubitat Dashboard page (4–5s load, high per-press latency,
single-TV) with an instant native app. Protocol ground truth is the Hubitat
driver by Dave Gutheinz
(`SamsungTvRemote/SamsungTVRemote.groovy` in `DaveGut/HubitatActive`).

## Features

- D-pad (Up/Down/Left/Right + OK), Back, Home, Volume Up/Down, Mute, Power toggle
- Customizable app-launch buttons (add/edit/delete, label + Samsung app ID).
  Defaults: Jellyfin, Disney+, Netflix, YouTube
- Multiple TVs (nickname + IP/hostname), fast switching via dropdown
- Cold start < 500ms: classic Android Views (no Compose), zero AndroidX deps,
  no custom Application class, no ContentProviders, single OkHttp dependency.
  Buttons are tappable instantly — key events queue until the socket opens
- Pairing tokens persisted per TV; Wake-on-LAN power-on; REST power-state poll
- Haptic tick on every button press (subtle ~15ms); per-TV "Haptic feedback"
  switch in Settings (TV edit dialog), default on — off keeps visual pressed
  states but disables vibration

## Protocol notes

- REST: `http://<tv>:8001/api/v2/` → `device.PowerState`, `device.wifiMac`,
  `device.TokenAuthSupport`, `device.model`, `device.duid`
- Keys: WebSocket `samsung.remote.control` — `wss://<tv>:8002` on token TVs,
  `ws://<tv>:8001` on older ones; frame
  `{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"KEY_X",
  "Option":"false","TypeOfRemote":"SendRemoteKey"}}`
- Token TVs use a self-signed cert; the app trusts it on a dedicated
  OkHttpClient used ONLY for TV sockets (see `TvConnection.kt` comments).
  Same tradeoff as the Hubitat driver's `ignoreSSLIssues:true`.
- Apps: launch via `POST http://<tv>:8001/api/v2/applications/<appId>`
  (this works). The installed-app list is NOT REST
  (`GET http://<tv>:8001/api/v2/applications/list` DOES NOT EXIST — it 404s):
  it is queried over the remote-control WebSocket by sending
  `{"method":"ms.channel.emit","params":{"event":"ed.installedApp.get","to":"host"}}`.
  The TV answers with an `ed.installedApp.get` event carrying
  `[{appId, name, app_type}]` under `data.data` (also accepted directly
  under `data`). Older firmware never answers; the picker treats that as
  "unavailable", not an error.
- Power on: WoL magic packet (UDP 255.255.255.255:7) + `KEY_POWER` Press,
  ~3s, Release. Power off: `KEY_POWER` Click.

## Pairing (first connect per TV)

1. The TV must be ON the first time the app connects.
2. Tap any button. The TV shows an on-screen Allow/Deny prompt —
   select **Allow**.
3. The status line changes to "Connected". The token the TV issues is stored
   on the phone (per TV) and reused from then on; you will not be prompted
   again unless the TV's paired-device list is cleared.
4. If the status says "Approve GrafRemote on the TV", the prompt is still
   waiting (or was missed) — tap a button again to re-trigger it.

## Default app IDs

| Button  | App ID                   | Source |
|---------|--------------------------|--------|
| Jellyfin | `AprZAARz4r.Jellyfin`   | official jellyfin-tizen `config.xml` |
| Disney+ | `MCmYXNxgcu.DisneyPlus`  | Hubitat driver `appIdList` |
| Netflix | `org.tizen.netflix-app`  | Hubitat driver translation table |
| YouTube | `9Ur5IzDKqV.TizenYouTube`| Hubitat driver `appIdList` |

If a button stops working (Samsung changes IDs), use "Add apps" in
Settings — it lists the TV's real IDs over the WebSocket
(`ed.installedApp.get` emit, answered under `data.data`) — and edit the
button — no reinstall needed.

## Build

Requires an x86_64 machine (or any host with a working Android SDK;
SDK build-tools are x86-64-only and cannot run on ARM Linux).

```sh
# point at your SDK, then:
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Sideload the debug APK. `INTERNET` permission is required for LAN sockets;
`usesCleartextTraffic` is required because the Samsung LAN API is plain HTTP.

Debug APKs are signed with a pinned keystore committed at
`app/debug.keystore` (standard `android`/`android` debug credentials — not
secret), so consecutive CI builds install as updates. ONE final
uninstall/reinstall is required the first time after this change: installs
made before it carry ephemeral CI signatures that Android refuses to update
over. After that one reinstall, updates work normally. (Switching to release
signing later is another one-time reinstall — see below.)

## Release signing

No release key material is committed to the repo, ever (the only exception
is the pinned debug keystore above, which uses public standard credentials).
`app/build.gradle` reads the
release signing config ONLY from environment variables, and CI decodes the
keystore from a secret at build time.

One-time setup (on your own machine, not in the repo):

```sh
# 1. Generate the keystore (keep the file + passwords private):
keytool -genkeypair -v -keystore grafmote-release.jks -alias grafmote \
  -keyalg RSA -keysize 2048 -validity 10000

# 2. Add these 4 secrets to the repo
#    (GitHub → Settings → Secrets and variables → Actions → New secret):
#    KEYSTORE_BASE64    <- output of: base64 -w0 grafmote-release.jks
#    KEYSTORE_PASSWORD  <- the keystore password you chose
#    KEY_ALIAS          <- e.g. grafmote
#    KEY_PASSWORD       <- the key password you chose
```

The `build-release` CI job is gated on `KEYSTORE_BASE64` existing, so CI
stays green until the secrets are added. Once they exist, every push builds
a signed release APK alongside the debug one (workflow artifacts).

Local signed build with the same four variables:

```sh
export KEYSTORE_PATH=$PWD/grafmote-release.jks KEYSTORE_PASSWORD=... \
  KEY_ALIAS=grafmote KEY_PASSWORD=...
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

WARNING: switching from the debug signature to the release signature
requires uninstalling the debug app first (Android rejects same-package
installs with a different signature). Uninstalling wipes app data — the TV
list and pairing tokens are lost, and TVs must be re-paired once (see
"Pairing" above).
