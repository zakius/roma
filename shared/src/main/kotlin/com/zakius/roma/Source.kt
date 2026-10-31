package com.zakius.roma

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.serialization.Serializable
import roma.core.Item
import roma.core.LyricsProvider
import roma.core.Song
import roma.core.Streams
import roma.core.YouTubeLyrics
import roma.core.item
import roma.core.song

@Serializable
class Section(val title: roma.core.Text, val items: List<Item>)

@Serializable
class Detail(val title: roma.core.Text, val subtitle: String, val thumb: String?, val songs: List<Song>, val items: List<Item>)

/** Search results. [corrected], [autoCorrected] and [originalParams] are YouTube's spelling correction; null elsewhere. */
class Found(val items: List<Item>, val corrected: String? = null, val autoCorrected: Boolean = false, val originalParams: String? = null)

/** Songs and where the next page starts: null on the last page. */
class SongPage(val songs: List<Song>, val next: String?)

/**
 * Where music comes from: YouTube Music or a Subsonic server (docs/subsonic.md). The screens browse [Sources.chosen];
 * everything about one song, album or playlist goes to the source that owns its id ([Sources.of]). Names come back
 * romanized, and songs that enter the app are remembered in the local index.
 */
interface Source {
    /** Shown where results are headed and in Settings. */
    val name: String
    /** Changes when the account or server does: what Home holds belongs to it. */
    val account: String?

    /** One page of Home's shelves, from [from] (null: the first). */
    suspend fun home(from: String?): Pair<String?, List<Section>>
    /** Home's pinned row holds the account's personal mixes; otherwise the library's own tiles. */
    val mixes: Boolean

    /** The search runs over the local index: then there's no "Seen before" section above it. */
    val searchIsLocal: Boolean
    suspend fun suggestions(query: String): List<String>
    /** [filter]: Songs, Albums, Artists, Playlists, as Search lists them. [asTyped]: YouTube's params without correction. */
    suspend fun search(query: String, filter: Int, asTyped: String?, partial: suspend (Found) -> Unit): Found

    /** The Library tab has something to show (YouTube Music: signed in). */
    val libraryReady: Boolean
    /** Library tab [tab] (Playlists, Albums, Artists) as a key for its kept copy. */
    fun libraryKey(tab: Int): String
    suspend fun library(tab: Int, partial: suspend (List<Item>) -> Unit): List<Item>

    /** An album, playlist or artist page. */
    suspend fun detail(p: Page, partial: suspend (Detail) -> Unit): Detail

    /** A page of radio after [seed]; null when it couldn't load. */
    suspend fun radio(seed: Song, from: String?): SongPage?
    /** The seed's related artists' ids, for the radio's leash. */
    suspend fun relatedArtists(seed: Song): Set<String>
    /** A playlist or mix played as one: its first page, or the page at [from]. Null when it couldn't load. */
    suspend fun playlist(id: String, from: String? = null): SongPage?
    /** The id of the liked songs, played from Home's Liked tile. */
    val likedId: String

    val canLike: Boolean
    /** Blocking. Whether the server took it. */
    fun like(id: String, like: Boolean): Boolean
    /** Blocking. Null when the server couldn't say. */
    fun liked(id: String): Boolean?
    /** Blocking. Whether an album or artist page is in the library; null where the source can't add pages. */
    fun saved(p: Page): InLibrary? = null
    /** Blocking. Adds the page to the library or takes it out; whether the server took it. */
    fun save(p: Page, on: Boolean) = false

    /** Blocking, on the player's loader thread: the song's stream URL. */
    fun streamUrl(id: String, full: Boolean): String
    /** dB above the reference level, when known. */
    fun loudness(id: String): Float?

    /** The source's own lyrics, the reference the other providers' text is matched against; null when it has none. */
    val lyrics: LyricsProvider?
    val hasComments: Boolean
    /** Blocking. A link to the song for Share; null when there's none. */
    fun shareUrl(s: Song): String?
    /** Blocking. Where the song's audio comes from, shown in its menu for debugging; null when there's nothing to tell. */
    fun origin(s: Song): String? = null

    /** roma scrobbles this source's songs to Last.fm itself ([roma.core.scrobblesToLastFm]). */
    val lastFm: Boolean
    /** A song of this source started: tell the source, if it takes plays. */
    fun nowPlaying(s: Song)
    /** A song of this source was listened to past the scrobble point. */
    fun played(s: roma.core.Scrobble)
}

// Debugging aid, to remove: a server song's tag on song rows and the player. oasis ids of songs streamed from YouTube
// start "yt-"; every other server song is taken as a file.
const val SHOW_ORIGIN = true
fun originTag(s: Song) = if (!SHOW_ORIGIN || !roma.core.SubsonicIds.of(s.id)) null else if (s.id.startsWith("s.yt-")) "[YT]" else "[FILE]"

/** [note]: how getting it is going, when the server fetches what's added (oasis through Lidarr). */
class InLibrary(val saved: Boolean, val note: String? = null)

object Sources {
    /** The backend the screens browse. */
    val chosen: Source get() = if (Store.str("source", "ytm") == "subsonic") SubsonicSource else YouTubeMusic

    /** The source of an id: Subsonic ids start with "s.", which no YouTube id holds. */
    fun of(id: String): Source = if (roma.core.SubsonicIds.of(id)) SubsonicSource else YouTubeMusic
}

/** YouTube Music through innertube. */
object YouTubeMusic : Source {
    override val name = "YouTube Music"
    override val account get() = Store.cookie

    override suspend fun home(from: String?): Pair<String?, List<Section>> {
        Store.ensureVisitorData()
        val p = YouTube.home(from).getOrThrow()
        return p.continuation to p.sections.map { sec ->
            val items = sec.items.mapNotNull { Store.romanizer.item(it) }
            Store.remember(items.mapNotNull { it.song })
            Section(Store.romanizer.text(sec.title), items)
        }
    }
    override val mixes = true

    private val FILTERS = listOf(
        YouTube.SearchFilter.FILTER_SONG,
        YouTube.SearchFilter.FILTER_ALBUM,
        YouTube.SearchFilter.FILTER_ARTIST,
        YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST,
    )

    override val searchIsLocal = false
    override suspend fun suggestions(query: String) = YouTube.searchSuggestions(query).getOrNull()?.queries.orEmpty()

    override suspend fun search(query: String, filter: Int, asTyped: String?, partial: suspend (Found) -> Unit): Found {
        val r = YouTube.search(query, asTyped?.let { YouTube.SearchFilter(it) } ?: FILTERS[filter]).getOrThrow()
        val items = r.items
            .filter { it !is SongItem || !it.isVideoSong || filter != 0 }
            .mapNotNull { Store.romanizer.item(it) }
            .also { list -> Store.remember(list.mapNotNull { it.song }) }
        return Found(items, r.correctedQuery, r.autoCorrected, r.originalParams)
    }

    override val libraryReady get() = Store.signedIn
    private val LIBRARY = listOf("FEmusic_liked_playlists", "FEmusic_liked_albums", "FEmusic_library_corpus_track_artists")
    override fun libraryKey(tab: Int) = LIBRARY[tab]

    /** All pages of a library tab: the first page holds only about 25 items; each page shows as it arrives. */
    override suspend fun library(tab: Int, partial: suspend (List<Item>) -> Unit): List<Item> {
        val first = YouTube.library(LIBRARY[tab]).getOrThrow()
        val all = first.items.mapNotNull { Store.romanizer.item(it) }.toMutableList()
        var cont = first.continuation
        // ponytail: stops at 50 pages (about 1,250 items); raise it if a library is bigger.
        repeat(50) {
            val c = cont ?: return@repeat
            partial(all.toList())
            val more = YouTube.libraryContinuation(c).getOrNull() ?: return@repeat
            all.addAll(more.items.mapNotNull { Store.romanizer.item(it) })
            cont = more.continuation
        }
        return all
    }

    override suspend fun detail(p: Page, partial: suspend (Detail) -> Unit): Detail {
        val r = Store.romanizer
        return when (p.kind) {
            "album" -> {
                val a = YouTube.album(p.id).getOrThrow()
                val artists = a.album.artists.orEmpty().joinToString(", ") { it.name }
                val songs = a.songs.map { r.song(it) }
                Store.remember(songs)
                Detail(r.text(a.album.title, listOf(artists)), listOfNotNull(r.text(artists).latin, a.album.year?.toString()).joinToString(" · "), a.album.thumbnail, songs, emptyList())
            }
            "playlist" -> {
                val id = p.id.removePrefix("VL")
                val pl = YouTube.playlist(id).getOrThrow()
                val title = r.text(pl.playlist.title)
                val author = pl.playlist.author?.name?.let { r.text(it).latin } ?: ""
                // Generated playlists (RD…) loop: their continuations return songs already listed.
                val songs = ArrayList<Song>()
                val seen = HashSet<String>()
                fun add(items: List<SongItem>) = items.filter { seen.add(it.id) }.map { r.song(it) }.also { songs += it; Store.remember(it) }
                add(pl.songs)
                var cont = pl.songsContinuation
                // ponytail: loads at most 10 continuation pages (about 1000 songs); page on scroll if libraries get bigger.
                repeat(10) {
                    val c = cont ?: return@repeat
                    partial(Detail(title, author, pl.playlist.thumbnail, songs.toList(), emptyList()))
                    val more = YouTube.playlistContinuation(c).getOrNull() ?: return@repeat
                    add(more.songs)
                    cont = more.continuation
                }
                if (id == "LM") Playback.markLiked(songs.map { it.id })
                Detail(title, author, pl.playlist.thumbnail, songs, emptyList())
            }
            else -> {
                val a = YouTube.artist(p.id).getOrThrow()
                val songs = a.sections.flatMap { it.items }.filterIsInstance<SongItem>().map { r.song(it) }
                Store.remember(songs)
                val items = a.sections.flatMap { it.items }.filter { it !is SongItem }.mapNotNull { r.item(it) }
                Detail(r.text(a.artist.title, artistId = a.artist.id), a.monthlyListenerCount ?: a.subscriberCountText ?: "", a.artist.thumbnail, songs, items)
            }
        }
    }

    override suspend fun radio(seed: Song, from: String?): SongPage? {
        val endpoint = WatchEndpoint(videoId = seed.id, playlistId = "RDAMVM${seed.id}")
        val page = YouTube.next(endpoint, from).getOrNull() ?: return null
        return SongPage(page.items.map { Store.romanizer.song(it) }, page.continuation)
    }

    override suspend fun relatedArtists(seed: Song): Set<String> {
        val id = seed.firstArtistId ?: return emptySet()
        val page = YouTube.artist(id).getOrNull() ?: return emptySet()
        return page.sections.flatMap { it.items }.filterIsInstance<ArtistItem>().map { it.id }.toSet()
    }

    override suspend fun playlist(id: String, from: String?): SongPage? {
        if (from != null) return YouTube.playlistContinuation(from).getOrNull()?.let { p -> SongPage(p.songs.map { Store.romanizer.song(it) }, p.continuation) }
        val page = YouTube.playlist(id.removePrefix("VL")).getOrNull() ?: return null
        return SongPage(page.songs.map { Store.romanizer.song(it) }, page.songsContinuation)
    }
    override val likedId = "LM"

    override val canLike get() = Store.signedIn
    override fun like(id: String, like: Boolean) = kotlinx.coroutines.runBlocking { YouTube.likeVideo(id, like).isSuccess }
    override fun liked(id: String) = kotlinx.coroutines.runBlocking { YouTube.likeStatus(id).getOrNull() }

    // An album is saved through its audio playlist, an artist by subscribing: the page says which and its state.
    private val libraryTargets = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun saved(p: Page) = if (!Store.signedIn || p.kind !in setOf("album", "artist")) null else
        kotlinx.coroutines.runBlocking { YouTube.libraryState(p.id).getOrNull() }?.let { (saved, target) -> libraryTargets[p.id] = target; InLibrary(saved) }
    override fun save(p: Page, on: Boolean) = libraryTargets[p.id]?.let { kotlinx.coroutines.runBlocking { YouTube.saveToLibrary(it, on).isSuccess } } ?: false

    override fun streamUrl(id: String, full: Boolean): String {
        kotlinx.coroutines.runBlocking { Store.ensureVisitorData() } // without it YouTube refuses the direct path's client as a bot
        return Streams.audio(id, full = full).url
    }
    // YouTube's loudness is relative to its reference level.
    override fun loudness(id: String) = Streams.loudness(id)

    override val lyrics: LyricsProvider = YouTubeLyrics
    override val hasComments = true

    override fun shareUrl(s: Song) = link(s.id)

    // The same video ID plays on both: on YouTube a song is its "Topic" upload.
    fun link(videoId: String) = if (Store.str("share.site", "music") == "music") "https://music.youtube.com/watch?v=$videoId" else "https://www.youtube.com/watch?v=$videoId"

    // YouTube Music never hears of plays from roma (not even its history); Last.fm does.
    override val lastFm = true
    override fun nowPlaying(s: Song) {}
    override fun played(s: roma.core.Scrobble) {}
}
