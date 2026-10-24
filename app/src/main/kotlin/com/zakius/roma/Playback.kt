package com.zakius.roma

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import roma.core.Streams
import roma.core.again
import roma.core.filterMix
import roma.core.filterRadio
import roma.core.romanizeLyrics
import roma.core.shouldScrobble
import roma.core.song
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

    /** Bumped on every player change and twice a second while playing; the UI reads it to redraw. */
    var tick by mutableIntStateOf(0)
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
    private val loudness = java.util.concurrent.ConcurrentHashMap<String, Float>()

    fun init(c: Context) {
        appContext = c.applicationContext
        val cache = SimpleCache(File(c.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(1L shl 30), StandaloneDatabaseProvider(c))
        val http = OkHttpDataSource.Factory(OkHttpClient.Builder().eventListenerFactory(roma.core.NetLog.factory("media3")).build())
        // Media items carry roma://<videoId>; the stream URL is looked up only when bytes are needed and not cached.
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.scheme != "roma") spec else spec.withUri(Uri.parse(streamUrl(spec.uri.host!!)))
        }
        val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(resolving)
        cached = source
        player = ExoPlayer.Builder(c)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Start after 0.5 s of audio instead of ExoPlayer's 2.5 s; audio downloads far faster than it plays.
            // (The next song's preloading is set right after build, below.)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 500, 2_000).build())
            .build()
        // Media3 prepares the next song in the queue in the background (its first 5 s), so an automatic transition
        // or a skip from the notification finds it ready.
        player.preloadConfiguration = ExoPlayer.PreloadConfiguration(5_000_000L)
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioPositionAdvancing(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                println("ROMALAT MARK playout wallStart=$playoutStartSystemTimeMs up=${System.nanoTime() / 1_000_000} wall=${System.currentTimeMillis()}")
            }
        })
        liked = runCatching { Store.json.decodeFromString<List<String>>(likedFile.readText()).toSet() }.getOrDefault(emptySet())
        syncLikes()
        // The first stream lookup of a run also loads YouTube's player script (about 1 s more). Do it now, on a song
        // seen before, so the first tap doesn't pay for it.
        Store.anySong()?.let { s -> scope.launch(Dispatchers.IO) { runCatching { streamUrl(s.id) } } }
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                tick++
                if (p.isPlaying) startSideWork()
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                error = null
                val e = current() ?: return
                if (Store.radioSettings().skipBanned && Store.bans().banned(e.song)) {
                    main.post { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
                    return
                }
                applyLoudness(e.song.id)
                startedTrack()
                sideWorkFor = e.song.id
                // Everything else waits until the song is audible (startSideWork), so the stream has the network to
                // itself; if it never starts (error, paused right away), it runs after 4 s anyway.
                val id = e.song.id
                main.postDelayed({ if (sideWorkFor == id) startSideWork() }, 4_000)
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
                error = e.cause?.message ?: e.errorCodeName
            }
        })
        scope.launch {
            while (true) {
                delay(500)
                if (player.isPlaying) {
                    tick++
                    countPlayed()
                }
            }
        }
    }

    // The next song's stream URL is looked up while this one plays, so Next and the automatic transition skip that round trip.
    private fun prefetchNext() {
        val i = player.nextMediaItemIndex
        if (i == C.INDEX_UNSET) return
        val next = (player.getMediaItemAt(i).localConfiguration?.tag as? Entry)?.song ?: return
        // Its lyrics and large art too, so they are there the moment it starts: only the one next song, once per song
        // started, and both are kept (lyrics cache, Coil's disk cache) for when it plays. Wasted only if it's skipped.
        next.thumb?.let { coil3.SingletonImageLoader.get(appContext).enqueue(coil3.request.ImageRequest.Builder(appContext).data(bigThumb(it)).build()) }
        scope.launch(Dispatchers.IO) {
            runCatching { streamUrl(next.id) }
            runCatching { lyricsResult(next) }
        }
    }

    // Blocking, on ExoPlayer's loader thread. URLs expire after about 6 hours; reuse them for 5.
    private fun streamUrl(id: String): String {
        synchronized(urls) {
            urls[id]?.let { (url, at) -> if (System.currentTimeMillis() - at < 5 * 3600_000) return url }
        }
        val a = Streams.audio(id, full = fullLookup.remove(id))
        synchronized(urls) { urls[id] = a.url to System.currentTimeMillis() }
        if (!loudness.containsKey(id)) scope.launch(Dispatchers.IO) {
            Streams.loudness(id)?.let { db -> loudness[id] = db; withContext(Dispatchers.Main) { applyLoudness(id) } }
        }
        return a.url
    }

    // YouTube's loudness is relative to its reference level. Only turn loud tracks down: volume can't go above 1.
    private fun applyLoudness(id: String) {
        if (current()?.song?.id != id) return
        val db = loudness[id]
        player.volume = if (db == null || db <= 0f || !Store.bool("play.normalize", true)) 1f else 10f.pow(-db / 20f)
    }

    // ---- Queue ----

    fun current(): Entry? = player.currentMediaItem?.localConfiguration?.tag as? Entry

    fun entries(): List<Entry> = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).localConfiguration?.tag as? Entry }

    private fun mediaItem(e: Entry): MediaItem {
        val s = e.song
        val artistLine = if (s.title.isLatin) s.artistLatin else "${s.artistLatin} · ${s.title.original}"
        return MediaItem.Builder()
            .setMediaId(s.id)
            .setUri("roma://${s.id}")
            .setCustomCacheKey(s.id)
            .setTag(e)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.title.latin)
                    .setArtist(artistLine)
                    .setAlbumTitle(s.album?.name?.latin)
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
    private lateinit var cached: CacheDataSource.Factory

    /** What the player shows: the song being prepared, else the one playing. */
    fun shown(): Song? = pending ?: current()?.song

    private fun play(songs: List<Song>, start: Int, mix: String?, mixNext: String?) {
        if (songs.isEmpty()) return
        roma.core.NetLog.mark("tap.play ${songs[start].id}")
        Store.remember(songs)
        val song = songs[start]
        pendingJob?.cancel()
        pending = song
        pendingJob = scope.launch {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(8_000) { runCatching { precache(song.id) } } // on failure the player's own error handling takes over
            }
            pending = null
            roma.core.NetLog.mark("precached ${song.id}")
            switchTo(songs, start, mix, mixNext)
        }
    }

    /** Next, Previous and queue taps: the current song keeps playing until the target's first bytes are cached. */
    fun skipTo(index: Int) {
        roma.core.NetLog.mark("tap.skip $index")
        val song = (player.getMediaItemAt(index).localConfiguration?.tag as? Entry)?.song ?: return
        pendingJob?.cancel()
        pending = song
        pendingJob = scope.launch {
            withContext(Dispatchers.IO) { withTimeoutOrNull(8_000) { runCatching { precache(song.id) } } }
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
    }

    // Resolves the stream URL and caches the first 32 KB: the container header plus about 1.5 s of audio at YouTube's
    // bitrates, more than the 0.5 s the player needs to start. Waiting for more only delays the switch on slow networks.
    // The key is the media item's cache key (the video ID): under the roma:// URI the player wouldn't find the bytes
    // and would download them again.
    private fun precache(id: String) {
        val spec = androidx.media3.datasource.DataSpec.Builder().setUri("roma://$id").setKey(id).setPosition(0).setLength(32 * 1024L).build()
        androidx.media3.datasource.cache.CacheWriter(cached.createDataSource(), spec, null, null).cache()
    }

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
                    val page = YouTube.next(endpoint, radioContinuation).getOrNull() ?: return@repeat
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
    fun playMix(id: String) {
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

    // ---- Controls ----

    fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.isPlaying) player.pause() else player.play()
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
            }
        }
        PlaybackService.instance?.updateLikeButton()
    }

    fun markLiked(ids: Collection<String>) {
        liked = liked + ids
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

    private val likedFile get() = java.io.File(appContext.filesDir, "liked.json")

    /** After an override or a scheme change: rebuild every name in the queue, lyrics included. */
    fun romanizationChanged() {
        for (i in 0 until player.mediaItemCount) {
            val e = player.getMediaItemAt(i).localConfiguration?.tag as? Entry ?: continue
            player.replaceMediaItem(i, mediaItem(Entry(Store.romanizer.again(e.song), e.section)))
        }
        current()?.let { loadLyrics(it.song) }
        tick++
    }

    // ---- Lyrics ----

    private var lyricsJob: Job? = null

    /** Blocking: the cached lyrics, else every provider asked and the answer cached. */
    private fun lyricsResult(s: Song): LyricsResult {
        val file = File(appContext.cacheDir, "lyrics/${s.id}.json")
        // A cache file from before 0.1.6 holds a bare Lyrics, fails to decode and is searched again.
        runCatching { Store.json.decodeFromString<LyricsResult>(file.readText()) }.getOrNull()?.let { return it }
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

    fun loadLyrics(s: Song) {
        lyricsJob?.cancel()
        if (lyrics?.songId != s.id) lyrics = LyricsState(s.id, null)
        lyricsJob = scope.launch {
            val found = withContext(Dispatchers.IO) {
                val raw = lyricsResult(s)
                val siblings = listOf(s.artistOriginal) + listOfNotNull(s.album?.name?.original)
                LyricsState(s.id, Store.romanizer.romanizeLyrics(raw.lyrics, siblings, s.id), raw.translations)
            }
            lyrics = found
        }
    }

    /** Drops the cached lyrics and asks the providers again, e.g. after changing their order. */
    fun refetchLyrics() {
        val s = current()?.song ?: return
        File(appContext.cacheDir, "lyrics/${s.id}.json").delete()
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

    private fun startedTrack() {
        playedHalfSeconds = 0
        scrobbled = false
        startedAt = System.currentTimeMillis() / 1000
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

class PlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession
    private val likeCommand = SessionCommand("roma.like", Bundle.EMPTY)

    companion object {
        var instance: PlaybackService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = MediaSession.Builder(this, Playback.player)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(s: MediaSession, c: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(s)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(likeCommand).build())
                        .build()

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
fun bigThumb(url: String) = url
    .replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
    .replace(Regex("/(default|mqdefault)\\.jpg"), "/hqdefault.jpg")
