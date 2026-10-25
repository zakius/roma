package com.zakius.roma

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import roma.core.LastFm
import roma.core.Lyrics
import roma.core.LyricsProviders
import roma.core.LyricsQuery
import roma.core.LyricsResult
import roma.core.Translation
import roma.core.Scrobble
import roma.core.ScrobbleQueue
import roma.core.Song
import roma.core.InnerTubeXStreams
import roma.core.StreamException
import roma.core.Streams
import roma.core.again
import roma.core.filterMix
import roma.core.filterRadio
import roma.core.romanizeLyrics
import roma.core.shouldScrobble
import roma.core.song
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.pow

/** A queue entry. Section is "queued" (pinned: radio never moves or removes it) or "radio". */
class Entry(val song: Song, val section: String)

/**
 * Lyrics for the current song: null while loading, [Lyrics] with no lines when no provider had any. The translation
 * layers are attached at display time, so changing the translation language needs no new search.
 */
class LyricsState(val songId: String, val lyrics: Lyrics?, val translations: List<Translation> = emptyList())

/** The one player. The service wraps it in a media session; the UI calls it directly (same process, main thread). */
object Playback {
    lateinit var player: ExoPlayer
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    /** Bumped on every player change; the UI reads it to redraw. */
    var tick by mutableIntStateOf(0)
        private set
    /** Bumped twice a second while playing. Only the seek bar reads it, so the clock doesn't redraw the whole player. */
    var clock by mutableIntStateOf(0)
        private set
    var lyrics by mutableStateOf<LyricsState?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** The "Banned · Undo" bar: the banned song and where it was, until the bar times out. */
    var lastBan by mutableStateOf<Pair<Song, Int>?>(null)
        private set
    var liked by mutableStateOf(setOf<String>())
        private set

    private val urls = HashMap<String, Pair<String, Long>>() // videoId to (url, resolved at)
    private var lastRetry: Pair<String, Long>? = null
    private val fullLookup: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun init(c: Context) {
        appContext = c.applicationContext
        Streams.refuseVisionOs = BuildConfig.REFUSE_VISIONOS
        InnerTubeXStreams.tokenProvider = PoTokens(c)
        cache = SimpleCache(File(c.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(1L shl 30), StandaloneDatabaseProvider(c))
        val http = OkHttpDataSource.Factory(OkHttpClient())
        // Media items carry roma://<videoId>; the stream URL is looked up only when bytes are needed and not cached.
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.scheme != "roma") spec else spec.withUri(Uri.parse(streamUrl(spec.uri.host!!)))
        }
        val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(resolving)
        cached = source
        // Start after 0.5 s of audio instead of ExoPlayer's 2.5 s; audio downloads far faster than it plays. It buffers
        // 50 s ahead, or with "Download songs ahead" the whole song (at most 10 min).
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 10 * 60_000, 500, 2_000).build()
        player = ExoPlayer.Builder(c)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source).setLoadErrorHandlingPolicy(WaitForNetwork))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(AheadLoadControl(load))
            .build()
        Startup.later { scope.launch {
            liked = withContext(Dispatchers.IO) { runCatching { Store.json.decodeFromString<List<String>>(likedFile.readText()).toSet() }.getOrDefault(emptySet()) }
            PlaybackService.instance?.updateLikeButton()
            // The copy on disk serves meanwhile: the whole Likes playlist, page by page, would share the connection
            // with Home's first request.
            delay(5_000)
            syncLikes()
        } }
        Startup.later { restore() }
        watchNetwork()
        watchHeadphones()
        Startup.later { scope.launch(Dispatchers.IO) {
            Streams.keepIn(File(c.cacheDir, "streams.txt"))
            // The first stream lookup of a run sets up NewPipe and asks YouTube for visitor data. Do it now so the first
            // tap doesn't pay for it, on the song Play would resume: its URL is then known too (kept 5 hours).
            val resume = runCatching { positionFile.readText().substringBefore(' ') }.getOrNull()?.takeIf { !fullyCached(it) }
            (resume ?: Store.anySong()?.id)?.let { id -> runCatching { streamUrl(id) } }
            // A song needed the InnerTubeX fallback in the last week: warm that path too (its player script, cipher
            // config and the PoToken WebView, a few seconds cold), within the same limits as the guesses below. Later,
            // not at launch: creating the first WebView blocks the main thread while Home is still coming in.
            if (thrifty()) return@launch
            delay(10_000)
            Streams.recentFallback()?.let { id -> runCatching { streamUrl(id) } }
        } }
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                tick++
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_REPEAT_MODE_CHANGED)) saveQueue()
                if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) savePosition()
                if (p.isPlaying) startSideWork()
                // Radio songs arrive after the song started, and queue edits can change what comes next.
                if (p.isPlaying && sideWorkFor == null && events.contains(Player.EVENT_TIMELINE_CHANGED)) { prefetchNext(); downloadAhead() }
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                error = null
                val e = current() ?: return
                if (Store.radioSettings().skipBanned && Store.bans().banned(e.song)) {
                    main.post { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
                    return
                }
                applyLoudness(e.song.id)
                if (restoringNow) {
                    // Back from a restart, paused: the lyrics show at once (from their cache); nothing is announced
                    // to Last.fm, and the radio top-up and next-song prefetch wait until play is pressed.
                    resetPlayCount()
                    sideWorkFor = e.song.id
                    loadLyrics(e.song, network = false)
                    return
                }
                startedTrack()
                loadLyrics(e.song, network = false) // cached lyrics show at once; the providers wait for the audio
                sideWorkFor = e.song.id
                // Everything else waits until the song is audible (startSideWork), so the stream has the network to
                // itself; if it never starts (error, paused right away), it runs after 4 s anyway.
                val id = e.song.id
                main.postDelayed({ if (sideWorkFor == id) startSideWork() }, 4_000)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) sleepOff()
            }

            override fun onPlayerError(e: PlaybackException) {
                val id = current()?.song?.id
                urls.remove(id) // the URL may have expired; a retry resolves it again
                // A refused stream URL (403, seen once after a seek) gets one silent retry through the full lookup,
                // from the same position. A second failure within a minute shows the error.
                val refused = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode in setOf(403, 410)
                val now = System.currentTimeMillis()
                if (refused && id != null && (lastRetry?.first != id || now - lastRetry!!.second > 60_000)) {
                    lastRetry = id to now
                    fullLookup.add(id)
                    val at = player.currentPosition
                    player.prepare()
                    player.seekTo(at)
                    player.play()
                    return
                }
                // A failed lookup carries its reason in plain words; Media3 may wrap it in its own exceptions.
                error = generateSequence<Throwable>(e) { it.cause }.firstNotNullOfOrNull { it as? StreamException }?.message
                    ?: e.cause?.message ?: e.errorCodeName
            }
        })
        scope.launch {
            while (true) {
                delay(500)
                if (player.isPlaying) {
                    clock++
                    countPlayed()
                    if (clock % 20 == 0) savePosition() // every 10 s, so a killed app loses at most that much
                } else if (sleepAt != 0L) clock++ // the seek bar's "Sleep in" count keeps going while paused
                checkSleep()
            }
        }
    }

    // The next song's stream URL and first bytes are fetched while this one plays, so Next (from the app or the
    // notification) and the automatic transition start it from the cache. Media3's own preloading isn't used: it
    // keeps the next song's download open, and the cache can't serve or store those bytes meanwhile.
    // Its lyrics and large art too, so they are there the moment it starts: only the one next song, once per song,
    // and both are kept (lyrics cache, Coil's disk cache) for when it plays. Wasted only if it's skipped.
    private fun prefetchNext() {
        val i = player.nextMediaItemIndex
        if (i == C.INDEX_UNSET) return
        val next = (player.getMediaItemAt(i).localConfiguration?.tag as? Entry)?.song ?: return
        if (next.id == prefetched) return
        prefetched = next.id
        next.thumb?.let { coil3.SingletonImageLoader.get(appContext).enqueue(coil3.request.ImageRequest.Builder(appContext).data(bigThumb(it)).build()) }
        scope.launch {
            precacheAsync(next.id).await()
            withContext(Dispatchers.IO) { runCatching { if (romanizedLyrics(next) == null && romanizedOnDisk(next) == null) romanize(next, lyricsResult(next)) } }
        }
    }

    private var prefetched: String? = null

    // Blocking, on ExoPlayer's loader thread. URLs expire after about 6 hours; reuse them for 5.
    private fun streamUrl(id: String): String {
        synchronized(urls) {
            urls[id]?.let { (url, at) -> if (System.currentTimeMillis() - at < 5 * 3600_000) return url }
        }
        val a = Streams.audio(id, full = fullLookup.remove(id))
        synchronized(urls) { urls[id] = a.url to System.currentTimeMillis() }
        main.post { applyLoudness(id) }
        return a.url
    }

    // YouTube's loudness is relative to its reference level. Only turn loud tracks down: volume can't go above 1.
    private fun applyLoudness(id: String) {
        if (current()?.song?.id != id) return
        val db = Streams.loudness(id)
        baseVolume = if (db == null || db <= 0f || !Store.bool("play.normalize", true)) 1f else 10f.pow(-db / 20f)
        player.volume = baseVolume
    }

    private var baseVolume = 1f

    // ---- Queue ----

    fun current(): Entry? = player.currentMediaItem?.localConfiguration?.tag as? Entry

    fun songAt(i: Int): Song? = if (i in 0 until player.mediaItemCount) (player.getMediaItemAt(i).localConfiguration?.tag as? Entry)?.song else null

    fun entries(): List<Entry> = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).localConfiguration?.tag as? Entry }

    private fun mediaItem(e: Entry): MediaItem {
        val s = e.song
        val artistLine = if (s.title.isLatin || Store.reads(s.title.original)) s.artistLatin else "${s.artistLatin} · ${s.title.original}"
        return MediaItem.Builder()
            .setMediaId(s.id)
            .setUri("roma://${s.id}")
            .setCustomCacheKey(s.id)
            .setTag(e)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(Store.shown(s.title))
                    .setArtist(artistLine)
                    .setAlbumTitle(s.album?.name?.let(Store::shown))
                    .setArtworkUri(s.thumb?.let { Uri.parse(bigThumb(it)) })
                    .build(),
            )
            .build()
    }

    /** Plays [songs] from [start]; the rest of the list is queued and radio from the started song follows. */
    fun play(songs: List<Song>, start: Int = 0) = play(songs, start, null, null)

    /**
     * The song being prepared after a tap: shown in the player at once, while whatever plays keeps playing until the
     * new song's stream URL is known and its first 32 KB are in the cache. Then the switch starts it without a gap.
     */
    var pending by mutableStateOf<Song?>(null)
        private set
    private var pendingJob: Job? = null
    private var pendingIndex = -1
    private lateinit var cached: CacheDataSource.Factory
    private lateinit var cache: SimpleCache

    /** What the player shows: the song being prepared, else the one playing. */
    fun shown(): Song? = pending ?: current()?.song

    /** The queue position of [shown]: a skip's target while it is prepared, -1 while a new list is. */
    fun shownIndex(): Int = if (pending == null) player.currentMediaItemIndex else pendingIndex

    private fun play(songs: List<Song>, start: Int, mix: String?, mixNext: String?) {
        if (songs.isEmpty()) return
        Store.remember(songs)
        val song = songs[start]
        pendingJob?.cancel()
        pending = song
        pendingIndex = -1
        loadLyrics(song, network = false)
        pendingJob = scope.launch {
            withTimeoutOrNull(8_000) { precacheAsync(song.id).await() } // on failure the player's own error handling takes over
            pending = null
            switchTo(songs, start, mix, mixNext)
        }
    }

    /** Next, Previous and queue taps: the current song keeps playing until the target's first bytes are cached. */
    fun skipTo(index: Int) {
        val song = (player.getMediaItemAt(index).localConfiguration?.tag as? Entry)?.song ?: return
        pendingJob?.cancel()
        pending = song
        pendingIndex = index
        loadLyrics(song, network = false)
        pendingJob = scope.launch {
            withTimeoutOrNull(8_000) { precacheAsync(song.id).await() }
            pending = null
            if (index < player.mediaItemCount) player.seekTo(index, 0)
        }
    }

    fun next() {
        if (player.hasNextMediaItem()) skipTo(player.nextMediaItemIndex)
    }

    /** Like the system's: past 3 s, back to the start of this song; earlier, the song before. */
    fun previous() {
        if (player.currentPosition > 3_000 || !player.hasPreviousMediaItem()) player.seekTo(0) else skipTo(player.previousMediaItemIndex)
    }

    // The song whose lyrics, radio top-up and next-song prefetch still wait for playback to start.
    private var sideWorkFor: String? = null

    private fun startSideWork() {
        val e = current() ?: return
        if (sideWorkFor != e.song.id) return
        sideWorkFor = null
        loadLyrics(e.song)
        topUpRadio()
        prefetchNext()
        downloadAhead()
    }

    private var aheadJob: Job? = null

    /**
     * Opt-in ("Download songs ahead"): the next 3 songs go into the audio cache in full, one after another, after the
     * next song's first bytes. With the current song buffered in full (the load control above), a network drop
     * costs nothing for the next quarter of an hour or so. Songs already cached are skipped.
     */
    fun downloadAhead() {
        if (!Store.bool("play.ahead", false) || aheadJob?.isActive == true) return
        val ids = entries().drop(player.currentMediaItemIndex + 1).take(3).map { it.song.id }
        if (ids.isEmpty()) return
        aheadJob = scope.launch {
            precacheAsync(ids[0]).await()
            withContext(Dispatchers.IO) {
                for (id in ids) {
                    if (fullyCached(id)) continue
                    val spec = androidx.media3.datasource.DataSpec.Builder().setUri("roma://$id").setKey(id).build()
                    runCatching { androidx.media3.datasource.cache.CacheWriter(cached.createDataSource(), spec, null, null).cache() }
                }
            }
        }
    }

    // Resolves the stream URL and caches the first 32 KB: the container header plus about 1.5 s of audio at YouTube's
    // bitrates, more than the 0.5 s the player needs to start. Waiting for more only delays the switch on slow networks.
    // The key is the media item's cache key (the video ID): under the roma:// URI the player wouldn't find the bytes
    // and would download them again.
    private fun precache(id: String) {
        val spec = androidx.media3.datasource.DataSpec.Builder().setUri("roma://$id").setKey(id).setPosition(0).setLength(32 * 1024L).build()
        androidx.media3.datasource.cache.CacheWriter(cached.createDataSource(), spec, null, null).cache()
    }

    // Precaches running, by video ID (main thread only). A tap joins the lookup its touch-down started, and Next
    // joins the next-song prefetch, instead of resolving and downloading the same song twice at once.
    private val precaching = HashMap<String, Deferred<Unit>>()
    private var speculative: Deferred<Unit>? = null

    private fun precacheAsync(id: String): Deferred<Unit> {
        precaching.values.removeAll { !it.isActive }
        return precaching.getOrPut(id) { scope.async(Dispatchers.IO) { runCatching { precache(id) }; Unit } }
    }

    /**
     * A finger landed on a song in a list: start its lookup now, before the tap is recognized; the tap joins it.
     * One at a time, so a scroll that starts on a song row costs one lookup (about 12 KB) and a fling costs no more.
     */
    fun touched(s: Song) {
        if (speculative?.isActive != true) speculative = precacheAsync(s.id)
    }

    // ---- Guesses: songs the user is likely to pick next, prepared as a tap would (stream URL + first 32 KB) ----

    private var guessJob: Job? = null
    private val guessTimes = ArrayDeque<Long>()

    /**
     * Prepares the first [GUESS_SONGS] of [songs], one after another: an opened page's first song, Search's top
     * result, the queue's next songs. A new call replaces what is left of the last one. Each costs about 45 KB
     * (nothing for a song already cached), at most [GUESSES_PER_HOUR] an hour (under 3 MB). Never while the current
     * song is still loading, and only on an unmetered network outside Battery Saver unless the user allows it.
     */
    fun prepare(songs: List<Song>) {
        guessJob?.cancel()
        guessJob = scope.launch {
            for (s in songs.take(GUESS_SONGS)) {
                val now = System.currentTimeMillis()
                while (guessTimes.isNotEmpty() && now - guessTimes.first() > 3600_000) guessTimes.removeFirst()
                if (guessTimes.size >= GUESSES_PER_HOUR || pending != null || player.playbackState == Player.STATE_BUFFERING || thrifty()) return@launch
                guessTimes.addLast(now)
                precacheAsync(s.id).await()
            }
        }
    }

    /** Mobile data or Battery Saver, and the user hasn't allowed guesses there. */
    private fun thrifty(): Boolean {
        if (Store.bool("prepare.always", false)) return false
        val metered = appContext.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered
        return metered || appContext.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode
    }

    private const val GUESS_SONGS = 3
    private const val GUESSES_PER_HOUR = 60

    private fun switchTo(songs: List<Song>, start: Int, mix: String?, mixNext: String?) {
        mixId = mix
        mixContinuation = mixNext
        radioSeed = songs[start]
        radioContinuation = null
        related = null
        shuffleOrder = null
        player.setMediaItems(songs.map { mediaItem(Entry(it, "queued")) }, start, 0)
        player.prepare()
        player.play()
    }

    fun playNext(s: Song) = insertQueued(s, player.currentMediaItemIndex + 1)

    fun addToQueue(s: Song) {
        val list = entries()
        var i = player.currentMediaItemIndex + 1
        while (i < list.size && list[i].section == "queued") i++
        insertQueued(s, i)
    }

    private fun insertQueued(s: Song, at: Int) {
        Store.remember(listOf(s))
        if (player.mediaItemCount == 0) return play(listOf(s))
        // A queued song leaves the radio section: radio entries aren't pinned, queued ones are.
        val list = entries()
        var insertAt = at.coerceAtMost(player.mediaItemCount)
        for (i in list.indices.reversed()) {
            if (i > player.currentMediaItemIndex && list[i].section == "radio" && list[i].song.id == s.id) {
                player.removeMediaItem(i)
                if (i < insertAt) insertAt--
            }
        }
        player.addMediaItem(insertAt, mediaItem(Entry(s, "queued")))
    }

    fun remove(index: Int) {
        if (index != player.currentMediaItemIndex) player.removeMediaItem(index)
    }

    /** Keeps the queued songs and replaces the radio with one seeded from [s]. */
    fun startRadio(s: Song) {
        if (current()?.song?.id != s.id) return play(listOf(s))
        mixId = null
        radioSeed = s
        radioContinuation = null
        related = null
        val list = entries()
        for (i in list.indices.reversed()) if (i > player.currentMediaItemIndex && list[i].section == "radio") player.removeMediaItem(i)
        topUpRadio(force = true)
    }

    // ---- Radio ----

    private var radioSeed: Song? = null
    private var radioContinuation: String? = null
    private var related: Set<String>? = null
    private var radioJob: Job? = null

    private fun topUpRadio(force: Boolean = false) {
        val left = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (left >= 3 || radioJob?.isActive == true) return
        if (mixId != null) return topUpMix()
        if (Store.stopAtEnd && !force) return
        val seed = radioSeed ?: return
        radioJob = scope.launch {
            val settings = Store.radioSettings()
            val already = entries().map { it.song } // read on the main thread: ExoPlayer is single-threaded
            val added = withContext(Dispatchers.IO) {
                if (related == null) related = if (settings.leash == "free") emptySet() else relatedArtists(seed)
                var fresh = emptyList<Song>()
                // A page can be all filtered out; try a few continuations before giving up.
                repeat(4) {
                    if (fresh.isNotEmpty()) return@repeat
                    val endpoint = WatchEndpoint(videoId = seed.id, playlistId = "RDAMVM${seed.id}")
                    // A continuation saved before a restart may have expired: start the radio's pages over (the
                    // songs already queued are filtered out).
                    val page = YouTube.next(endpoint, radioContinuation).getOrNull() ?: run { radioContinuation = null; return@repeat }
                    radioContinuation = page.continuation
                    val candidates = page.items.map { Store.romanizer.song(it) }
                    fresh = filterRadio(candidates, seed, related!!, already, Store.bans(), settings)
                    if (radioContinuation == null) return@repeat
                }
                fresh
            }
            Store.remember(added)
            player.addMediaItems(added.map { mediaItem(Entry(it, "radio")) })
        }
    }

    // ---- Mixes and other generated playlists ----

    private var mixId: String? = null
    private var mixContinuation: String? = null

    /**
     * Plays a personal mix or another generated playlist (RD…) as a playlist: its songs go into "You queued", not radio,
     * and its next page loads when the queue runs low. The radio filters apply; the leash doesn't.
     */
    fun playMix(id: String, loaded: List<Song>? = null) {
        // From its page: the songs are there already (every distinct one, see loadDetail), so it starts at once.
        if (loaded != null) return play(filterMix(loaded, emptyList(), Store.bans(), Store.radioSettings()), 0, id.removePrefix("VL"), null)
        scope.launch {
            val page = withContext(Dispatchers.IO) { YouTube.playlist(id.removePrefix("VL")).getOrNull() }
            if (page == null) { error = "Couldn't load this playlist."; return@launch }
            val songs = withContext(Dispatchers.IO) { filterMix(page.songs.map { Store.romanizer.song(it) }, emptyList(), Store.bans(), Store.radioSettings()) }
            play(songs, 0, id.removePrefix("VL"), page.songsContinuation)
        }
    }

    private fun topUpMix() {
        val id = mixId ?: return
        radioJob = scope.launch {
            val already = entries().map { it.song }
            val added = withContext(Dispatchers.IO) {
                var fresh = emptyList<Song>()
                // Generated playlists' continuations loop back to songs already queued, and filtering can empty a
                // page; a few pages with nothing new end the mix.
                repeat(3) {
                    val c = mixContinuation
                    if (fresh.isNotEmpty() || c == null) return@repeat
                    val page = YouTube.playlistContinuation(c).getOrNull() ?: return@repeat
                    mixContinuation = page.continuation
                    fresh = filterMix(page.songs.map { Store.romanizer.song(it) }, already, Store.bans(), Store.radioSettings())
                }
                fresh
            }
            if (mixId != id) return@launch // another queue started meanwhile
            if (added.isEmpty()) {
                // The mix is done (or the network failed): radio from here on, unless the queue should stop.
                mixId = null
                main.post { topUpRadio() }
                return@launch
            }
            Store.remember(added)
            player.addMediaItems(added.map { mediaItem(Entry(it, "queued")) })
        }
    }

    private suspend fun relatedArtists(seed: Song): Set<String> {
        val id = seed.firstArtistId ?: return emptySet()
        val page = YouTube.artist(id).getOrNull() ?: return emptySet()
        return page.sections.flatMap { it.items }.filterIsInstance<ArtistItem>().map { it.id }.toSet()
    }

    // ---- Sleep timer ----

    /** When the sleep timer pauses playback, in [SystemClock.elapsedRealtime] time; 0 when it's off. */
    var sleepAt by mutableLongStateOf(0L)
        private set
    /** The sleep timer pauses when the playing song ends. */
    var sleepAfterSong by mutableStateOf(false)
        private set

    fun sleepIn(minutes: Int) {
        sleepOff()
        sleepAt = SystemClock.elapsedRealtime() + minutes * 60_000L
    }

    fun sleepAtSongEnd() {
        sleepOff()
        sleepAfterSong = true
        player.pauseAtEndOfMediaItems = true
    }

    fun sleepOff() {
        if (sleepAt != 0L) player.volume = baseVolume // it may have been fading
        sleepAt = 0L
        sleepAfterSong = false
        player.pauseAtEndOfMediaItems = false
    }

    // Runs on the half-second clock, so the timer costs no wakeups of its own. The last 10 s fade out instead of
    // cutting a phrase off. A timer that runs out while paused just turns off.
    private fun checkSleep() {
        if (sleepAt == 0L) return
        val left = sleepAt - SystemClock.elapsedRealtime()
        if (left > 10_000) return
        if (left > 0 && player.isPlaying) { player.volume = baseVolume * left / 10_000f; return }
        if (left > 0) return
        player.pause()
        sleepOff()
    }

    // ---- Controls ----

    // ---- Continuity: the queue, the song and its position survive a restart ----

    /** What queue.json holds. The position is kept apart (position file): it changes every few seconds. */
    @Serializable
    private class Saved(
        val songs: List<Song>,
        val sections: List<String>,
        val index: Int,
        val repeat: Int,
        val shuffleOrder: List<String>?,
        val radioSeed: Song?,
        val radioContinuation: String?,
        val mixId: String?,
        val mixContinuation: String?,
    )

    /** True until the saved queue is back (or found missing). The player shows nothing rather than "Nothing playing". */
    var restoring by mutableStateOf(true)
        private set
    private var restoringNow = false
    private val restoreWaiters = ArrayList<() -> Unit>()

    private val queueFile get() = File(appContext.filesDir, "queue.json")
    private val positionFile get() = File(appContext.filesDir, "position")
    // One writer thread, in order: a later save never lands before an earlier one.
    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var saveQueued = false

    // A burst of queue changes (radio adding 20 songs) is written once, after it.
    private fun saveQueue() {
        if (restoring || saveQueued) return
        saveQueued = true
        main.post {
            saveQueued = false
            val list = entries()
            val s = Saved(list.map { it.song }, list.map { it.section }, player.currentMediaItemIndex, player.repeatMode, shuffleOrder, radioSeed, radioContinuation, mixId, mixContinuation)
            saver.execute { writeAtomically(queueFile, Store.json.encodeToString(s)) }
        }
    }

    private fun savePosition() {
        if (restoring) return
        val id = current()?.song?.id ?: return
        val line = "$id ${player.currentPosition}"
        saver.execute { writeAtomically(positionFile, line) }
    }

    // A kill mid-write leaves the old file whole instead of half a new one.
    private fun writeAtomically(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }

    private fun restore() {
        scope.launch {
            // Everything but handing the items to the player happens off the main thread: building a few hundred items
            // reads settings per song, and the cache check waits until the audio cache has scanned its files.
            val r = withContext(Dispatchers.IO) {
                val saved = runCatching { Store.json.decodeFromString<Saved>(queueFile.readText()) }.getOrNull()?.takeIf { it.songs.isNotEmpty() }
                    ?: return@withContext null
                val position = runCatching { positionFile.readText().split(" ").let { it[0] to it[1].toLong() } }.getOrNull()
                val index = saved.index.coerceIn(0, saved.songs.size - 1)
                val id = saved.songs[index].id
                Restored(saved, index, if (position?.first == id) position.second else 0L,
                    saved.songs.mapIndexed { i, s -> mediaItem(Entry(s, saved.sections.getOrElse(i) { "queued" })) }, fullyCached(id))
            }
            // A song picked while the file was read wins.
            if (r != null && player.mediaItemCount == 0 && pending == null) {
                val saved = r.saved
                radioSeed = saved.radioSeed
                radioContinuation = saved.radioContinuation
                mixId = saved.mixId
                mixContinuation = saved.mixContinuation
                shuffleOrder = saved.shuffleOrder
                restoringNow = true
                player.setMediaItems(r.items, r.index, r.at)
                player.repeatMode = saved.repeat
                restoringNow = false
                // A song fully on disk is prepared now; any other is prepared by Play, which reads what's cached
                // before asking the network, so opening the app to browse costs no data.
                if (r.cached) player.prepare()
            }
            restoring = false
            if (player.mediaItemCount == 0 && pending == null && Nav.tab == TAB_PLAYER) Nav.tab = TAB_HOME
            restoreWaiters.forEach { it() }
            restoreWaiters.clear()
        }
    }

    private class Restored(val saved: Saved, val index: Int, val at: Long, val items: List<MediaItem>, val cached: Boolean)

    /** The whole song is in the audio cache: it plays, seeks and repeats without the network. */
    fun fullyCached(id: String): Boolean {
        val length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(id))
        return length > 0 && cache.isCached(id, 0, length)
    }

    /** Runs [f] once the saved queue is back: at once if it already is. */
    fun afterRestore(f: () -> Unit) = if (restoring) restoreWaiters.add(f).let { } else f()

    // Pause while buffering (waiting for the network) pauses; Play prepares an idle player and restarts an ended one.
    fun togglePlay() = androidx.media3.common.util.Util.handlePlayPauseButtonAction(player)

    // ---- Headphones ----

    // When the headphones went away and paused the song (Media3's "becoming noisy"); 0 otherwise.
    private var unpluggedAt = 0L

    /**
     * Opt-in: headphones, Bluetooth earbuds or a car that come back within an hour of pausing the song by going away
     * resume it. Only that pause counts: a song the user paused stays paused. The system calls back on device
     * changes; nothing polls.
     */
    private fun watchHeadphones() {
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                unpluggedAt = if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) android.os.SystemClock.elapsedRealtime() else 0L
            }
        })
        val audio = appContext.getSystemService(android.media.AudioManager::class.java)
        audio.registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<android.media.AudioDeviceInfo>) {
                if (unpluggedAt == 0L || !Store.bool("play.resumeOnHeadphones", false)) return
                if (android.os.SystemClock.elapsedRealtime() - unpluggedAt > 3600_000) return
                if (added.none { it.isSink && it.type in HEADPHONES }) return
                unpluggedAt = 0L
                player.play()
            }
        }, main)
    }

    private val HEADPHONES = setOf(
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        26 /* TYPE_BLE_HEADSET */, 27 /* TYPE_BLE_SPEAKER */, 23 /* TYPE_HEARING_AID */,
    )

    // ---- Network drops ----

    /** A validated network is up. Changes come from the system's callback, never from polling. */
    var online by mutableStateOf(true)
        private set

    /** Playing, but stuck until the network is back: the player shows a quiet note instead of an error. */
    val waitingForNetwork get() = !online && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING

    private fun watchNetwork() {
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: android.net.Network, caps: android.net.NetworkCapabilities) {
                val up = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                main.post {
                    // Radio that couldn't load while offline loads now.
                    if (up && !online && player.isPlaying) topUpRadio()
                    online = up
                }
            }

            override fun onLost(n: android.net.Network) { main.post { online = false } }
        }, main)
    }

    /**
     * Without a connection the player keeps playing what it has, then waits, retrying every 2 s for up to 10 min
     * (an attempt with no network fails at once and costs nothing), and goes on by itself once a retry succeeds. Other
     * errors keep Media3's usual 3 retries. ponytail: fixed 2 s; a nudge from the network callback would save up to 2 s.
     */
    private object WaitForNetwork : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long = when {
            noConnection(info.exception) -> if (info.errorCount <= 300) 2_000 else C.TIME_UNSET
            info.errorCount > DEFAULT_MIN_LOADABLE_RETRY_COUNT -> C.TIME_UNSET
            else -> super.getRetryDelayMsFor(info)
        }

        // The error reaches the player only once retrying gives up (above), not after a fixed count.
        override fun getMinimumLoadableRetryCount(dataType: Int) = Int.MAX_VALUE

        private fun noConnection(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
            it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.SocketTimeoutException ||
                it is java.net.NoRouteToHostException || it is java.net.SocketException || it.message == Streams.NO_CONNECTION
        }
    }

    fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // Shuffle reorders only the queued songs after the current one, so radio stays after them; off restores the order.
    private var shuffleOrder: List<String>? = null
    val shuffled get() = shuffleOrder != null

    fun toggleShuffle() {
        val cur = player.currentMediaItemIndex
        val list = entries()
        var end = cur + 1
        while (end < list.size && list[end].section == "queued") end++
        val queued = list.subList(cur + 1, end)
        val order = shuffleOrder
        val newOrder = if (order == null) {
            shuffleOrder = queued.map { it.song.id }
            queued.shuffled()
        } else {
            shuffleOrder = null
            queued.sortedBy { e -> order.indexOf(e.song.id).let { if (it < 0) Int.MAX_VALUE else it } }
        }
        if (queued.isEmpty()) { tick++; return }
        player.replaceMediaItems(cur + 1, end, newOrder.map { mediaItem(it) })
        tick++
    }

    /** Never: ban the current song and skip it. The bar offers Undo for 5 seconds. */
    fun never() {
        val e = current() ?: return
        val index = player.currentMediaItemIndex
        Store.ban("song", e.song.id, e.song.title.latin)
        lastBan = e.song to index
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.pause()
        scope.launch {
            delay(5000)
            if (lastBan?.first?.id == e.song.id) lastBan = null
        }
    }

    fun undoBan() {
        val (song, index) = lastBan ?: return
        lastBan = null
        Store.unban("song", song.id)
        if (index < player.mediaItemCount && entries()[index].song.id == song.id) player.seekTo(index, 0)
    }

    fun toggleLike(s: Song? = current()?.song) {
        val song = s ?: return
        if (!Store.signedIn) return
        val like = song.id !in liked
        liked = if (like) liked + song.id else liked - song.id
        scope.launch(Dispatchers.IO) {
            if (YouTube.likeVideo(song.id, like).isFailure) withContext(Dispatchers.Main) {
                liked = if (like) liked - song.id else liked + song.id
                PlaybackService.instance?.updateLikeButton()
            }
        }
        PlaybackService.instance?.updateLikeButton()
    }

    fun markLiked(ids: Collection<String>) {
        liked = liked + ids
        PlaybackService.instance?.updateLikeButton()
    }

    /** Replaces the liked set with the account's "Liked songs" playlist (LM), so likes made elsewhere show up. */
    fun syncLikes() {
        if (!Store.signedIn) return
        scope.launch {
            val ids = withContext(Dispatchers.IO) {
                val first = YouTube.playlist("LM").getOrNull() ?: return@withContext null
                val all = first.songs.mapTo(LinkedHashSet()) { it.id }
                var cont = first.songsContinuation
                // ponytail: stops at 200 pages (about 20,000 likes); raise it if someone has more.
                repeat(200) {
                    val c = cont ?: return@repeat
                    val more = YouTube.playlistContinuation(c).getOrNull() ?: return@withContext null
                    more.songs.mapTo(all) { it.id }
                    cont = more.continuation
                }
                all
            } ?: return@launch
            liked = ids
            likedFile.writeText(Store.json.encodeToString(ids.toList()))
            PlaybackService.instance?.updateLikeButton()
        }
    }

    private val likedFile get() = java.io.File(appContext.cacheDir, "liked.json")

    private var romanizeJob: Job? = null

    /**
     * After an override or a scheme change: rebuild every name in the queue, lyrics included. The names are romanized
     * off the main thread (a 1,000-song queue takes a while) and swapped in with one queue change, not one per song.
     */
    fun romanizationChanged() {
        Store.bumpRomanizationVersion()
        forgetRomanizedLyrics()
        romanizeJob?.cancel()
        romanizeJob = scope.launch {
            val before = entries()
            val again = withContext(Dispatchers.Default) { before.map { Entry(Store.romanizer.again(it.song), it.section) } }
            // Radio may have added songs meanwhile; any other change to the queue means starting over.
            val now = entries()
            if (now.size < before.size || before.indices.any { now[it] !== before[it] }) return@launch romanizationChanged()
            player.replaceMediaItems(0, before.size, again.map { mediaItem(it) })
            tick++
        }
        current()?.let { loadLyrics(it.song) }
    }

    // ---- Lyrics ----

    private var lyricsJob: Job? = null

    /** Blocking: the cached lyrics, else every provider asked and the answer cached. */
    private fun lyricsResult(s: Song): LyricsResult = lyricsResult(s, network = true)!!

    /** The song's lyrics from the disk cache, or (with [network]) from the providers. Null: not cached, no network asked. */
    private fun lyricsResult(s: Song, network: Boolean): LyricsResult? {
        val file = File(appContext.cacheDir, "lyrics/${s.id}.json")
        // A cache file from before 0.1.6 holds a bare Lyrics, fails to decode and is searched again.
        runCatching { Store.json.decodeFromString<LyricsResult>(file.readText()) }.getOrNull()?.let { return it }
        if (!network) return null
        val q = LyricsQuery(s.id, s.title.original, s.artistOriginal, s.album?.name?.original, s.durationSec)
        val providers = Store.lyricsOrder().filter { Store.lyricsEnabled(it) }
        val search = LyricsProviders.find(q, providers, Store.icu)
        val r = search.result ?: LyricsResult(Lyrics("", emptyList(), false))
        // "No lyrics" is kept only when every provider answered; after a failure the next play asks again.
        if (search.result != null || search.complete) {
            file.parentFile!!.mkdirs()
            file.writeText(Store.json.encodeToString(r))
        }
        return r
    }

    /**
     * Shows the song's lyrics. Already romanized lyrics come from memory or disk at once; cached raw lyrics are
     * romanized and kept; only a song with nothing cached asks the providers, and only with [network] (callers pass
     * false until the song is audible, so the stream has the network to itself).
     */
    fun loadLyrics(s: Song, network: Boolean = true) {
        romanizedLyrics(s)?.let { lyricsJob?.cancel(); lyrics = it; return }
        lyricsJob?.cancel()
        if (lyrics?.songId != s.id) lyrics = LyricsState(s.id, null)
        lyricsJob = scope.launch {
            val found = withContext(Dispatchers.IO) { romanizedOnDisk(s) ?: romanize(s, lyricsResult(s, network) ?: return@withContext null) }
            if (found != null) lyrics = found
        }
    }

    // Romanized lyrics, kept so that opening lyrics again (or after a restart) skips Sudachi: in memory for the last
    // 40 songs, and on disk next to the raw copy. The key carries the romanization version, which changes with the
    // settings and corrections, so stale romanizations are never shown (and their files are deleted then).
    private val romanizedCache = object : LinkedHashMap<String, LyricsState>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsState>) = size > 40
    }
    private fun romanizedKey(id: String) = "$id@${Store.romanizationVersion}"
    private fun romanizedFile(id: String) = File(appContext.cacheDir, "lyrics/$id.r${Store.romanizationVersion}.json")

    private fun romanizedLyrics(s: Song): LyricsState? = synchronized(romanizedCache) { romanizedCache[romanizedKey(s.id)] }

    private fun romanizedOnDisk(s: Song): LyricsState? {
        val r = runCatching { Store.json.decodeFromString<LyricsResult>(romanizedFile(s.id).readText()) }.getOrNull() ?: return null
        return LyricsState(s.id, r.lyrics, r.translations).also { synchronized(romanizedCache) { romanizedCache[romanizedKey(s.id)] = it } }
    }

    private fun romanize(s: Song, raw: LyricsResult): LyricsState {
        val siblings = listOf(s.artistOriginal) + listOfNotNull(s.album?.name?.original)
        val done = Store.romanizer.romanizeLyrics(raw.lyrics, siblings, s.id)
        runCatching { romanizedFile(s.id).writeText(Store.json.encodeToString(LyricsResult(done, raw.translations, raw.rule))) }
        return LyricsState(s.id, done, raw.translations).also { synchronized(romanizedCache) { romanizedCache[romanizedKey(s.id)] = it } }
    }

    /** After a romanization change: the kept romanizations no longer apply. */
    fun forgetRomanizedLyrics() {
        synchronized(romanizedCache) { romanizedCache.clear() }
        scope.launch(Dispatchers.IO) { File(appContext.cacheDir, "lyrics").listFiles { f -> f.name.contains(".r") }?.forEach { it.delete() } }
    }

    /** Drops the cached lyrics and asks the providers again, e.g. after changing their order. */
    fun refetchLyrics() {
        val s = current()?.song ?: return
        File(appContext.cacheDir, "lyrics/${s.id}.json").delete()
        romanizedFile(s.id).delete()
        synchronized(romanizedCache) { romanizedCache.remove(romanizedKey(s.id)) }
        lyrics = null
        loadLyrics(s)
    }

    // ---- Last.fm ----

    private var playedHalfSeconds = 0
    private var scrobbled = false
    private var startedAt = 0L

    private fun lastFm(): Pair<LastFm, String>? {
        val key = Store.str("lastfm.key", "")
        val secret = Store.str("lastfm.secret", "")
        val sk = Store.str("lastfm.session", "")
        if (key.isBlank() || secret.isBlank() || sk.isBlank()) return null
        return LastFm(key, secret) to sk
    }

    private fun scrobbleOf(s: Song) = Scrobble(s.artists.firstOrNull()?.name?.original ?: "", s.title.original, s.album?.name?.original, startedAt, s.durationSec)

    private fun resetPlayCount() {
        playedHalfSeconds = 0
        scrobbled = false
        startedAt = System.currentTimeMillis() / 1000
    }

    private fun startedTrack() {
        resetPlayCount()
        val s = current()?.song ?: return
        val (fm, sk) = lastFm() ?: return
        val now = scrobbleOf(s)
        scope.launch(Dispatchers.IO) { runCatching { fm.nowPlaying(sk, now) } }
    }

    private fun countPlayed() {
        playedHalfSeconds++
        val s = current()?.song ?: return
        val duration = if (s.durationSec > 0) s.durationSec else (player.duration / 1000).toInt()
        if (scrobbled || !shouldScrobble(duration, playedHalfSeconds / 2)) return
        scrobbled = true
        if (Store.str("lastfm.session", "").isBlank()) return
        scrobbleQueue().add(scrobbleOf(s))
        flushScrobbles()
    }

    fun scrobbleQueue() = ScrobbleQueue(File(appContext.filesDir, "scrobbles.json"))

    fun flushScrobbles() {
        val (fm, sk) = lastFm() ?: return
        scope.launch(Dispatchers.IO) { runCatching { scrobbleQueue().flush { fm.scrobble(sk, it) } } }
    }
}

/**
 * [load] as is, except that it stops 50 s ahead unless "Download songs ahead" is on. Kotlin's `by` doesn't forward
 * Java's default methods, so the ones the player calls are forwarded by hand.
 */
private class AheadLoadControl(val load: DefaultLoadControl) : androidx.media3.exoplayer.LoadControl {
    override fun shouldContinueLoading(p: androidx.media3.exoplayer.LoadControl.Parameters) =
        load.shouldContinueLoading(p) && (p.bufferedDurationUs < 50_000_000 || Store.prefs.getBoolean("play.ahead", false))
    override fun getAllocator(id: androidx.media3.exoplayer.analytics.PlayerId) = load.getAllocator(id)
    override fun onPrepared(id: androidx.media3.exoplayer.analytics.PlayerId) = load.onPrepared(id)
    override fun onStopped(id: androidx.media3.exoplayer.analytics.PlayerId) = load.onStopped(id)
    override fun onReleased(id: androidx.media3.exoplayer.analytics.PlayerId) = load.onReleased(id)
    override fun onTracksSelected(p: androidx.media3.exoplayer.LoadControl.Parameters, groups: androidx.media3.exoplayer.source.TrackGroupArray, selections: Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>) = load.onTracksSelected(p, groups, selections)
    override fun getBackBufferDurationUs(id: androidx.media3.exoplayer.analytics.PlayerId) = load.getBackBufferDurationUs(id)
    override fun retainBackBufferFromKeyframe(id: androidx.media3.exoplayer.analytics.PlayerId) = load.retainBackBufferFromKeyframe(id)
    override fun shouldStartPlayback(p: androidx.media3.exoplayer.LoadControl.Parameters) = load.shouldStartPlayback(p)
    override fun shouldContinuePreloading(id: androidx.media3.exoplayer.analytics.PlayerId, t: androidx.media3.common.Timeline, period: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId, bufferedDurationUs: Long) =
        load.shouldContinuePreloading(id, t, period, bufferedDurationUs)
}

class PlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession
    private val likeCommand = SessionCommand("roma.like", Bundle.EMPTY)

    companion object {
        var instance: PlaybackService? = null
    }

    override fun onCreate() {
        Startup.go() // started without a screen (a headset's Play, the system's resume): nothing waits for a frame
        super.onCreate()
        instance = this
        session = MediaSession.Builder(this, Playback.player)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(s: MediaSession, c: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(s)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(likeCommand).build())
                        .build()

                // Play from a headset, a car or the system's media controls after the app was closed or the phone
                // restarted: the queue saved before is back (Playback.restore); hand it over once it is.
                override fun onPlaybackResumption(s: MediaSession, c: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val result = com.google.common.util.concurrent.SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                    Playback.afterRestore {
                        val p = Playback.player
                        if (p.mediaItemCount == 0) result.setException(UnsupportedOperationException("nothing saved"))
                        else result.set(MediaSession.MediaItemsWithStartPosition((0 until p.mediaItemCount).map { p.getMediaItemAt(it) }, p.currentMediaItemIndex, p.currentPosition))
                    }
                    return result
                }

                override fun onCustomCommand(s: MediaSession, c: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (command.customAction == likeCommand.customAction) Playback.toggleLike()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
        updateLikeButton()
        Playback.player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) = updateLikeButton()
        })
    }

    /** Notification and lock screen: prev, play/pause, next from the system, plus Like. Never is left out on purpose. */
    fun updateLikeButton() {
        val liked = Playback.current()?.song?.id in Playback.liked
        val button = CommandButton.Builder(if (liked) CommandButton.ICON_THUMB_UP_FILLED else CommandButton.ICON_THUMB_UP_UNFILLED)
            .setDisplayName(if (liked) "Unlike" else "Like")
            .setSessionCommand(likeCommand)
            .setEnabled(Store.signedIn)
            .build()
        session.setMediaButtonPreferences(listOf(button))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (!Playback.player.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        instance = null
        session.release()
        super.onDestroy()
    }
}

/**
 * Lists get small thumbnails (60–120 px); the player and the media notification need a large one.
 * Google-hosted art takes its size in the URL; YouTube video stills come in fixed sizes, hqdefault (480 px) always exists.
 */
fun bigThumb(url: String) = url.replace(BIG_WH, "=w544-h544").replace(SMALL_STILL, "/hqdefault.jpg")

// Compiled once: a 1,000-song playlist builds 1,000 media items on the main thread when it starts.
private val BIG_WH = Regex("=w\\d+-h\\d+")
private val SMALL_STILL = Regex("/(default|mqdefault)\\.jpg")
