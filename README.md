# roma

An Android client for YouTube Music. It romanizes every non-Latin title, artist and lyric line, and its controls stay in the same place.

Tiny and fast: the whole app is a 2.1 MB download; romanizing Japanese needs an optional dictionary, downloaded separately. Install it and tap around; that shows the speed better than a benchmark.

- **Romanization everywhere:** lists, player, queue, search, lyrics, notification.
  - Japanese: [Sudachi](https://github.com/WorksApplications/Sudachi) with SudachiDict core, Hepburn.
  - Korean: Revised Romanization.
  - Chinese: pinyin.
  - Cyrillic: per-language national standards (Russian, Ukrainian, Belarusian, Kazakh, Kyrgyz, Serbian, Macedonian, Bulgarian, Mongolian).
  - Greek and others through ICU.
  - Official Latin names win; user corrections can be scoped to a string, a song or an artist.
- **Fixed controls:** a control never moves or disappears. If it isn't available, it's dimmed in place.
- **Lyrics:** all providers are queried in parallel and one result is chosen deterministically against YouTube Music's own text. Word-level karaoke on the original and the romanization, plus a translation layer.
- **Playback:** the current song keeps playing until the next one can start. Optional radio drift limits, duplicate and ban filters.
- **Last.fm scrobbling** with the original metadata, using your own API key.
- **No Google Play Services needed.**

The design and its rules are in [docs/design.md](docs/design.md).

## Build

Requirements: JDK 21 and the Android SDK (compileSdk 37).

```sh
./gradlew :core:test :app:assembleDebug
```

The core tests and the dictionary app need SudachiDict: their first build downloads it (about 77 MB, checked against a pinned sha256) into the Gradle cache. Building roma itself doesn't.

Release builds are made with `tools/release.sh`: R8 shrinking, Zopfli-recompressed, signed with a key that you supply in `keystore.properties` (not in the repository). A merge to master that sets a new `versionName` builds and publishes it as a GitHub release (`.github/workflows/release.yml`); there's no tag to push.

## Install

Get `roma-<version>.apk` from [Releases](https://github.com/zakius/roma/releases). If you want Japanese romanized, roma offers to install its Japanese dictionary on the first start: a separate app (`roma-dict-…`, a 31 MB download, 63 MB installed), so roma's own updates stay small.

## Credits

- YouTube Music API: the `innertube` module from [Metrolist](https://github.com/MetrolistGroup/Metrolist).
- Playback: [Media3](https://developer.android.com/media/media3).
- Skipping ASUS's font rebuild at start: [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass).
- Japanese: [Sudachi](https://github.com/WorksApplications/Sudachi) and [SudachiDict](https://github.com/WorksApplications/SudachiDict).
- Lyrics: LRCLIB, KuGou, NetEase and YouTube Music.

Not affiliated with YouTube or Google.

## Licence

GPL-3.0; see [LICENSE](LICENSE).
