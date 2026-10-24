package roma.core

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI

// Step 0 check against the live services: ./gradlew :core:networkCheck
// Searches YouTube Music through innertube, resolves the first song through NewPipeExtractor and reads 64 KB of audio.
fun main() = runBlocking {
    val result = YouTube.search("YOASOBI 夜に駆ける", YouTube.SearchFilter.FILTER_SONG).getOrThrow()
    val song = result.items.first() as SongItem
    println("innertube: ${song.id} ${song.title} / ${song.artists.joinToString { it.name }}")
    val audio = Streams.audio(song.id)
    println("newpipe: ${audio.mimeType} ${audio.bitrate} bps")
    val c = URI(audio.url).toURL().openConnection() as HttpURLConnection
    c.setRequestProperty("Range", "bytes=0-65535")
    val n = c.inputStream.use { it.readAllBytes().size }
    println("stream: HTTP ${c.responseCode}, read $n bytes")
    check(n > 1000)

    // Lyrics providers, each on its own, with the song's original metadata.
    val q = LyricsQuery(song.id, song.title, song.artists.joinToString { it.name }, song.album?.name, song.duration ?: 0)
    for (p in LyricsProviders.all) {
        val l = runCatching { p.fetch(q) }.onFailure { println("lyrics ${p.name}: ${it.message}") }.getOrNull()
        println("lyrics ${p.name}: " + if (l == null) "none" else "${l.lines.size} lines, synced=${l.synced}, word=${l.wordLevel}, first=${l.lines.firstOrNull { it.text.isNotBlank() }?.text}")
    }
}
