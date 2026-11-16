# Luna

Luna is an IPTV player for Google TV, Android TV and Fire TV. It plays the Xtream login or M3U playlist you already
have from your TV service, with a fast TV guide, films and series, search, recording and a teal look built for the
remote. It does not provide any channels or content.

## Install

On the TV, install the free **Downloader** app, open it and enter:

```
github.com/zchchog/luna/releases/latest/download/luna.apk
```

Press Go, allow Downloader to install apps when asked, then Install. Open Luna and add your Xtream login or M3U link.

Updates arrive by themselves: when a new version is out, Luna shows an update pop-up when it opens (Settings › About ›
"Check for updates when Luna opens").

Fire TV: works on Fire OS 7 and 8 sticks. Fire TV devices running Vega OS (Fire TV Stick 4K Select, Fire TV Stick HD
2025 and later) can't install Android apps.

## Features

- **TV guide** with programme details and a live preview.
- **Films and series**: posters, plots, seasons and episodes, resume where you left off.
- **Search** across channels, films and series.
- **Your categories**: favourites, your own groups, hide or reorder.
- **Recording** to the box, a USB drive or a network share (Settings › Playback › Recording).
- **Backup and restore**: logins are never written to the backup file.
- **Private**: no ads, no analytics. Logins are encrypted on the device.

## Build from source

You need JDK 17 to 21 and the Android SDK (platform 35), or Android Studio.

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with the key described in `~/.config/iptv-tv/keystore.properties` (path overridable with
`IPTV_KEYSTORE_PROPERTIES`); without it they fall back to the debug key.

## Credits and licence

Luna is a modified version of **IMD TV**, the free and open source IPTV player by
[majorleeevil](https://github.com/majorleeevil/imd-tv). Like the original, it is licensed under the
[GPL-3.0](LICENSE): you may use, study, change and share it, and shared changes must stay under the same licence.
Fonts: Inter and Montserrat (SIL Open Font License, see `licenses/`).
