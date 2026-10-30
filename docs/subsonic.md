# roma: Subsonic backend (plan, 2026-10-30)

roma gets a second backend next to YouTube Music: any Subsonic or OpenSubsonic server, and first of all oasis (~/dev/oasis), the user's server that merges the local library with YouTube Music and later other services. Romanization and the fixed controls stay the point of the app; they work on any track's metadata. Decided with the user on 2026-10-30.

The music stack:

- **oasis**: a Python OpenSubsonic server. Clients never see where a track comes from. Every response carries `"type": "oasis"` in `subsonic-response`.
- **mirage**: a fooyin fork for the desktop (another thread).
- **roma**: this app, with either backend.

## Decisions

- One codebase. YouTube Music and Subsonic are two implementations of one small interface (`Source`) under the screens. No screen names a backend; a screen asks the source for what it shows, and the source says what it can't do (a dimmed control, a hidden hint). Separate builds, or a combined mode with both at once, can come later and must stay cheap: every id says which source it belongs to (Ids below), so a queue, a ban or a scrobble already finds its source per song.
- On first launch roma asks which backend to use: YouTube Music, or a Subsonic server (URL, user, password). Existing installs stay on YouTube Music without asking.
- Library feel on Subsonic: the whole library index (artists, albums, songs, cover ids) is synced into a local store, so browsing and search are instant and work offline.
- Each play is reported exactly once, by the side that knows the real source (Scrobbling below).

## The source interface

`Source` (shared/…/Source.kt). `Sources.chosen` is the backend the screens browse (Home, Search, Library); `Sources.of(id)` is the source that owns an id, for everything about one song (stream, like, lyrics, scrobble, radio). Today one of the two is chosen; a combined mode would only change `chosen`.

| What | YouTube Music (`YouTubeMusic`, the code that is there now, moved) | Subsonic (`SubsonicSource`) |
|---|---|---|
| Home | `YouTube.home` pages; the personal mixes row; Liked music | Shelves from `getAlbumList2` (recently added, recently played, most played, random), one page. Pinned tiles: Starred and Shuffle all; no mixes. |
| Search | `YouTube.search` with filters, spelling correction, suggestions; "Seen before" from the local index | The synced index, romanized forms included (`matchDistance`, as "Seen before"). Same filters; Playlists from the playlist list. No suggestions, no correction line, no "Seen before" section (the whole result is local). |
| Library tabs | Playlists, Albums, Artists from the account | Playlists (`getPlaylists`), Albums and Artists from the index. |
| Album, artist, playlist pages | `YouTube.album/artist/playlist` | Album and artist from the index (instant, offline); playlist from `getPlaylist`. An artist page lists the artist's albums and plays all their songs. |
| Stream | `Streams.audio` (VISIONOS, then InnerTubeX), loudness | `stream?id=…`, no transcoding asked; no loudness (ReplayGain later). |
| Radio and queue | YouTube's autoplay (`next`), related artists for the leash | `getSimilarSongs2` on the seed's artist; when the server has none (oasis today), random songs from the index. The same filters and leash (`filterRadio`). |
| Mixes, Liked music | Personal mixes, `LM` | Starred songs (`getStarred2`) for the Liked tile; Shuffle all plays the index at random. |
| Like | `likeVideo`, `likeStatus` | `star`/`unstar`; the status from the song's `starred`, kept from the sync and from `getSong`. |
| Lyrics: the reference provider | `YouTubeLyrics` (ANDROID_MUSIC browse) | OpenSubsonic `getLyricsBySongId`, only when the server lists the `songLyrics` extension. The third-party providers run as before. |
| Comments, Share | YouTube comments; a YouTube link | None: both buttons dimmed, in place. |
| Scrobbling | Last.fm from roma | Subsonic `scrobble`; Last.fm from roma only where the rule below says so. |
| Covers | Google-hosted URLs, sized in the URL | `subsonic:<coverArt id>` in the song; `Images` turns it into a `getCoverArt` URL with fresh auth and `size`. No credentials in stored songs. |

What stays as it is: romanization (every name enters through `Romanizer` once, as before), the lyrics rules, the queue sections, the filters, bans, corrections, the player, the controls.

What Subsonic can't provide, and what the UI does:

- No home feed and no personal mixes: Home shows the server's album lists and two fixed tiles. "Your mixes" becomes "Your library" on Subsonic.
- Similar songs only through `getSimilarSongs2`. A server without them (oasis today answers an empty list) gets radio from the index at random, leash applied, so "close" still keeps the seed's artist.
- Lyrics from the server only with OpenSubsonic `getLyricsBySongId`; otherwise the third-party providers alone, under rule 5 (no reference text).
- No comments and no share link; their slots stay, dimmed (a control never moves).
- No spelling correction or suggestions in Search; the local search tolerates typos already.

## Ids

A Subsonic id is stored as `s.` + the server's id, with every character other than `A–Z a–z 0–9 _ -` written as `.` and two hex digits per UTF-8 byte. YouTube ids never contain a dot, so `Sources.of(id)` needs no lookup; the stored id is safe as a file name on Windows (lyrics cache, desktop audio cache) and in roma's `roma://<id>` media URIs. Songs, albums, artists and playlists all use it. Existing YouTube data (queue, bans, played, corrections, liked) is unchanged.

One server at a time: changing the server's URL or user drops the index and the server's pages. A combined mode with several servers would put a server tag after `s`.

## First launch and settings

- First launch: "Where does your music come from?" before the scripts question (`SetupScreen`): YouTube Music, or a Subsonic server with URL, user and password. Continue on a server pings it (`ping`, then `getOpenSubsonicExtensions`) and says what failed in plain words (no connection, wrong password, not a Subsonic server). An install where `setup.done` is set already is on YouTube Music and isn't asked.
- Settings: a "Music source" category, first in the list, shows the backend and the server. It switches between the two, edits the server, shows the last sync and its counts, and has "Sync now". The Subsonic page also holds the per-server Last.fm switch (Scrobbling below).
- Prefs (user storage, like the YouTube cookie): `source` (`ytm` or `subsonic`), `subsonic.url`, `subsonic.user`, `subsonic.password` (token auth needs the password itself; it never leaves the device except as `md5(password + salt)`), `subsonic.lastfm`. The detected server type and extensions go to the cache with the index.
- Switching the source starts Home, Search, Library and the open pages over (`Store.dataChanged`). The queue stays: each song plays from its own source.

## Index sync

- Core class `SubsonicIndex` (roma.core, unit-tested): artists, albums and songs with their original fields (id, title, artist, artist id, album, album id, track, disc, year, duration, cover id, starred, created).
- Sync: `search3` with an empty query (`query=""`, which oasis, Navidrome and others read as "everything"), paged with `songCount=500&songOffset=…`, `albumCount`/`albumOffset` and `artistCount`/`artistOffset` the same way, until every list comes back short. One full pass; Subsonic has no change feed. Albums and artists not seen in the songs (an empty album) are kept as the server lists them.
- When: at start, after the first frame, if the last sync is older than 6 hours; on "Sync now"; after the server changes. A sync runs in the background and the old index serves until the new one is complete; a failed sync keeps the old one and says so in Settings.
- Store: cache/subsonic/index.json (it can be fetched again, so cache, per design.md's Storage rule). Then every name is romanized once in the background, as roma `Song` and `Item` values (`Romanizer.song` for Subsonic songs), and kept in cache/subsonic/romanized.json stamped with the romanization version and app version, like the Names cache. A romanization change romanizes the index again in the background; until then the old forms show.
- Search, Library, album and artist pages read the romanized index in memory. Starred songs from the sync go into the liked set.

## Scrobbling

Each play is reported exactly once, by the side that knows the real source. The decision is made per song, from `Sources.of(song.id)`, so a combined mode needs nothing more.

- YouTube Music songs: roma scrobbles to Last.fm itself, as now.
- Subsonic songs on oasis (`type == "oasis"` in any response): only Subsonic `scrobble`. `submission=false` when the song starts (now playing), `submission=true` once `shouldScrobble` holds. oasis passes the play on to Last.fm, ListenBrainz and YouTube Music history. roma's own Last.fm scrobbling is off for these songs, whatever the setting.
- Subsonic songs on other servers: always `scrobble` as above. roma's Last.fm scrobbling as well only with the per-server switch "Also scrobble to Last.fm from roma", off by default, because Navidrome and others forward to Last.fm themselves.
- Offline: finished plays go into the timestamped queue (`ScrobbleQueue`, one file per target: scrobbles.json for Last.fm as now, subsonic-scrobbles.json for the server). The server's queue is sent as `scrobble` calls with `submission=true` and `time` in milliseconds, one call per batch of up to 50 ids with their times; oasis stores that time. Now-playing calls are never queued.
- Known gap, not in scope: with the YouTube Music backend roma never reports plays to YouTube Music history, only to Last.fm.

## Step order

Each step is a commit (or a few) on the `subsonic-source` branch; the app works after every one.

1. This plan.
2. `Source` extracted, `YouTubeMusic` behind it; no behaviour change. Screens and `Playback` call the source; the YouTube calls move unchanged.
3. `SubsonicClient` in core: auth (salt and token), JSON responses, errors, the calls above. Unit tests against a local test server (the JDK's HttpServer) with canned responses.
4. `SubsonicIndex` and the sync, with unit tests (paging, short pages, starred, id encoding).
5. `SubsonicSource` in shared: pages, search, radio, likes, lyrics, streams and covers from the index and client. Android's player gets the extractors for local formats (MP3, FLAC, Ogg, WAV, ADTS AAC).
6. First-launch backend picker and the Music source settings.
7. Scrobble routing, with unit tests for the decision and the server's queue.
8. Checked on the emulator against a real oasis server with a generated test library (Japanese, Korean, Chinese, Cyrillic and Greek tags).

## Checked on the emulator (2026-10-30)

Against oasis (commit 18b9e5e, run from its venv) with a generated library of 15 songs in 7 albums: Japanese, Korean, Chinese, Russian, Greek and English tags; MP3, FLAC, Opus in Ogg and AAC in M4A; folder covers. From the AVD the host is 10.0.2.2.

- First start on a cleared install: the source question, the scripts question, then Home. An existing install went straight to its YouTube Music queue, and YouTube Music still searched, played, found lyrics and loaded comments.
- Connect: a wrong password says "Wrong user or password."; the right one syncs the library (15 songs, 7 albums, 7 artists).
- Home's tiles and shelves (recently added from the index, recently and most played from the server), Library's albums and artists, album and artist pages, all romanized: Yoru ni Kakeru, Gunjō, Bampyeonji, Qíng Tiān, Gruppa krovi, Ta tragoudia mou.
- Search over the index: "yoru ni" finds 夜に駆ける, "gunjo" finds 群青.
- Playback of all four formats, the album queued and radio from the library after it; Like stars the song on the server; Starred plays the starred songs; Comments dimmed.
- Scrobbling: a play past half the song reached oasis once, with the start time roma sent. With oasis stopped, the play waited in subsonic-scrobbles.json; after oasis came back, roma's next start sent it, and oasis stored the original time.
- With oasis stopped, Search and Library still worked from the index.

## Known limits

- Desktop: the shared code runs, but the desktop player decodes only Opus in WebM and AAC in MP4. MP3, FLAC and Ogg files from a server need decoders there (or a server that transcodes); Android plays them through MediaCodec.
- No ReplayGain yet: Subsonic songs play at their own level.
- Names get machine romanization only: a server has no official Latin names, so 米津玄師 reads "Yonetsu Genshi" (the dictionary's reading) where YouTube gives "Kenshi Yonezu". A correction for that artist fixes it.
- oasis today answers `getSimilarSongs2` with nothing and has no `getLyricsBySongId`, so its radio is the library at random and its songs' lyrics come from the third-party providers only.
