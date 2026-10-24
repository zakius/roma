package roma.core

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI

// Step 0 check against the live services: ./gradlew :core:networkCheck
// Searches YouTube Music through innertube, resolves the first song through NewPipeExtractor and reads 64 KB of audio,
// then resolves it again through the InnerTubeX fallback alone.
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

    // The fallback alone (as when YouTube refuses NewPipe's client): the same song through InnerTubeX, read at the
    // start and past 1 MB, where a stream that needs a PoToken is cut off. Then the age-restricted case, for its reason.
    Streams.skipNewPipe = true
    val t0 = System.currentTimeMillis()
    val fb = Streams.audio(song.id)
    println("fallback: ${fb.mimeType} ${fb.bitrate} bps in ${System.currentTimeMillis() - t0} ms")
    for (range in listOf("0-65535", "1500000-1565535")) {
        val f = URI(fb.url).toURL().openConnection() as HttpURLConnection
        f.setRequestProperty("Range", "bytes=$range")
        val got = f.inputStream.use { it.readAllBytes().size }
        println("fallback stream $range: HTTP ${f.responseCode}, read $got bytes")
        check(got > 1000)
    }
    println("fallback, age-restricted x8VYWazR5mE: " + runCatching { Streams.audio("x8VYWazR5mE").mimeType }.getOrElse { "refused: ${it.message}" })
    Streams.skipNewPipe = false

    // Lyrics providers, each on its own, with the song's original metadata, then the rule that picks one.
    for (query in listOf("YOASOBI 夜に駆ける", "米津玄師 Lemon", "The Weeknd Blinding Lights", "周杰倫 晴天")) {
        val s = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.first() as SongItem
        val q = LyricsQuery(s.id, s.title, s.artists.joinToString { it.name }, s.album?.name, s.duration ?: 0)
        println("\n${s.id} ${s.title} / ${q.artist}")
        val ytm = runCatching { YouTubeLyrics.fetch(q) }.getOrNull()
        for (p in LyricsProviders.all) {
            val l = runCatching { p.fetch(q) }.onFailure { println("  ${p.name}: failed: ${it.message}") }.getOrNull()
            val sim = if (l != null && ytm != null && p != YouTubeLyrics) " sim=%.2f".format(similarity(l.lines.joinToString("\n") { it.text }, ytm.lines.joinToString("\n") { it.text }, jvmIcu)) else ""
            val tr = l?.lines?.mapNotNull { it.translation }?.let { translationLanguage(it) }
            println("  ${p.name}: " + if (l == null) "none" else "${l.source}, ${l.lines.size} lines, synced=${l.synced}, word=${l.wordLevel}$sim, translation=$tr, first=${l.lines.firstOrNull { it.text.isNotBlank() }?.text}")
        }
        val r = LyricsProviders.find(q, LyricsProviders.all, jvmIcu)
        println("  -> rule ${r.result?.rule}: ${r.result?.lyrics?.source}, complete=${r.complete}, translations=${r.result?.translations?.map { "${it.source}:${it.language}" }}")
    }
}
