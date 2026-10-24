# roma: design

A YouTube Music client for Android. It shows a romanized form of every non-Latin string, and its controls never move.
Working name; rename freely.

## Scope

- Android first. Test devices: Zenfone 10 and a Huawei MatePad on EMUI, which has **no Google Play Services**. Never depend on GMS.
- Desktop (Linux, then Windows) is a maybe, decided later by cost. The only concession now: modules `core` (data, romanization, lyrics) must not import Android APIs, so a desktop app can reuse them later.
- Language: Kotlin, because Media3 fits best. The user isn't keen on Kotlin, so keep the code plain. No Flutter, no KMP setup now.
- Not in v1: offline downloads (the stream cache is enough), a separate tablet layout (the MatePad uses the phone layout), comments, video playback, the mini player.

## Stack

| Concern | Choice |
|---|---|
| YT Music API (home, search, library, radio, likes, playlists) | `innertube` Kotlin module from Metrolist, vendored into `core` (package `com.metrolist.innertube`, unchanged; a no-op `timber.log.Timber` stands in for its logger). Since October 2026 it is a facade over Metrolist's InnerTubeX library (Kotlin Multiplatform, JVM target, from JitPack), which pulls in QuackJS. Not published on its own, so vendoring is the only option. |
| Stream URLs (signature / n-param / PoToken) | NewPipeExtractor. Its team fixes YouTube changes fastest. |
| Playback, media session, notification | Media3 (ExoPlayer + MediaSessionService) |
| Login | WebView sign-in to music.youtube.com, cookies kept. No GMS account picker. |
| Romanization | Kuromoji with IPADIC (Apache-2.0, about 13 MB; Sudachi needs a 50 MB+ dictionary) for Japanese readings, then our own kana-to-Hepburn table. Our own tables for Korean (Revised Romanization) and Cyrillic (English-style, Polish-style, ISO 9). ICU through a one-method interface for Chinese (`Han-Latin`) and other scripts: `android.icu` in the app, ICU4J in `core` tests. |

Step 0, before writing code: check that both libraries are maintained, that they build, and that they play a stream on the emulator without GMS. Note their licences. `innertube` is GPL-3, so the app will be GPL-3. Report blockers instead of working around them.

## Romanization

- Convert once, when metadata enters the app, and store `original` + `latin` next to each other. Every surface reads from the stored pair: lists, player, queue, search, lyrics, MediaSession metadata, notification. No surface romanizes on its own.
- Order of preference for names:
  1. An official Latin name (YouTube artist channel name, a MusicBrainz alias if it's cheap to get).
  2. A user override.
  3. Machine romanization.
- Machine schemes, user-selectable per script:
  - Japanese: Modified Hepburn by default; option for no macrons.
  - Korean: Revised Romanization.
  - Chinese: pinyin with tone marks by default; option for no tones.
  - Cyrillic: English-style by default; options for Polish-style and ISO 9.
  - Greek: a classical scheme without accent marks by default (β = b, βίος → "Bios"): ICU's `Greek-Latin` with its marks removed. Option for modern Greek, ELOT 743 (β = v): ICU's `Greek-Latin/UNGEGN` without its macron below (η = i̱), which only serves reversibility. ICU was checked first; it gives the right letters, so no table of our own.
  - Other scripts: ICU defaults.
- Han text, Japanese or Chinese? Decide in this order:
  1. Kana in the string means Japanese.
  2. Kana in a sibling field (artist, album) means Japanese.
  3. Otherwise use the user's default language (setting).
  - In lyrics this is decided once per song, from the whole lyrics: kana in any line makes every line Japanese, so a kanji-only line in a J-pop song by a Latin-named artist is still read as Japanese.
- Capitalization:
  - Titles and names get title case. Japanese particles (part of speech 助詞 in Kuromoji) stay lowercase: "Yoru ni Kakeru", "Kimi no Na wa". Korean, Chinese and other caseless scripts capitalize every word ("Qíng Tiān": Chinese stays one word per syllable). Cyrillic and Greek keep the original's capitals ("Gruppa krovi").
  - The first word is always capitalized, in any script (βίος → "Bios", "の歌" → "No uta").
  - Lyric lines get sentence case: only the line's first letter is capitalized ("Shizumu yō ni"). Title case on every lyric line reads like a list of headlines.
  - Parts that were Latin already never change ("LiSA", "feat.", "(Ballade Ver.)"). A user correction is shown exactly as typed.
- Japanese word spacing: short auxiliaries, suffixes and the te-form attach to their verb ("aruita", "aruite"); a subsidiary verb after the te-form is its own word ("tokete yuku", "tabete iru"); contractions such as てる and ちゃう stay glued ("tabeteru").
- Mixed script:
  - Before romanizing, the half-width and full-width forms block (U+FF00–U+FFEF) is NFKC-normalized: ＬＯＶＥ → LOVE, ｱｲ → アイ, （ → " (". Only that block: full NFKC also changes Latin text (² → 2, ﬁ → fi) and kaomoji (´ becomes a space and an accent).
  - Japanese punctuation becomes Latin: 、 → ","  。 → "."  「」『』 → `"`  ・ → space (only between two words)  ～ 〜 → "~".
  - Strings are read by code point, so 𠮷 is Han and an emoji doesn't make a title non-Latin.
- Stylized text stays as is:
  - A word that mixes Latin letters with Greek or Cyrillic letters is not romanized ("ΛRCTIC", "ЯOCK", "μ's"). Only Greek and Cyrillic: Japanese mixed with Latin is normal and is romanized ("Bokura no LIVE").
  - A single Greek or Cyrillic letter between symbols, with no space or line edge on either side, stays: (´・ω・`) keeps its ω. One-letter words between spaces are romanized (Russian я, и, в).
  - Known limit: inside Japanese text, Kuromoji splits "ΛRCTIC" into Λ and RCTIC, so the rule doesn't hold there.
- A title's own Latin form: when a song or album title carries one after " - ", " / " or in parentheses, and that part is fully Latin, the Latin form is just that part ("スピカ - Spica" → "Spica"). A part that looks like a version tag (ver, live, remix, feat., TV size, …) doesn't count, so "(Ballade Ver.)" is kept. Artist names don't get this rule ("… - Topic").
- Overrides: the user can correct any string. Scope is this string, this song or this artist. Stored locally and exportable as one file.
- Display modes: Romanized, Both (Latin with the original small underneath), Ruby (reading above the original), Original. Defaults: lists show Both; the player shows the Latin title with the original underneath.
- Search: the local index includes the romanized forms, so "kimi no" finds 君の声. Remote search sends the query as typed.

## Lyrics

- Provider chain, first good result wins, cached per song:
  1. Word-level (karaoke): enhanced LRC or a provider's word timing. Candidates: LRCLIB, KuGou / NetEase (word timing for C/J-pop), Musixmatch richsync. Check what each needs (token, ToS).
  2. Line-synced LRC.
  3. YouTube Music's own lyrics (unsynced).
- Providers sit behind one small interface, so you can add or reorder them. Settings list the providers in order, each with an on/off switch.
- Display per line: original, romanization, translation (if available). Each layer can be turned on or off. In karaoke mode, highlight the current word in **both** the original and the romanization: romanize per token so the timings map 1:1.
- Translations show only when their language matches the "Translation language" setting: by default the device language, then English. KuGou and NetEase send Chinese translations, which are no use here. No provider tags the language, so it is read from the script: Han is Chinese, kana Japanese, Hangul Korean, Cyrillic Russian, Latin English. A song whose translation doesn't match shows no translation layer; the "Show translation" switch stays in place, dimmed.
- Tapping a line seeks to it. Long-pressing a word lets the user correct its reading (an override scoped to the song).

## Controls: fixed positions

The core rule: **a control never moves.** If a feature is unavailable, its button stays in the same place, dimmed. No action bar scrolls sideways. The playback source (playlist, album, radio, search) never changes the layout.

Bottom block of the player, the same on the song, lyrics and queue views (only the area above it changes):

```
[ seek bar ]
[ shuffle ] [ prev ] [ PLAY/PAUSE ] [ next ] [ repeat ]
[ Never ] [ (empty, no label) ] [ Lyrics ] [ Queue ] [ More ] [ Like ]
```

- The action row has 6 equal slots, each at least 48 dp. Slot 2 is deliberately empty: no icon, no label, not tappable. Its purpose is distance from Never.
- Leave extra vertical space between the playback row and the action row, so a tap meant for shuffle can't hit Never.
- Never bans the song. It shows a "Banned · Undo" bar for about 5 s. To ban the artist, use More.
- Lyrics and Queue are toggles that swap the upper area. The selected one is highlighted. Lyrics is dimmed when no provider has lyrics.
- Like is always the rightmost slot.
- No mini player. The bottom nav has a Player tab to get back to the player.
- Notification / lock screen (MediaSession): prev, play/pause, next, Like as a custom action. Never is not there (the system places those buttons, so its gap can't be guaranteed). Title = Latin form; artist line = artist + original title.
- The ⋯ menu on a song has the same items in the same order everywhere.
- Bottom nav: Home, Search, Library, Player, Settings, in a fixed order. Player is dimmed when nothing is loaded.

## Queue and radio

- The queue has 3 sections: Now, You queued (pinned: radio never reorders or removes these), Radio.
- Each radio item shows why it's there ("same artist", "often played after X") when the API provides that.
- Radio leash setting: how far the radio may drift from the seed (close, medium, free). The simplest thing that works: when close, filter radio results to the seed's artist and related artists.
- Filters, each a setting and all on by default:
  - Prefer audio tracks over music videos.
  - Hide duplicates: live, sped up, nightcore, slowed.
  - Skip banned songs and artists.
- Option: stop when the queue ends (no autoplay).
- Playback: gapless; loudness normalization if the stream provides loudness data.

## Last.fm

- In-app scrobbling: browser auth flow to get a session key; `track.updateNowPlaying` and `track.scrobble`. Follow the standard rules: the track is longer than 30 s, and it was played for half its length or 4 min. Keep an offline queue and send it in batches of up to 50.
- Scrobble the **original** metadata, not the romanized form, so tracks match Last.fm's database.
- The user registers their own API key and secret in settings. Don't ship one.
- Not in v1: recommendations. The API no longer has a per-user recommendation endpoint. Doing it ourselves means `track.getSimilar` from the user's top tracks, then matching each result to a YT Music track, and the matching is the hard part. A possible later radio source.

## Search

- Filters in a fixed order: Songs, Albums, Artists, Playlists. Videos are excluded by default.
- Results show Latin with the original underneath.

## Implementation notes (2026-10-24)

Decisions taken while building v1, where the spec left room or turned out different:

- Licences: Metrolist innertube, InnerTubeX, QuackJS and NewPipeExtractor are GPL-3.0; Kuromoji and Media3 are Apache-2.0. The app is GPL-3.0 (LICENSE).
- Extra dependency the spec didn't name: `desugar_jdk_libs_nio`, which NewPipeExtractor needs below API 33.
- Streams: NewPipeExtractor's current YouTube client needs no PoToken, so no WebView token generator. Loudness comes from one extra `player` call (WEB_REMIX); it only turns loud tracks down, because the volume can't go above 1.
- Official Latin names: the YouTube channel name is used as is. MusicBrainz aliases are not fetched (not cheap: rate-limited, needs matching).
- Official Latin song titles: YouTube Music has no separate field for one. With `hl=en` (InnerTubeX's default, en-US) the watch-next and radio responses often give the title as "original - Latin" ("アイドル - Idol", "群青 - Gunjō", "打上花火 - Fireworks"), and search sometimes does ("咲き誇れ - The Bloom"); with `hl=ja` they give the plain original. The own-Latin-form rule picks these up. These localized titles belong to the video: アイドル's music video (ZRtdQ81jPUQ) comes as "アイドル - Idol", but its audio track (m9SMT5ipbxk), which "Prefer audio tracks" picks, comes as plain "アイドル" from search, watch-next and radio alike. So アイドル by YOASOBI shows "Aidoru"; a correction fixes it.
- Lyrics providers: NetEase (word timing as YRC, line LRC, Chinese translations) and KuGou (word timing as KRC, decrypted with the well-known key) use unofficial APIs with no token. LRCLIB is free and keyless. Musixmatch is left out: richsync needs a user token taken from their app, which breaks their ToS. Every provider must match the title as well as the duration, because duration alone picked an English cover. A song with no lyrics is cached as "none" too; Settings has "Search lyrics again".
- Radio leash: close keeps only the seed's artist and the related artists from its artist page. Medium lets in other artists only while they stay fewer than the on-leash songs. Free applies no artist filter. Reasons shown are "same artist" and "related artist"; YouTube's radio gives no "often played after".
- "Prefer audio tracks" drops a music video when the same title by the same artist exists as an audio track; a video with no audio twin stays.
- Shuffle reorders only the "You queued" songs after the current one, and turning it off restores their order. Radio stays after them, as the pinning rule requires.
- Playing from a list starts a new queue: the rest of the list goes into "You queued" and radio from the tapped song follows. Queueing a song that is already in the radio moves it out of the radio.
- The ⋯ menu, in this order everywhere: Play next, Add to queue, Start radio, Like, Go to artist, Go to album, Correct romanization…, Don't romanize title, Don't romanize artist, Remove from queue, Ban song, Ban artist. "Don't romanize title" stores a song-scoped correction with latin = original, "Don't romanize artist" an artist-scoped one; both are dimmed when the text is already Latin, and once set they read "Romanize title" / "Romanize artist" and undo it. The correction dialog has "Keep original", which does the same for the chosen field and scope. Settings lists every correction; tapping one removes it. Unavailable items are dimmed, not hidden.
- Library tabs: Playlists, Albums, Artists. Search's Playlists filter is community playlists.
- Known romanization limit: IPADIC reads some compounds by their parts (二人 → "ninin", not "futari"). Overrides fix it per song.

## Home and personal mixes (2026-10-24)

- Home starts with a pinned "Your mixes" row, always first. Signed out it holds a dimmed "Sign in to see your mixes"; signed in with none found, "No mixes yet". Signed in, the row keeps a card's height while it loads or is empty, so the shelves below don't jump. The last mixes found are kept in prefs and shown until the next lookup finishes.
- Order in the row is fixed: My Supermix, My Mix 1–7, Discover Mix, New Release Mix, Replay Mix, then other personal mixes (Archive Mix and any new ones) by title.
- Below it come YouTube's shelves. The first pages load until the mixes turn up (at most 10 pages), later pages as the user scrolls. New shelves only go at the bottom. The mixes are taken out of YouTube's own shelves, so they show once.
- How mixes are identified: by playlist ID, not the localized shelf title. Personal mixes have fixed IDs that are **the same for every account**; the account's cookies decide the contents. All start with `RDTMAK5uy_`. The fixed list (Supermix `RDTMAK5uy_kset8DisdE7LSD4TNjEVvrKRTmG7a56sY`, My Mix 1–7, Discover, New Release, Replay) comes from Music Assistant's `YT_PERSONAL_PLAYLISTS` and cliamp issue #619 (`roma.core.Mixes`). Other prefixes: `RDCLAK5uy_` editorial playlists, `RDAMVM<videoId>` song radio, `RDMM` YouTube "My Mix", `LM` liked songs.
  - The IDs resolve without cookies too, to an anonymous mix (checked: Supermix and My Mix 1 return 100 songs signed out; Discover Mix returns nothing). So the row can't simply list every known ID: the home feed says which mixes this account has, the table gives their order.
  - Metrolist has no mix detection: its Android Auto "Mixes" takes every playlist from up to 4 home pages (commit 38c8e122). No dedicated browse ID was found. `FEmusic_made_for_you` (mentioned in a ytmtui issue) answers HTTP 400 signed out, so it is unverified.
  - Not verified (needs an account): that the signed-in home feed lists the mixes under these IDs, and whether per-artist mixes use `RDTMAK5uy_` (if they do, they land in "others").
- Why Home never had the mixes: InnerTubeX sends account cookies on browse and next calls only when `YouTube.useLoginForBrowse` is on (default off; Metrolist turns it on). roma now turns it on, so home and radio are the account's.
- Signed out, home continuation pages come back empty unless every request carries the same visitor data; Home fetches one if none is stored.
- Playing a mix (tap in the row), or the Play button on any `RD…` playlist page: the songs go into "You queued" as a playlist, not radio. The radio filters (audio over video, duplicates, bans) apply; the leash doesn't (`filterMix`). The next page loads when 3 songs are left. Continuations of generated playlists loop back to songs already listed (an editorial list gives about 100 distinct songs over 6 pages, the Supermix about 137), so 3 pages with nothing new end the mix and radio follows, unless "stop when the queue ends" is on. The playlist page drops these repeats too.

## Process

- Local git repo. Don't create a GitHub repo or a signing key without asking the user.
- Build toolchain: mirror ~/dev/plainfin (AGP, Kotlin, Gradle, SDK at ~/Android/Sdk, JDK under ~/.local/share/JetBrains/Toolbox/apps/air/jbr). Read its build files first.
- Test on an emulator. Never install on the user's own devices without asking first.
- Leave one small runnable check per non-trivial piece: romanization (including the Japanese/Chinese decision), the lyrics parsers (LRC, enhanced LRC), queue filtering.
  - `./gradlew :core:test`: romanization, lyrics parsers (LRC, enhanced LRC, KRC, YRC), radio filters, personal mix identification and order, scrobble rule, Last.fm signature.
  - `./gradlew :core:networkCheck`: live check that innertube search, NewPipe stream resolution and each lyrics provider still work.
