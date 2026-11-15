# PocoCast

Cast a link, or a YouTube video, from an Android phone to an Apple TV 4K over AirPlay.

The Apple TV has no Google Cast and Android has no AirPlay, so the phone has to
speak Apple's protocol itself. This app does that: it resolves a link to a
playable stream, serves it over the LAN when the TV cannot fetch it directly,
and drives an AirPlay session with the TV.

## Status

The APK builds and installs. It has **not** been verified against a real Apple
TV from this machine, so treat the first run as a test: report what the status
line says and whether the TV starts playing.

## How it works

1. **Discovery** - `NsdManager` finds `_airplay._tcp` devices on the same Wi-Fi.
2. **Pairing** - try the stored accessory key with `pair-verify`; otherwise the
   open/transient exchange; otherwise a PIN pair-setup (M1-M6) and a fresh
   `pair-verify` for the session key. The Ed25519 identity seed and the TV's
   public key are kept, so only the first cast asks for a PIN.
3. **Session** - SETUP (with a timing server) → event channel → RECORD → a
   second SETUP for the remote-control stream, then the tvOS 26+ play-queue
   commands (`insertPlayQueueItem`, two `setProperty`, `setRate`). If that path
   fails the legacy `/play` + `/rate` path is used instead.
4. **Media** - YouTube is resolved with NewPipeExtractor. If it offers an HLS
   manifest, the TV plays it directly (1080p, nothing done on the phone).
   Otherwise a 1080p H.264 track and an AAC track are downloaded and joined
   into an MP4 with `MediaMuxer`. A link the TV can fetch itself is handed over
   as-is; a local-only or Matroska stream is downloaded, remuxed and served by a
   small Range-capable HTTP server on the phone.

## Build

The sender library is not committed (it is noncommercial-licensed), so fetch it
first:

```bash
bash scripts/bootstrap.sh
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

Requirements: JDK 17, Android SDK platform 33 and build-tools 33.0.2.

## Limitations

- No screen mirroring: AirPlay mirroring adds ~4 s latency and would carry ads.
- No DRM: FairPlay-protected content is out of scope.
- Subtitles are not carried into the joined MP4.
- YouTube resolution depends on NewPipeExtractor staying current with YouTube.
- Nuvio streams work when the app exposes an HTTP(S) URL; a torrent handle that
  only exists inside Nuvio cannot be intercepted by anything.

## Licences

- **AirSonic sender** (`sender/`, fetched by the bootstrap script): PolyForm
  Noncommercial License 1.0.0. Personal and other noncommercial use is free;
  commercial use requires the author's written consent.
- **NewPipeExtractor**: GPL-3.0. It is used unmodified as a dependency.
- The rest of this repository is the project's own code.
