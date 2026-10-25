# roma: design

A YouTube Music client for Android. It shows a romanized form of every non-Latin string, and its controls never move.
Working name; rename freely.

## Rules

- No exceptions fitted to examples. Romanization and lyrics logic use general rules (a script, a part of speech, a sound rule, a standard) and data from their sources; never a list of words or songs that happened to come out wrong. A wrong result for one song or word is a user correction or a dictionary limit, recorded as a known limit if it matters. (User, 2026-10-24.)

## Scope

- Android first. Test devices: Zenfone 10 and a Huawei MatePad on EMUI, which has **no Google Play Services**. Never depend on GMS.
- Desktop (Linux, then Windows) is a maybe, decided later by cost. The only concession now: modules `core` (data, romanization, lyrics) must not import Android APIs, so a desktop app can reuse them later.
- Language: Kotlin, because Media3 fits best. The user isn't keen on Kotlin, so keep the code plain. No Flutter, no KMP setup now.
- Not in v1: offline downloads (the stream cache is enough), a separate tablet layout (the MatePad uses the phone layout; only landscape puts the controls beside the upper area), comments, video playback, the mini player.

## Stack

| Concern | Choice |
|---|---|
| YT Music API (home, search, library, radio, likes, playlists) | `innertube` Kotlin module from Metrolist, vendored into `core` (package `com.metrolist.innertube`, unchanged except the search correction fields, see Search; a no-op `timber.log.Timber` stands in for its logger). Since October 2026 it is a facade over Metrolist's InnerTubeX library (Kotlin Multiplatform, JVM target, from JitPack), which pulls in QuackJS. Not published on its own, so vendoring is the only option. |
| Stream URLs (signature / n-param / PoToken) | NewPipeExtractor first (fast, VISIONOS client). When YouTube refuses it, InnerTubeX's extractor with a WebView PoToken generator (see Stream fallback). |
| Playback, media session, notification | Media3 (ExoPlayer + MediaSessionService) |
| Login | WebView sign-in to music.youtube.com, cookies kept. No GMS account picker. |
| Romanization | Sudachi with SudachiDict "core" for Japanese words and readings (see Japanese dictionary below), then our own kana-to-Hepburn table. Our own tables for Korean (Revised Romanization) and Cyrillic (English-style, Polish-style, ISO 9). ICU through a one-method interface for Chinese (`Han-Latin`) and other scripts: `android.icu` in the app, ICU4J in `core` tests. |

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
  - Cyrillic: the language is read from its distinctive letters (below). Russian: English-style by default; options for Polish-style and ISO 9. Other languages use their national Latin form; the ISO 9 option applies to every language.
  - Greek: a classical scheme without accent marks by default (β = b, βίος → "Bios"): ICU's `Greek-Latin` with its marks removed. Option for modern Greek, ELOT 743 (β = v): ICU's `Greek-Latin/UNGEGN` without its macron below (η = i̱), which only serves reversibility. ICU was checked first; it gives the right letters, so no table of our own.
  - Other scripts: ICU defaults.
- Han text, Japanese or Chinese? Decide in this order:
  1. Kana in the string means Japanese.
  2. Kana in a sibling field (artist, album) means Japanese.
  3. Otherwise use the user's default language (setting).
  - In lyrics this is decided once per song, from the whole lyrics: kana in any line makes every line Japanese, so a kanji-only line in a J-pop song by a Latin-named artist is still read as Japanese.
- Capitalization:
  - Titles and names get title case. Japanese particles (part of speech 助詞 in Sudachi, and だ as に/な/で after a na-adjective) stay lowercase: "Yoru ni Kakeru", "Kimi no Na wa". Korean, Chinese and other caseless scripts capitalize every word ("Qíng Tiān": Chinese stays one word per syllable). Cyrillic and Greek keep the original's capitals ("Gruppa krovi").
  - The first word is always capitalized, in any script (βίος → "Bios", "の歌" → "No uta").
  - Lyric lines get sentence case: only the line's first letter is capitalized ("Shizumu yō ni"). Title case on every lyric line reads like a list of headlines.
  - Parts that were Latin already never change ("LiSA", "feat.", "(Ballade Ver.)"). A user correction is shown exactly as typed.
- Japanese word spacing: auxiliaries, suffixes, a prefix, the te-form and ば attach to their word ("aruita", "aruite", "tabemasu", "okāsan", "sannin", "ikeba"); です, でしょう, だ and らしい stay apart ("iku deshō"). A subsidiary verb straight after a verb stem glues ("hashiridasu"); after the te-form or an adjective it is its own word ("tokete yuku", "tabete iru", "tsuyoku nareru"). Contractions such as てる and ちゃう are auxiliaries, so they glue ("tabeteru"). A sentence-final particle at the start of a phrase is a misread first syllable and joins the next word (よ|ふかし → "Yofukashi").
- Japanese readings: Sudachi's reading is the kana spelling, so Hepburn's spoken forms come from spelling plus part of speech: particles は へ を → wa e o; オウ, オオ, ウウ inside a word → ō, ū (tōkyō, ōkii, kūki), while エイ and イイ stay (eiga, ōkii); a verb's final ウ is its ending, not a long vowel (omou), except in the volitional (ikō); katakana ー → macron (kōhī). 何 is nan before t, d and n sounds and as a number (nan de, nando), else nani. Readings come from the dictionary alone: no word lists. Where the dictionary's reading isn't the one a song uses (明日 asu/ashita, 私 watakushi/watashi), that is a correction. The kana spelling is kept where Hepburn would need morpheme boundaries the dictionary doesn't give (お母さん okaasan: aa → ā would also turn 場合 baai into "bāi"). A reading in brackets after a word (君(きみ)) is dropped.
- Mixed script:
  - Before romanizing, the half-width and full-width forms block (U+FF00–U+FFEF) is NFKC-normalized: ＬＯＶＥ → LOVE, ｱｲ → アイ, （ → " (". Only that block: full NFKC also changes Latin text (² → 2, ﬁ → fi) and kaomoji (´ becomes a space and an accent).
  - Japanese punctuation becomes Latin: 、 → ","  。 → "."  「」『』 → `"`  ・ → space (only between two words)  ～ 〜 → "~".
  - Strings are read by code point, so 𠮷 is Han and an emoji doesn't make a title non-Latin.
- Stylized text stays as is:
  - A word that mixes Latin letters with Greek or Cyrillic letters is not romanized ("ΛRCTIC", "ЯOCK", "μ's"). Only Greek and Cyrillic: Japanese mixed with Latin is normal and is romanized ("Bokura no LIVE").
  - A single Greek or Cyrillic letter between symbols, with no space or line edge on either side, stays: (´・ω・`) keeps its ω. One-letter words between spaces are romanized (Russian я, и, в).
  - Known limit: inside Japanese text, Sudachi splits "ΛRCTIC" into Λ and RCTIC, so the rule doesn't hold there.
- A title's own Latin form: when a song or album title carries one after " - ", " / " or in parentheses, and that part is fully Latin, the Latin form is just that part ("スピカ - Spica" → "Spica"). A part that looks like a version tag (ver, live, remix, feat., TV size, …) doesn't count, so "(Ballade Ver.)" is kept. Artist names don't get this rule ("… - Topic").
- Overrides: the user can correct any string. Scope is this string, this song or this artist. Stored locally and exportable as one file.
- Word readings (scope "word"): the reading of one word, in one song or everywhere (私 → watashi where the dictionary says watakushi). It applies inside any string, titles and lyric lines alike, wherever the word is a whole dictionary token or a run of whole tokens; never inside a longer token (私立 stays shiritsu). Japanese matches Sudachi tokens; other scripts match a word between spaces or a run of one script. A song's own reading wins over the everywhere one. Title and sentence case apply to it like any word. Set from a long-press on a lyric word or from the correction dialog's word buttons; listed in Settings and part of the export.
- Scripts you read (setting, none by default): text only in those scripts shows as written, in lists, the player, the notification and the lyrics (no romanization line).
- Display modes: Romanized, Both (Latin with the original small underneath), Ruby (reading above the original), Original. Defaults: lists show Both; the player shows the Latin title with the original underneath.
- Search: the local index includes the romanized forms, so "kimi no" finds 君の声. Remote search sends the query as typed; YouTube's own spelling correction applies (see Search).

## Lyrics

- Every enabled provider is asked at once, each for at most 5 s (`LyricsProviders.find`). YouTube Music's own text is the reference, and the first rule that holds picks the lyrics (`chooseLyrics`):
  1. Word-level (karaoke) from any provider whose text matches YTM's. When YTM has no lyrics, any word-level result.
  2. YTM's line-timed lyrics.
  3. A third-party line-synced result whose text matches YTM's.
  4. YTM's plain lyrics.
  5. Only when YTM has nothing: third-party synced, then third-party plain, in the user's provider order.
  - Among several matching candidates in rules 1 and 3, the most similar wins, then the user's order.
- "Matches": `similarity` > `SAME_LYRICS` (0.6). Both texts are normalized (NFKC, lowercase, letters and digits only; Traditional Chinese folded to Simplified through ICU) and compared as the Dice coefficient of their character bigrams over the whole lyrics. Bigrams, not edit distance, because providers differ in whether repeated choruses are written out. Measured 2026-10-24: the same lyrics from two providers 0.77–1.00 (credit lines and Traditional/Simplified differences at the low end); LRCLIB's other-language Lemon 0.00; a different song in the same language around 0.3.
- Providers sit behind one small interface, so you can add or reorder them. Settings list the providers in order, each with an on/off switch. The order only breaks ties in rule 5.
- Translations are a separate layer (`Translation`: source, language, lines of original text + translation), not part of the chosen lyrics. Every provider result with a translation gives one layer; a later opt-in AI translation would add another. At display time the layer in the wanted language is picked (the lyrics' own source first, then the first found) and attached line by line (`attachTranslation`): same normalized original text first, then, when both are synced, the line starting within 1 s. Lines with neither get none.
- Cache: cacheDir/lyrics/<videoId>.json holds a `LyricsResult` (the chosen lyrics without translations, every translation layer, the rule). A result is cached; "no lyrics" is cached only when every provider answered. If any failed or timed out, an empty result is not cached and the next play asks again.
- The end of the lyrics shows "Lyrics: <source>" and "Translation: <source>" in small text. YTM's source keeps its own source message ("YouTube Music · Source: LyricFind").
- Display per line: original, romanization, translation (if available). Each layer can be turned on or off. In karaoke mode, highlight the current word in **both** the original and the romanization: romanize per token so the timings map 1:1.
- Translations show only when their language matches the "Translation language" setting: by default the device language, then English. KuGou and NetEase send Chinese translations, which are no use here. No provider tags the language, so it is read from the script: Han is Chinese, kana Japanese, Hangul Korean, Cyrillic Russian, Latin English. A song whose translation doesn't match shows no translation layer. The "Show translation" switch is a global setting and is never dimmed by the current song.
- Tapping a line seeks to it. Long-pressing a word (in either layer) opens its card: the dictionary's kana, romaji, dictionary form and part of speech, and the user's reading for this song or everywhere (the scope last used is offered first), or Keep original. The word is the dictionary word under the finger, not the karaoke syllable: し in 忙|し|い opens 忙しい.
- Each line is also split into words as its romanization spaces them (`Romanizer.pieces`: a word with its glued endings, particles apart). The "Above each word" layout sets each word's romaji right above it (ruby); in karaoke the word holding the current syllable is lit.
- Long-pressing the player's title or artist opens the correction dialog.

## Controls: fixed positions

The core rule: **a control never moves.** If a feature is unavailable, its button stays in the same place, dimmed. No action bar scrolls sideways. The playback source (playlist, album, radio, search) never changes the layout.

Bottom block of the player, the same on the song, lyrics and queue views (only the area above it changes). Changed by the user on 2026-10-24 to a shorter block with play nearest the thumb; positions are fixed again from there:

```
[ 0:42 ][ seek bar ][ 3:16 ]                                   about 28 dp
[ More ] [ (empty) ] [ (empty) ] [ Lyrics ] [ Queue ] [ Like ]  about 44 dp, small icon, 10 sp label
[ shuffle ] [ prev ] [ PLAY/PAUSE ] [ next ] [ repeat ]        about 52 dp, play icon 44 dp
[ Home ] [ Search ] [ Player ] [ Library ] [ Settings ]        bottom navigation, about 48 dp
```

- The elapsed and total time sit on the seek bar's line. Every slot is at least 48 dp wide. No extra gap between the rows.
- The action row has 6 equal slots. Slots 2 and 3 are deliberately empty: no icon, no label, not tappable. They keep More (which holds Ban) away from the other buttons.
- Left-handed mode (setting) mirrors the action row: Like on the left, More on the right. Nothing else moves.
- On a landscape screen (a phone on its side, the MatePad) the upper area is on the left and the same block, in the same order, on the right. On a wide portrait screen the block keeps at most 560 dp of width, centred.
- The Never button was removed (user, 2026-10-24). Ban song / Ban artist live in More; banning the playing song skips it and shows a "Banned · Undo" bar for about 5 s.
- Lyrics and Queue are toggles that swap the upper area. The selected one is highlighted. Lyrics is dimmed when no provider has lyrics.
- Like is always the rightmost slot.
- No mini player. The bottom nav has a Player tab to get back to the player.
- Notification / lock screen (MediaSession): prev, play/pause, next, Like as a custom action. No ban button there. Title = Latin form; artist line = artist + original title.
- The ⋯ menu on a song has the same items in the same order everywhere.
- Bottom nav: Home, Search, Player, Library, Settings, in a fixed order, Player in the middle. Player is dimmed when nothing is loaded. Back (or a swipe down on the song view) leaves the player for the tab it was opened from.
- Gestures add shortcuts and never move a control (2026-10-24): on the song view, swipe sideways for the next or previous song (the target peeks in during the drag; it goes to the song before even past 3 s, as the peek shows), double-tap to like (only likes, never unlikes), swipe down to leave the player. Long-press play/pause for the sleep timer (15 min to 1.5 h, or when this song ends; the last 10 s fade out); while it runs a chip at the top of the upper area shows the time left.
- Seek bar: while dragging, the lyric line at the drag position shows over the bottom of the upper area; near a timed line's start (12 dp of travel) the drag snaps to it with a haptic tick.
- Haptics: like (button and double-tap), ban, a swipe passing the skip point, a seek snap.
- Lyrics view background: the song's art, blurred (Android 12+; a 16 px copy stretched on older versions) at 20 % opacity, fading into the theme's background at the top and bottom.
- Long lists (library, album and playlist pages, the queue; over three screens) show a fast-scroll thumb on the right edge while they scroll and for 1.5 s after. Only then does it take touches, so the rows' ⋯ buttons under it work otherwise.
- Display options, off by default: true black in dark mode (OLED), and an accent colour from the playing song's art (a saturation-weighted average of a 24 px copy; grey art keeps the usual colours).
- Large system fonts: the fixed rows (player controls, nav bar) follow the font size up to 1.3×, so their labels and times are never cut off; everything else scales fully. Search filters wrap onto a second line in the same order.
- Errors say what to do: no connection → "Check Wi-Fi or mobile data, then Retry"; a timeout → "Retry in a moment". A song that can't play offers Retry and "Play the next song".

## Queue and radio

- The queue has 3 sections: Now, You queued (pinned: radio never reorders or removes these), Radio.
- Each radio item shows why it's there ("same artist", "often played after X") when the API provides that.
- Tapping a song in search results or a Home shelf plays that song alone; YouTube's autoplay (radio) follows. Only album and playlist pages queue the rest of their list.
- Radio leash setting: how far the radio may drift from the seed (close, medium, free). Default is free: YouTube's autoplay as is. The simplest thing that works: when close, filter radio results to the seed's artist and related artists.
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
- Spelling (checked 2026-10-24): YouTube Music corrects typos with and without the Songs filter. "yoasbi yoru ni kakru" and "kenshi yonezy lemon" come back as results for "yoasobi yoru ni kakeru" and "kenshi yonezu lemon" (`showingResultsForRenderer`); "kino grupa krovi" comes back for the query as typed, which already finds Группа крови, plus a suggestion "kino gruppa krovi" (`didYouMeanRenderer`). Both sit in an `itemSectionRenderer` before the results, which innertube used to drop; the vendored `YouTube.search` now reads them (`SearchResult.correctedQuery`, `autoCorrected`, `originalParams`). This is the only change to the vendored innertube.
- One line directly above YouTube's results (under the filters, or under the "Seen before" rows when there are any) shows the correction: "Showing results for “X” · Search as typed" (the corrected query gives way to "…" so the action always fits; tap: search the query as typed, with YouTube's `originalQueryEndpoint` params, which turn the correction off for that filter) or "Did you mean X?" (tap: search X). Changing the filter or the query clears the "as typed" choice.
- Suggestions: while the text differs from the last search, YouTube's suggestions (`searchSuggestions`, 250 ms after the last keystroke) take the results' place; tapping one searches it.
- "Seen before" (local) matching ignores case, accents (ō = o), spaces, apostrophes and hyphens, and tolerates typos: none in a query under 5 letters, one from 5, two from 9 (edit distance to any part of the title, the artist, or artist + title). Closer matches come first, at most 20. They show at once, above a spinner while YouTube's results load, and keep their place when those arrive.

## Implementation notes (2026-10-24)

Decisions taken while building v1, where the spec left room or turned out different:

- Licences: Metrolist innertube, InnerTubeX, QuackJS and NewPipeExtractor are GPL-3.0; Sudachi, SudachiDict and Media3 are Apache-2.0. The app is GPL-3.0 (LICENSE).
- Japanese dictionary (2026-10-24). Sudachi 0.8.2 (`com.worksap.nlp:sudachi`) with SudachiDict core, version 20261015.
  - Why: Kuromoji's IPADIC mis-split all-hiragana text (よふかしのうた → "Yo fuka shinōta"), 二人 ("ninin") and katakana loanwords (セブンティーン). Kuromoji UniDic splits right but adds 179 MB of heap. SudachiDict splits right and is memory-mapped: about 80 ms to load on the JVM, almost no heap. Core, not small: small split compounds such as お母さん, 一歩 and 既視感 and needed a hand-made word list; core gets them from the dictionary (the user ruled out word lists, see Rules).
  - Licence: SudachiDict is Apache-2.0. Its lexicon and connection matrix contain parts of UniDic (BSD 3-clause, © The UniDic Consortium). The core lexicon also contains parts of mecab-unidic-NEologd (Apache-2.0, © Toshinori Sato; built partly from Hatena keyword data). Both are compatible with GPL-3. The notice ships in the APK as `assets/sudachi-LEGAL.txt`.
  - Size: the dictionary is 202 MB, stored uncompressed in the APK (`noCompress += "dic"`). Debug APK about 225 MB.
  - Loading: `:core:sudachiDict` downloads the GitHub release zip once into `~/.gradle/caches/roma/sudachi-core-<version>/`, checks its sha256 and unpacks `system_core.dic` and `LEGAL`. The app's assets include that directory. On Android, `Store.romanizer` maps the asset in place (`AssetManager.openFd` → `FileChannel.map` at the asset's offset) and hands the buffer to `BinaryDictionary.loadSystem(ByteBuffer)`: no copy to filesDir. A background thread loads it at app start. The JVM tests map the file named by the `roma.sudachi` system property, which the Gradle test task sets.
  - Update: set `sudachiVersion` and `sudachiSha256` in core/build.gradle.kts to the new release (https://github.com/WorksApplications/SudachiDict/releases, file `sudachi-dictionary-<version>-core.zip`). Use the GitHub zip: the CloudFront mirror of the same version is a different build. Run `./gradlew :core:test` and check that the dictionary still loads with the Sudachi version in use (a dictionary format change needs a newer Sudachi).
- Extra dependency the spec didn't name: `desugar_jdk_libs_nio`, which NewPipeExtractor needs below API 33.
- Stream lookup fast path (2026-10-24). NewPipeExtractor 0.26.5's `StreamInfo.getInfo` makes 7 requests in a row: visitor_id + reel_item_watch (ANDROID client, whose streams need a PoToken we don't send, so they go unused), visitor_id + player (VISIONOS: the streams we play, and the playability check), visitor_id + player (WEB, metadata only), and `next` (WEB, related videos, about 0.7–1 s). roma calls the stream extractor directly for its audio streams, and its downloader answers the ANDROID and WEB requests itself with a minimal JSON (`stub` in Streams.kt). Only the 2 VISIONOS requests go out. If the fast path fails in any way, the full `StreamInfo.getInfo` runs.
  - Measured on the emulator, warm, a song tapped in search: `Streams.audio` 1.3–1.8 s before, 0.17–0.29 s after; tap to playing 1.7–2.2 s before, 0.5–0.7 s after.
  - Tied to NewPipe 0.26.5's request order. A NewPipe update may change the clients; check that the fast path still returns streams (`./gradlew :core:networkCheck` uses it) before trusting the timings.
  - Loudness comes from the player response of the lookup; it only turns loud tracks down, because the volume can't go above 1.
- Stream fallback (2026-10-24). NewPipe 0.26.5 asks only the VISIONOS client. YouTube refuses it for some songs on some networks or accounts ("This video is a YouTube Music Premium video" for Youngblood, CXNv4_wQjKQ, on the user's phone in Poland, while it plays from our IP) and for every age-restricted song. `Streams.audio` tries, in order: the fast path, NewPipe's full lookup, then InnerTubeX's extractor (`InnerTubeXStreams`, set up as Metrolist's InnerTubeXPlayer). The path that worked for a song goes first next time; a song only InnerTubeX could play keeps that mark for 7 days across starts (filesDir/streams.txt, which also keeps each song's loudness: a song played to the end replays from the cache without a lookup, so its loudness must outlive the process); a song every path refused gets the same answer for 30 s without asking again (not when offline, so Retry works). The error shown is the last specific reason, in plain words.
  - InnerTubeX tries VISIONOS 0.1 with visitor data from a watch page, then the music web client WEB_REMIX. WEB_REMIX streams need a PoToken, or they stop after about 1 MB; the app mints them with YouTube's BotGuard in a hidden WebView (`PoTokens`, ported from Metrolist, `assets/po_token.html`). A cold start of the WebView takes a few seconds, only on the fallback path.
  - Signatures: InnerTubeX 0.11.2's QuackJS with the cipher hints from zemer-cipher's `player_configs.json` (fetched from GitHub). 0.11.0 and NewPipe 0.26.5 could not solve the October 2026 player's signature, so the web clients need 0.11.2 or later.
  - Age-restricted songs: signed out every client refuses them ("Sign in to confirm your age"). Signed in, InnerTubeX retries with the account's cookies (`YouTube.cookie`, needs SAPISID) and an authenticated watch page; for an account without Premium only WEB_REMIX with a PoToken plays them (InnerTubeX's client catalog). Not verified yet with a real account.
  - Testing: `-Proma.refuseVisionOs=true` (debug or release build, never for a shipped release) acts as if YouTube refused VISIONOS; logcat shows `roma streams: <id> via InnerTubeX <client profile>`. `:core:networkCheck` checks the InnerTubeX fallback without a PoToken (the JVM has no WebView) and, with `ROMA_COOKIE` set to a signed-in cookie, the age-restricted song.
  - Licence: the PoToken code and page come from Metrolist (GPL-3.0), originally NewPipe (GPL-3.0).
- Official Latin names: the YouTube channel name is used as is. MusicBrainz aliases are not fetched (not cheap: rate-limited, needs matching).
- Official Latin song titles: YouTube Music has no separate field for one. With `hl=en` (InnerTubeX's default, en-US) the watch-next and radio responses often give the title as "original - Latin" ("アイドル - Idol", "群青 - Gunjō", "打上花火 - Fireworks"), and search sometimes does ("咲き誇れ - The Bloom"); with `hl=ja` they give the plain original. The own-Latin-form rule picks these up. These localized titles belong to the video: アイドル's music video (ZRtdQ81jPUQ) comes as "アイドル - Idol", but its audio track (m9SMT5ipbxk), which "Prefer audio tracks" picks, comes as plain "アイドル" from search, watch-next and radio alike. So アイドル by YOASOBI shows "Aidoru"; a correction fixes it.
- Lyrics providers: NetEase (word timing as YRC, line LRC, Chinese translations) and KuGou (word timing as KRC, decrypted with the well-known key) use unofficial APIs with no token. LRCLIB is free and keyless. Musixmatch is left out: richsync needs a user token taken from their app, which breaks their ToS. Every provider must match the title as well as the duration, because duration alone picked an English cover. A song with no lyrics is cached as "none" only when every provider answered; Settings has "Search lyrics again".
- YouTube Music lyrics (2026-10-24): the lyrics browse ID (`MPLYt…`, from `next`) browsed with the ANDROID_MUSIC client (version 7.21.50, sent with plain OkHttp like the other providers, so the vendored innertube stays unchanged) returns `timedLyricsModel.lyricsData.timedLyricsData`, one entry per line with `cueRange.startTimeMilliseconds`, as ytmusicapi's `get_lyrics(timestamps=True)` reads it. Works signed out: 夜に駆ける, Lemon, Blinding Lights, Numb and 晴天 (audio tracks) all came back timed. A music video's entry can come without `cueRange`; its lines are then plain. When the mobile response has no timed model, the web client's description shelf gives plain text.
- Translation languages available (checked 2026-10-24): NetEase `tlyric` and KuGou's KRC language block type 1 are Chinese only; NetEase `romalrc` and KuGou type 0 are romanizations, not translations; LRCLIB has none. So no provider gives English or Polish yet; the layer structure is ready for another source.
- Radio leash: close keeps only the seed's artist and the related artists from its artist page. Medium lets in other artists only while they stay fewer than the on-leash songs. Free applies no artist filter. Reasons shown are "same artist" and "related artist"; YouTube's radio gives no "often played after".
- "Prefer audio tracks" drops a music video when the same title by the same artist exists as an audio track; a video with no audio twin stays.
- Shuffle reorders only the "You queued" songs after the current one, and turning it off restores their order. Radio stays after them, as the pinning rule requires.
- Playing from a list starts a new queue: the rest of the list goes into "You queued" and radio from the tapped song follows. Queueing a song that is already in the radio moves it out of the radio.
- The ⋯ menu, in this order everywhere: Play next, Add to queue, Start radio, Like, Go to artist, Go to album, Correct romanization…, Don't romanize title, Don't romanize artist, Remove from queue, Ban song, Ban artist. "Don't romanize title" stores a song-scoped correction with latin = original, "Don't romanize artist" an artist-scoped one; both are dimmed when the text is already Latin, and once set they read "Romanize title" / "Romanize artist" and undo it. The correction dialog has "Keep original", which does the same for the chosen field and scope. Settings lists every correction; tapping one removes it. Unavailable items are dimmed, not hidden.
- Karaoke romaji: a word timed over several parts (忙|し|い) gets its romaji split the same way: kana parts at either end take their own reading, the kanji between take the rest ("isoga" | "shi" | "i"). When the pieces wouldn't add up exactly (a long vowel across parts, 行|こ|う → "ikō"), the whole word stays on its first part, and the romaji line keeps it lit while the word's later parts play.
- Library tabs: Playlists, Albums, Artists. Search's Playlists filter is community playlists.
- Cyrillic languages (2026-10-24). Decided once per string, or once per song for lyrics (from all lines), by the first rule that matches (`Romanizer.cyrillicLanguage`):
  1. Kazakh: any of ә ғ қ ұ һ, or і together with ң, ө or ү.
  2. Kyrgyz: ң (otherwise). No official Latin alphabet: BGN/PCGN 1979 (ң ng, ө ö, ү ü, ж j).
  3. Mongolian: ө ү without ң.
  3. Belarusian: ў, or і with ы, э or ё and none of и є ї ґ.
  4. Ukrainian: є ї ґ or і.
  5. Macedonian: ѓ ќ ѕ. Serbian: ђ ј љ њ ћ џ.
  6. Bulgarian: ъ before a consonant (път, България) and none of ы э ё. Russian writes ъ only before е ё ю я.
  7. Otherwise Russian, the safe default.
- Their Latin forms:
  - Kazakh: the 2021 Latin alphabet (ä ğ q ñ ö ū ü h ı ş j; и й → i, capital İ; і → ı, capital I). Letters it has no own letter for: ё io, ц ts, ч tş, щ ştş, ю iu, я ia, э e, ъ ь dropped. Russian's "ye" for е is not used.
  - Ukrainian: the 2010 national transliteration. є ї й ю я are ye yi y yu ya at the start of a word, ie i i iu ia elsewhere; зг is zgh; the apostrophe is dropped.
  - Belarusian: the 2007 national system (г h, х ch, ц c, ў ŭ). е ё ю я are je jo ju ja at the start of a word and after a vowel, apostrophe, ь or ў; ie io iu ia after a consonant. зь ль нь сь ць дзь give ź ĺ ń ś ć dź.
  - Serbian: Gaj's Latin alphabet. Macedonian: the same, with ѓ gj, ќ kj, ѕ dz.
  - Bulgarian: the 2009 Streamlined System (ъ a, щ sht, ь y; a final ия is ia).
  - Mongolian: MNS 5217:2012 through ICU's `mn-mn_Latn/MNS`.
  - An apostrophe between Cyrillic letters belongs to the word, so м'ясо is one word.
  - ISO 9 uses our table for Russian, Ukrainian and Belarusian letters; other letters go through the safety net.
  - Safety net: whatever is left goes through ICU's `Cyrillic-Latin`. Letters ICU doesn't know (Tatar җ, Tajik ҷ) become the letter they are built on, by Unicode name (җ → ж), and go through ICU again; if none fits, the letter is dropped. Only stylized words (ЯOCK) and kaomoji letters keep their Cyrillic, by the rules above.
- Known romanization limit: IPADIC reads some compounds by their parts (二人 → "ninin", not "futari"). Overrides fix it per song.

## Continuity (2026-10-25)

- Restart: `Playback` writes the queue (songs, sections, index, repeat, shuffle order, radio seed and continuation, mix ID and continuation) to filesDir/queue.json after each burst of queue changes, and "<videoId> <ms>" to filesDir/position on pause, seek and every 10 s while playing; both via tmp file + rename. At start the queue comes back paused, at its position, with lyrics from their cache; no Last.fm "now playing", and radio top-up and prefetch wait for Play. A fully cached song is prepared at once; any other on Play, so opening the app costs no data. The tab and the player view (song, lyrics, queue) come back too (prefs `ui.tab`, `ui.playerView`). An expired radio continuation restarts the radio's pages.
- Media buttons: `MediaButtonReceiver` plus `onPlaybackResumption` let a headset, car or the system's media controls resume after the process died.
- Network drops (`WaitForNetwork`): with no connection, loads retry every 2 s for up to 10 min and the error reaches the player only when that gives up, so the buffer plays out, the player shows "Waiting for network…" (no validated default network, buffering, play wanted) and goes on by itself. Other errors keep 3 retries. The Play slot follows `Util.shouldShowPlayButton`: Pause while buffering.
- Opt-in "Resume when headphones reconnect": a pause caused by audio becoming noisy is undone when a headset, A2DP/BLE, USB headset or hearing aid output appears within an hour (`AudioDeviceCallback`, no polling). Not testable on the emulator.
- Opt-in "Download songs ahead": the load control buffers the whole current song (up to 10 min, else 50 s), and the next 3 songs are cached in full with `CacheWriter`. The current song can't use CacheWriter: the player's open read locks the cache span. Queue rows say "saved" for songs fully in the cache.

## Home and personal mixes (2026-10-24)

- Home starts with a pinned "Your mixes" row, always first. Signed out it holds a dimmed "Sign in to see your mixes"; signed in with none found, "No mixes yet". Signed in, the row keeps a card's height while it loads or is empty, so the shelves below don't jump. The last mixes found are kept in prefs and shown until the next lookup finishes.
- Order in the row is fixed: My Supermix, My Mix 1–7, Discover Mix, New Release Mix, Replay Mix, then other personal mixes (Archive Mix and any new ones) by title.
- Below it come YouTube's shelves. Signed in, the first 10 pages load at once and the row shows the mixes from all of them: the Supermix often comes on an early page and the numbered mixes on later ones. Later pages load as the user scrolls, and mixes on them join the row too. New shelves only go at the bottom. The mixes are taken out of YouTube's own shelves, so they show once.
- How mixes are identified: by playlist ID, not the localized shelf title. Personal mixes have fixed IDs that are **the same for every account**; the account's cookies decide the contents. All start with `RDTMAK5uy_`. The fixed list (Supermix `RDTMAK5uy_kset8DisdE7LSD4TNjEVvrKRTmG7a56sY`, My Mix 1–7, Discover, New Release, Replay) comes from Music Assistant's `YT_PERSONAL_PLAYLISTS` and cliamp issue #619 (`roma.core.Mixes`). Other prefixes: `RDCLAK5uy_` editorial playlists, `RDAMVM<videoId>` song radio, `RDMM` YouTube "My Mix", `LM` liked songs.
  - The IDs resolve without cookies too, to an anonymous mix (checked: Supermix and My Mix 1 return 100 songs signed out; Discover Mix returns nothing). So the row can't simply list every known ID: the home feed says which mixes this account has, the table gives their order.
  - Metrolist has no mix detection: its Android Auto "Mixes" takes every playlist from up to 4 home pages (commit 38c8e122). No dedicated browse ID was found. `FEmusic_made_for_you` (mentioned in a ytmtui issue) answers HTTP 400 signed out, so it is unverified.
  - Not verified (needs an account): that the signed-in home feed lists the mixes under these IDs, and whether per-artist mixes use `RDTMAK5uy_` (if they do, they land in "others").
- Why Home never had the mixes: InnerTubeX sends account cookies on browse and next calls only when `YouTube.useLoginForBrowse` is on (default off; Metrolist turns it on). roma now turns it on, so home and radio are the account's.
- Signed out, home continuation pages come back empty unless every request carries the same visitor data; Home fetches one if none is stored.
- Playing a mix (tap in the row), or the Play button on any `RD…` playlist page: the songs go into "You queued" as a playlist, not radio. The radio filters (audio over video, duplicates, bans) apply; the leash doesn't (`filterMix`). The next page loads when 3 songs are left. Continuations of generated playlists loop back to songs already listed (an editorial list gives about 100 distinct songs over 6 pages, the Supermix about 137), so 3 pages with nothing new end the mix and radio follows, unless "stop when the queue ends" is on. The playlist page drops these repeats too.

## Getting songs ready before the tap (2026-10-25)

- A guess prepares a song the way a tap would: its stream URL and first 32 KB (`Playback.prepare`). Guessed: an opened album, playlist or artist page's first song (Play and the first row), Search's top row ("Seen before" first, then YouTube's top song), and the next three songs when the queue view opens. One at a time, at most 60 an hour; none while the current song is loading or a picked song is being prepared.
- Cost: about 45 KB a guess (a lookup of about 12 KB and 32 KB of audio; nothing for a song already cached), under 3 MB an hour at the cap, against about 60 MB an hour of audio. Battery: a few short requests, no timers or polling.
- Guesses run only on an unmetered network outside Battery Saver. The setting "Get likely songs ready on mobile data and in Battery Saver" (off by default) lifts that.
- A song needed the InnerTubeX fallback in the last 7 days: the app start also warms that path (player script, cipher config, PoToken WebView) with that song, within the same network and battery limits. One lookup per start.
- Play on a generated playlist's page (RD…) plays the songs the page already holds instead of loading the playlist again; radio follows when they run out.
- Coming back to the foreground loads a Home older than 30 minutes again behind the shown one.

## Process

- Local git repo. Don't create a GitHub repo or a signing key without asking the user.
- Build toolchain: mirror ~/dev/plainfin (AGP, Kotlin, Gradle, SDK at ~/Android/Sdk, JDK under ~/.local/share/JetBrains/Toolbox/apps/air/jbr). Read its build files first.
- Test on an emulator. Never install on the user's own devices without asking first.
- Leave one small runnable check per non-trivial piece: romanization (including the Japanese/Chinese decision), the lyrics parsers (LRC, enhanced LRC), queue filtering.
  - `./gradlew :core:test`: romanization, lyrics parsers (LRC, enhanced LRC, KRC, YRC), radio filters, personal mix identification and order, scrobble rule, Last.fm signature.
  - `./gradlew :core:networkCheck`: live check that innertube search, NewPipe stream resolution, the InnerTubeX stream fallback and each lyrics provider still work, and which rule picks the lyrics for 4 songs (Japanese, English, Chinese).
  - `:core:test` also covers the lyrics rules, the similarity threshold, translation alignment and the parallel search's caching decision.
