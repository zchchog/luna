# Lunaro (personal copy of IMD TV)

A personal, hobby copy of **IMD TV**, the free and open source IPTV player for Android TV by
[majorleeevil](https://github.com/majorleeevil/imd-tv), changed for private use. It is licensed under the same terms
(GPL-3.0), and the original author's work and licence are kept (see `LICENSE`). It plays a playlist you already have
(Xtream login or M3U link) and does not provide any channels or content.

Changes in this copy: renamed Lunaro; one theme (Celestial Glass); a floating-style category panel and a guide that
follows the highlighted programme; a playing menu with pause/play, audio, subtitles, quality, picture size, audio delay
and other versions of a channel; the app always opens on the guide; gentler on the provider (no instant retries of a
refused stream, delayed first sync); the self-updater and the original author's links removed.

## Features

- **TV guide** — seven days of programmes, and free open-source guides (epgshare01) for channels your provider leaves blank.
- **Movies & series** — posters, plots, seasons and episodes, resume where you left off, embedded subtitles in your language.
- **Search** — channels, films and series in one search, or within a single category.
- **Your categories** — favourites, your own groups, hide what you never watch, reorder the rest.
- **Recording** — record live TV now or schedule a programme from the guide, to the box, a USB drive or a network
  share (SMB), and watch it later with resume. Turn it on in Settings › Playback › Recording.
- **Channel logos** — open-source logos (tv-logos), your playlist's, or your own logo pack.
- **Four themes** — Celestial Glass, Celestial +, Celestial Retro and Liquid Glass.
- **Backup & restore** — move your setup to a new box; logins are never written to the backup file.
- **Private** — no accounts, no ads, no analytics. Logins are encrypted on the device.

## Screenshots

Four themes, all with the same fast guide:

| | |
|---|---|
| ![Celestial Glass](site/img/themes/celestial-glass.webp) Celestial Glass | ![Celestial +](site/img/themes/celestial-plus.webp) Celestial + |
| ![Celestial Retro](site/img/themes/celestial-retro.webp) Celestial Retro | ![Liquid Glass](site/img/themes/liquid-glass.webp) Liquid Glass |

| | |
|---|---|
| ![Movies](site/img/movies.webp) Movies | ![Movie details](site/img/movie-detail.webp) Movie details |
| ![Recordings](site/img/recordings.webp) Recordings | ![Recording settings](site/img/settings/recording.webp) Recording settings |

## Recording: known limitations

- A box in deep standby may not wake for a scheduled recording; it is then marked "Missed". If the box wakes while the
  programme is still on, the rest is recorded.
- Recording one channel while watching another needs two connections from your provider. Xtream logins report their
  limit and the app asks before it stops one; with M3U playlists the app just tries, and the recording fails if the
  provider refuses a second stream. (Recording the channel you're watching needs two connections too.)
- Internal storage on small boxes holds only a few hours (a Chromecast with Google TV: about 2 hours of HD). Use a USB
  drive or a network share.
- Recordings on the box or a USB drive are removed if you uninstall the app (they live in the app's own folder; on a
  USB drive that's `Android/data/org.imdtv.iptvtv/files/Recordings`). Network-share recordings stay.
- DASH and encrypted streams can't be recorded. Recordings are saved as received (`.ts`), not converted.
- No live pause and no series recording.

## Install

Requires Android TV / Google TV **8.0 or newer** (tested on Chromecast with Google TV and a Thomson 140 stick).

### Option 1: Downloader (on the TV)

1. On your TV, install the free **Downloader** app from the Play Store.
2. Open Downloader, type the code `7573990` and press Go. When asked, allow Downloader to install apps, then choose Install.
3. Open IMD TV and add your Xtream login or M3U link.

### Option 2: Sideloading (from a computer)

1. Download `imd-tv.apk` from [Releases](#releases) on your computer.
2. On the TV, turn on Developer options and USB or network debugging, then run `adb connect <your TV's IP>` and
   `adb install imd-tv.apk`. Or copy the file to a USB drive and open it with a file manager app on the TV.
3. Open IMD TV and add your Xtream login or M3U link.

## Build from source

You need JDK 17 and the Android SDK (platform 35), or Android Studio.

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest # unit tests
```

Release builds are signed with the key described in `~/.config/iptv-tv/keystore.properties` (path overridable with
`IPTV_KEYSTORE_PROPERTIES`); without it they fall back to the debug key.

## Support

IMD TV is free and always will be. If it's useful to you, you can
[buy me a coffee](https://buymeacoffee.com/majorleeevil) — entirely optional.

## Contact

support@imd-tv.com

## Licence

[GPL-3.0](LICENSE). You may use, study, change and share IMD TV; shared changes must stay under the same licence.

## Releases

- **Latest:** [imd-tv.apk](https://github.com/majorleeevil/imd-tv/releases/latest/download/imd-tv.apk)
  (always the newest version)
- **All versions and change notes:** [github.com/majorleeevil/imd-tv/releases](https://github.com/majorleeevil/imd-tv/releases)
