# Grafmote

Native Android remote for Samsung Smart TVs (Tizen, 2016+). LAN-only.
No ads, no analytics, no trackers, no cloud, no account.

## Features

- Full remote: D-pad + OK, Back, Home, volume rocker with mute in its
  center, channel rocker, media keys including separate Play and Pause
- Multiple TVs with LAN scan discovery and fast switching
- Custom app-launch buttons with logos — added from the TV's own
  installed-app list, drag to reorder
- Pairing token stored per TV; per-TV haptic feedback toggle
- Cold start under 500ms: classic Android Views, one networking dependency

## Design decisions

- Play and Pause are separate buttons — Samsung's API offers a play-only
  key and a pause-only key; there is no play/pause toggle key to map to
  one button.
- Power-on is Wake-on-LAN only — KEY_POWER is a toggle, so a network
  "power on" frame cannot act as a discrete on; WoL wakes the TV without
  toggling anything.
- Buttons never wait for a connection — taps queue briefly and flush when
  the socket opens, so the remote feels instant even mid-reconnect.
- The power button follows the TV's reported power state — and a live
  control socket counts as proof the TV is on.

## Protocol notes

- REST `http://<tv>:8001/api/v2/` provides power state, Wi-Fi MAC, token
  support, model, and duid.
- Remote keys go over `samsung.remote.control`: `wss://<tv>:8002` on token
  TVs, `ws://<tv>:8001` on older ones, as `ms.remote.control` Click frames.
- Pairing: the first connect shows an on-TV Allow prompt; the issued token
  is sent as `?token=` on later connects.
- The installed-app list comes from the `ed.installedApp.get` WebSocket
  emit — the REST list endpoint doesn't exist.
- TLS: token TVs use self-signed certs, accepted only by the dedicated
  TV-socket client.

## Pairing

1. Turn the TV on.
2. Tap any button in the app.
3. Select Allow on the TV's prompt.
4. The token is stored per TV and reused — no further prompts.

## Build

```sh
./gradlew assembleDebug   # or assembleRelease
```

CI builds on every push to master and publishes APKs to the rolling
`latest` prerelease. Release signing comes from CI secrets/environment —
no key material in the repo (the pinned debug keystore for debug builds is
the only exception).
