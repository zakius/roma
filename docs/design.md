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
| YT Music API (home, search, library, radio, likes, playlists) | `innertube` Kotlin module from the InnerTune / OuterTune / Metrolist lineage. Vendor it or depend on it, whichever is cleaner. |
| Stream URLs (signature / n-param / PoToken) | NewPipeExtractor. Its team fixes YouTube changes fastest. |
| Playback, media session, notification | Media3 (ExoPlayer + MediaSessionService) |
| Login | WebView sign-in to music.youtube.com, cookies kept. No GMS account picker. |
| Romanization | `android.icu.text.Transliterator` (built in) for non-Japanese; Kuromoji or Sudachi (pick the smaller one that works) for Japanese. In `core`, use ICU4J via an interface so the JVM path works too. |

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
  - Other scripts: ICU defaults.
- Han text, Japanese or Chinese? Decide in this order:
  1. Kana in the string means Japanese.
  2. Kana in a sibling field (artist, album) means Japanese.
  3. Otherwise use the user's default language (setting).
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

## Search

- Filters in a fixed order: Songs, Albums, Artists, Playlists. Videos are excluded by default.
- Results show Latin with the original underneath.

## Process

- Local git repo. Don't create a GitHub repo or a signing key without asking the user.
- Build toolchain: mirror ~/dev/plainfin (AGP, Kotlin, Gradle, SDK at ~/Android/Sdk, JDK under ~/.local/share/JetBrains/Toolbox/apps/air/jbr). Read its build files first.
- Test on an emulator. Never install on the user's own devices without asking first.
- Leave one small runnable check per non-trivial piece: romanization (including the Japanese/Chinese decision), the lyrics parsers (LRC, enhanced LRC), queue filtering.
