# PocoCast

Plays a video that lives on an Android phone (built for a Poco F6 on HyperOS)
on an Apple TV 4K, by pushing the stream to it over AirPlay.

The Apple TV has no Google Cast support and Android has no AirPlay support, so
something has to speak Apple's protocol from the phone. That is what this app
does: it runs a tiny HTTP server on the phone, finds the Apple TV over mDNS and
asks it to play the file's URL, then sends the play/pause/seek/stop commands.

## What works and what does not

Works: a local video file (gallery, Downloads, a USB stick, a file manager),
a direct link to one (including an HLS playlist, `…/master.m3u8`), or a stream
playing in Nuvio — P2P torrent streams included. Matroska (`.mkv`), MPEG-TS and
AVI, which is what nearly every P2P release is, are repackaged as MP4 on the
phone first, because the AirPlay player refuses those containers. A link shared
into PocoCast from a browser, Jellyfin, Plex, a NAS app or a chat is picked up
automatically, so anything that can hand over a media URL can be cast.

## Nuvio, P2P streams included

Nuvio plays its streams through ExoPlayer and already has a first-class
"Open in external player" action, so it is the good case: the app hands over
the stream URL and PocoCast works out how to get that stream onto the Apple TV.
A P2P source is no different — Nuvio opens the torrent itself and serves the
result from this phone, so by the time PocoCast sees it there is a URL to work
with.

1. Start a stream in Nuvio and let it load, direct source or torrent alike.
2. In the player toolbar tap the "Open in external player" icon (or long-press
   the stream in the list and pick the other player). Nuvio sends `ACTION_VIEW`
   with the stream URL and its MIME type.
3. Pick PocoCast. It says what the stream is: "MKV · 1.42 GB", "MP4 · 700 MB".
4. Tap **Prepare for Apple TV** to copy and repackage a big stream ahead of
   time, or just tap **Play**, which does the same work and starts playback
   when it is done. Both show progress and can be stopped with **Cancel**.

What PocoCast does depends on what it finds:

- **MP4 or HLS the Apple TV can reach** — the URL is handed over as it stands;
  the TV fetches the stream itself and no phone server is involved.
- **MP4 that exists only on this phone** — Nuvio's P2P engine listens on
  `127.0.0.1`, which the Apple TV cannot reach. PocoCast relays it: the TV asks
  the phone, the phone asks Nuvio's engine, and ranges are passed through so
  seeking still works.
- **Matroska, MPEG-TS, AVI, or a container it cannot identify** — almost every
  P2P release. The AirPlay player refuses these, so PocoCast copies the stream
  down, repackages it as MP4, and serves that file. The video and audio
  bitstreams are copied rather than re-encoded, so the picture and sound are
  untouched.

Limits of the P2P path, stated plainly:

- Video and audio are copied, so the Apple TV still has to decode them. H.264,
  HEVC and MPEG-4 all pass through; VP9 and AV1 do not, and no phone-side
  conversion is attempted for them, because re-encoding a film takes hours.
- Audio an MP4 cannot carry — DTS, TrueHD, FLAC, Opus — is re-encoded to AAC on
  the phone, which keeps the cast audible. That is the one job that costs real
  time, a minute or two for a feature film. If this device cannot decode the
  track at all, the video is cast silently and the status line says so.
- Multi-audio releases give you the first usable track.
- Subtitles are not carried into the MP4, and there is no track to switch. Burn
  them in, or use the TV's own player for those titles.
- The conversion reads the stream at full speed once: the whole file passes
  through the phone. Keep PocoCast in the foreground, and keep Nuvio open too —
  with its P2P engine gone, the stream it was serving is gone with it.
- A `headers` extra is honoured: the phone adds those headers when it fetches
  the stream itself, which is why such a source is relayed rather than handed
  over. AirPlay cannot send headers, so only the relay can.
- An HLS playlist that exists only on the phone is relayed whole, with every URI
  inside it rewritten to point back at the phone.
- DASH (`.mpd`) cannot be cast, and a magnet link on its own is not a stream:
  PocoCast says so and asks for the "Open in external player" handoff instead.

## YouTube in Brave

Share the video's link from Brave to PocoCast, or paste it into the link field.
PocoCast asks a Piped or Invidious front-end for the video's own streams and
casts those, which is why there are no advertisements: the YouTube player page
is never loaded, so there is nothing to advertise on.

- A 1080p H.264 video track and an AAC audio track are downloaded and joined
  into one MP4 on the phone — the best picture YouTube offers, at the cost of a
  download the length of a P2P one.
- When only a single muxed file is on offer, it is relayed through the phone as
  it stands.
- **Prepare for Apple TV** does the download and the joining ahead of time, and
  **Play** does the same work before starting. Both show progress.

What does not work here, and why it is not a limit of this app:

- Screen capture of Brave would give a picture with no sound. Brave's Android
  manifest sets `android:allowAudioPlaybackCapture="false"` (inherited from
  upstream, still the case in the open Brave issue), so every third-party
  screen recorder or mirroring app gets silence; users report exactly that on
  Android 13 and 14. Resolving the stream instead of capturing it avoids the
  problem entirely, and keeps the real audio track at full quality.
- The front-ends are run by volunteers. They are rate limited, some refuse
  datacentre or foreign addresses, and one that worked last week can be down
  today — all of the ones this app ships with refused the build machine while
  this was written, which is why the failure is reported rather than hidden.
  PocoCast tries each in turn. To use your own, put its address first in the
  list in `YouTube.kt`. If none answers at all, open the same video on an
  Invidious or Piped site in Brave and cast the stream link it gives you, which
  takes the ordinary link path.

Why not Miracast?

- Miracast is Wi-Fi Display. Both ends have to be Miracast/Wi-Fi Display
  certified and it normally runs over a Wi-Fi Direct link.
- Apple never implemented it. The Apple TV 4K's connectivity is Wi-Fi,
  Bluetooth, Thread, Ethernet and HDMI; Miracast is absent from Apple's specs
  and tvOS has no Wi-Fi Display or Wi-Fi Direct (P2P) support for apps.
- Android's own "Cast"/"Wireless display" sheet therefore has nothing to
  discover on an Apple TV, and no app on the phone can register a sink for it.
  Miracast is still useful for TVs and dongles that do support it — your Poco
  already mirrors to those with the built-in screen cast, no app needed.
- The Apple TV's receiving protocol is AirPlay, full stop. That is why this app
  speaks AirPlay instead.

Does not work, by any means available to an app:

- Protected streams from Netflix, Disney+, Prime Video and friends. Those never
  expose a URL a sender may hand to another device, so the service's own player
  is the only route.
- Screen mirroring. That needs the full AirPlay mirroring protocol with FairPlay
  encryption on a live encoded stream, which nothing open-source implements. The
  capture-and-re-encode substitute is silent for any app that opts out of audio
  capture, which is most of them.
- Subtitles. Only the video and one audio track go into the MP4, and the AirPlay
  player has no way to add an external subtitle track mid-cast.
- Files without a `moov` atom at the front start slowly, because the Apple TV
  has to read to the end of the file before it can play. `ffmpeg -i in.mkv
  -c copy -movflags +faststart out.mp4` fixes that; a repackaged stream comes
  out of PocoCast the same way, so the first cast of a large file sends the TV
  back for the end of the file once.

## Before you start

On the Apple TV: Settings > AirPlay and HomeKit, and make sure AirPlay is on
and "Allow Others to AirPlay" (or the equivalent "Anyone on the Same Network"
option) is enabled. Both devices must be on the same Wi-Fi network and the
network must not isolate clients from each other. A guest network usually does.

PocoCast completes AirPlay's HomeKit pairing exchange before it asks the TV to
play anything, because a current tvOS answers `403 Forbidden` to a play request
that arrives on an unpaired connection — which is exactly what this app did
until that exchange was implemented. The exchange uses AirPlay's fixed transient
PIN, so nothing appears on screen and nothing has to be typed. If the TV is set
to require a password, or Allow Access is anything other than "Anyone on the
Same Network", pairing is refused and the status line says so.

## Using it

1. Open the app. Tap **Find Apple TVs**; the Apple TV shows up within a few
   seconds. If mDNS is blocked on your network, type the Apple TV's IP address
   (Settings > Network on the TV) in the field and tap **Use IP**.
2. Tap **Choose video** and pick a file.
3. Tap **Play**. The Apple TV switches to the video within a second or two.
4. **Pause**, **Resume**, **-10 s**, **+10 s** and **Stop** drive the Apple TV
   while the app stays open.

Instead of a file you can paste a link into the field under the video picker
and tap **Cast this link**. PocoCast then probes the link and tells you what it
is, before you commit to anything: an MP4 or an HLS playlist is handed to the
Apple TV as it stands, a stream that only exists on the phone — or that only
answers with particular request headers — is relayed through the phone, a
Matroska or TS stream is copied down and repackaged as MP4 first, and a YouTube
link is resolved to the video's own streams. This is the way to cast a Jellyfin
or Plex direct stream, an IP camera, a P2P stream URL, a YouTube video, or any
MP4/HLS link you have.

An HLS playlist is relayed whole when it needs to be: PocoCast rewrites every
URI it contains — segments, nested playlists, encryption keys — to point back at
the phone, so a playlist that only exists on this phone, or whose segments are
signed for this device, still plays on the Apple TV.

You can also share a video from the gallery or a file manager straight to
PocoCast; the app takes `ACTION_SEND` for `video/*` and `text/plain` (it pulls
the first http(s) link out), and `ACTION_VIEW` for `video/*`, Matroska,
`application/octet-stream`, `application/x-mpegurl` and
`application/vnd.apple.mpegurl`, so it shows up as an external player for an MKV
or an HLS stream as well as for an MP4 URL. A `magnet:` link is accepted only so
the app can tell you it needs the player's own handoff instead.

The phone's HTTP server only lives while the app is running, so keep it in the
foreground during playback.

## Building it

The repository builds itself: every push to `main` runs
`.github/workflows/build.yml`, which assembles the APK, attaches it to a release
tagged `latest`, and also leaves the zipped copy on the Actions tab.

To install, open this on the phone:

    https://github.com/Ron-warrior/PocoCast/releases/latest/download/app-debug.apk

Download it, allow installs from that source when Android asks, and install. The
repository is private, so the browser has to be signed in to GitHub; the release
page is https://github.com/Ron-warrior/PocoCast/releases.

The workflow keeps the debug signing key between runs, so a later build installs
over an earlier one instead of colliding with it. If Android ever reports a
package conflict anyway, uninstall the old PocoCast first — that only loses the
app's cache.

Locally you only need a JDK 17 and the Android SDK:

```
gradle assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## How it is put together

- `MainActivity.kt` – the whole UI, built in code, plus the cast state machine
  that decides whether a stream is handed over, relayed, repackaged, or — for
  YouTube — resolved and joined first.
- `AirPlayDiscovery.kt` – browses `_airplay._tcp` with `NsdManager` and
  resolves each result to an address.
- `LocalMediaServer.kt` – a small HTTP/1.1 server with `Range` support. It
  serves a picked file through the content resolver, a converted file from the
  app's cache, a relay of a URL only the phone can reach or that needs the
  stream's own headers, and rewritten HLS playlists.
- `StreamSource.kt` – asks a stream what it is (container, size) with a one-byte
  range request, and copies it down for repackaging with progress.
- `Remuxer.kt` – repackages Matroska/MPEG-TS/AVI as MP4 and joins a separate
  video and audio track into one file, with `MediaExtractor` and `MediaMuxer`.
  Streams are copied; audio an MP4 cannot hold (DTS, TrueHD, Opus) is re-encoded
  to AAC through `MediaCodec`, and dropped only if the device cannot do it.
- `YouTube.kt` – resolves a YouTube video id to its own streams through a Piped
  or Invidious front-end, preferring 1080p H.264 plus AAC over a muxed file.
- `airplay/AirPlaySession.kt` – the AirPlay sender: `POST /play` (RTSP-style
  with a binary plist body, with an older header-only fallback), `/rate`,
  `/scrub` and `/stop` against port 7000.
- `airplay/BinaryPlist.kt` – the minimal binary plist encoder the play request
  needs.
