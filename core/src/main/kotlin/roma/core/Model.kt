package roma.core

import com.metrolist.innertube.models.SongItem
import kotlinx.serialization.Serializable

@Serializable
class Named(val id: String?, val name: Text)

/** A song with every name already romanized. Built once, when the song enters the app. */
@Serializable
class Song(
    val id: String,
    val title: Text,
    val artists: List<Named>,
    val album: Named? = null,
    val durationSec: Int = 0,
    val thumb: String? = null,
    /** A music video rather than an audio track. */
    val video: Boolean = false,
    /** Why radio picked it, if known ("same artist"). */
    val reason: String? = null,
) {
    val artistLatin get() = artists.joinToString(", ") { it.name.latin }
    val artistOriginal get() = artists.joinToString(", ") { it.name.original }
    val firstArtistId get() = artists.firstOrNull { it.id != null }?.id

    fun withReason(r: String?) = Song(id, title, artists, album, durationSec, thumb, video, r)
}

fun Romanizer.song(item: SongItem): Song {
    val artistNames = item.artists.map { it.name }
    val albumName = item.album?.name
    val artistId = item.artists.firstOrNull { it.id != null }?.id
    val title = text(item.title, artistNames + listOfNotNull(albumName), item.id, artistId)
    val artists = item.artists.map { a -> Named(a.id, text(a.name, listOf(item.title) + listOfNotNull(albumName), item.id, a.id)) }
    val album = item.album?.let { Named(it.id, text(it.name, listOf(item.title) + artistNames, item.id, artistId)) }
    return Song(item.id, title, artists, album, item.duration ?: 0, item.thumbnail, item.isVideoSong)
}

/** Redo the Latin forms, e.g. after an override or a scheme change. */
fun Romanizer.again(s: Song): Song {
    val artistNames = s.artists.map { it.name.original }
    val albumName = s.album?.name?.original
    val title = text(s.title.original, artistNames + listOfNotNull(albumName), s.id, s.firstArtistId)
    val artists = s.artists.map { a -> Named(a.id, text(a.name.original, listOf(s.title.original) + listOfNotNull(albumName), s.id, a.id)) }
    val album = s.album?.let { Named(it.id, text(it.name.original, listOf(s.title.original) + artistNames, s.id, s.firstArtistId)) }
    return Song(s.id, title, artists, album, s.durationSec, s.thumb, s.video, s.reason)
}

/** Local search: the query matches the original or the Latin form, ignoring case, accents and spaces. */
fun matches(song: Song, query: String): Boolean {
    val q = fold(query)
    if (q.isEmpty()) return false
    return listOf(song.title.original, song.title.latin, song.artistOriginal, song.artistLatin).any { fold(it).contains(q) }
}

private fun fold(s: String) = Romanizer.stripMarks(s).lowercase().replace(Regex("[\\s'’-]+"), "")

/** A card on home, search, library or artist pages: a song, album, playlist or artist. */
@Serializable
class Item(val kind: String, val id: String, val title: Text, val subtitle: Text, val thumb: String?, val song: Song? = null)

fun Romanizer.item(y: com.metrolist.innertube.models.YTItem): Item? = when (y) {
    is SongItem -> song(y).let { Item("song", it.id, it.title, Text(it.artistOriginal, it.artistLatin), it.thumb, it) }
    is com.metrolist.innertube.models.AlbumItem -> {
        val artists = y.artists.orEmpty().joinToString(", ") { it.name }
        Item("album", y.browseId, text(y.title, listOf(artists)), text(artists, listOf(y.title)), y.thumbnail)
    }
    is com.metrolist.innertube.models.PlaylistItem -> Item("playlist", y.id, text(y.title), text(y.author?.name ?: ""), y.thumbnail)
    is com.metrolist.innertube.models.ArtistItem -> Item("artist", y.id, text(y.title, artistId = y.id), Text("", ""), y.thumbnail)
    else -> null
}
