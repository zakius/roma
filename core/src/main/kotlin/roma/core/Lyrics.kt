package roma.core

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import kotlin.math.abs

@Serializable
class Word(val startMs: Long, val endMs: Long, val text: String)

/** One lyric line. latin and wordLatin are filled by [romanizeLyrics], wordLatin has one entry per word. */
@Serializable
class Line(
    val startMs: Long,
    val text: String,
    val words: List<Word> = emptyList(),
    val translation: String? = null,
    val latin: String = "",
    val wordLatin: List<String> = emptyList(),
)

@Serializable
class Lyrics(val source: String, val lines: List<Line>, val synced: Boolean) {
    val wordLevel get() = lines.any { it.words.isNotEmpty() }
}

/** What providers search with. Always the original (not romanized) metadata, so it matches their catalogues. */
class LyricsQuery(val videoId: String, val title: String, val artist: String, val album: String?, val durationSec: Int)

interface LyricsProvider {
    val id: String
    val name: String

    /** Blocking. Null when the provider has nothing. */
    fun fetch(q: LyricsQuery): Lyrics?
}

object LyricsProviders {
    val all: List<LyricsProvider> = listOf(NetEase, KuGou, LrcLib, YouTubeLyrics)

    /**
     * Asks the providers in order. Word timing beats line timing beats plain text: the first word-level result
     * wins at once, otherwise the first synced one, otherwise the first plain one.
     */
    fun find(q: LyricsQuery, providers: List<LyricsProvider>): Lyrics? {
        val found = ArrayList<Lyrics>()
        for (p in providers) {
            val l = runCatching { p.fetch(q) }.getOrNull() ?: continue
            if (l.lines.isEmpty()) continue
            if (l.wordLevel) return l
            found.add(l)
        }
        return found.firstOrNull { it.synced } ?: found.firstOrNull()
    }
}

fun Romanizer.romanizeLyrics(l: Lyrics, siblings: List<String>, songId: String): Lyrics = Lyrics(
    l.source,
    l.lines.map { line ->
        val wordLatin = if (line.words.isEmpty()) emptyList() else parts(line.words.map { it.text }, siblings, songId)
        val latin = if (line.words.isEmpty()) text(line.text, siblings, songId).latin else wordLatin.joinToString("").trim()
        Line(line.startMs, line.text, line.words, line.translation, latin, wordLatin)
    },
    l.synced,
)

// ---- Parsers ----

private val TIME = Regex("""\[(\d+):(\d+(?:[.:]\d+)?)]""")
private val WORD_TIME = Regex("""<(\d+):(\d+(?:[.:]\d+)?)>""")

private fun ms(min: String, sec: String): Long {
    val s = sec.replace(':', '.')
    return min.toLong() * 60_000 + (s.toDouble() * 1000).toLong()
}

/** LRC, including the enhanced form with per-word <mm:ss.xx> stamps. */
fun parseLrc(text: String): List<Line> {
    val lines = ArrayList<Line>()
    for (raw in text.lines()) {
        var rest = raw.trim()
        val stamps = ArrayList<Long>()
        while (true) {
            val m = TIME.matchAt(rest, 0) ?: break
            stamps.add(ms(m.groupValues[1], m.groupValues[2]))
            rest = rest.substring(m.range.last + 1)
        }
        if (stamps.isEmpty()) continue // metadata such as [ar:...] or plain text
        val words = ArrayList<Word>()
        val marks = WORD_TIME.findAll(rest).toList()
        if (marks.isNotEmpty()) {
            for ((i, m) in marks.withIndex()) {
                val end = marks.getOrNull(i + 1)?.range?.first ?: rest.length
                val w = rest.substring(m.range.last + 1, end)
                val start = ms(m.groupValues[1], m.groupValues[2])
                if (words.isNotEmpty()) words[words.size - 1] = Word(words.last().startMs, start, words.last().text)
                if (w.isNotEmpty()) words.add(Word(start, -1, w))
            }
        }
        val plain = if (marks.isEmpty()) rest else words.joinToString("") { it.text }
        for (s in stamps) lines.add(Line(s, plain.trim(), words))
    }
    lines.sortBy { it.startMs }
    return closeWords(lines)
}

/** KuGou KRC: [lineStart,lineDur]<offset,dur,0>word, offsets relative to the line. */
fun parseKrc(text: String): List<Line> {
    val head = Regex("""^\[(\d+),(\d+)]""")
    val word = Regex("""<(\d+),(\d+),\d+>([^<]*)""")
    val translations = krcTranslations(text)
    val lines = ArrayList<Line>()
    for (raw in text.lines()) {
        val h = head.find(raw) ?: continue
        val start = h.groupValues[1].toLong()
        val words = word.findAll(raw.substring(h.range.last + 1)).map {
            val off = start + it.groupValues[1].toLong()
            Word(off, off + it.groupValues[2].toLong(), it.groupValues[3])
        }.filter { it.text.isNotEmpty() }.toList()
        lines.add(Line(start, words.joinToString("") { it.text }.trim(), words, translations.getOrNull(lines.size)))
    }
    return lines
}

private fun krcTranslations(text: String): List<String?> {
    val tag = Regex("""\[language:([^\]]*)]""").find(text) ?: return emptyList()
    return runCatching {
        val json = Json.parseToJsonElement(String(Base64.getDecoder().decode(tag.groupValues[1]))).jsonObject
        val content = json["content"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["type"]?.jsonPrimitive?.content == "1" }
        content!!["lyricContent"]!!.jsonArray.map { row -> row.jsonArray.joinToString("") { it.jsonPrimitive.content }.ifBlank { null } }
    }.getOrDefault(emptyList())
}

/** NetEase YRC: [lineStart,lineDur](wordStart,dur,0)word, word starts absolute. */
fun parseYrc(text: String): List<Line> {
    val head = Regex("""^\[(\d+),(\d+)]""")
    val word = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")
    val lines = ArrayList<Line>()
    for (raw in text.lines()) {
        val h = head.find(raw) ?: continue
        val words = word.findAll(raw.substring(h.range.last + 1)).map {
            val s = it.groupValues[1].toLong()
            Word(s, s + it.groupValues[2].toLong(), it.groupValues[3])
        }.filter { it.text.isNotEmpty() }.toList()
        lines.add(Line(h.groupValues[1].toLong(), words.joinToString("") { it.text }.trim(), words))
    }
    return lines
}

// A word without an end ends where the next line starts.
private fun closeWords(lines: List<Line>): List<Line> = lines.mapIndexed { i, l ->
    if (l.words.none { it.endMs < 0 }) l
    else {
        val nextStart = lines.getOrNull(i + 1)?.startMs ?: (l.words.last().startMs + 1000)
        Line(l.startMs, l.text, l.words.map { if (it.endMs < 0) Word(it.startMs, nextStart, it.text) else it }, l.translation)
    }
}

/** Adds translations from a second LRC with the same timestamps. */
fun withTranslation(lines: List<Line>, translationLrc: String?): List<Line> {
    if (translationLrc.isNullOrBlank()) return lines
    val byTime = parseLrc(translationLrc).filter { it.text.isNotBlank() }.associate { it.startMs to it.text }
    return lines.map { l ->
        val t = byTime[l.startMs] ?: byTime.entries.firstOrNull { abs(it.key - l.startMs) < 300 }?.value
        Line(l.startMs, l.text, l.words, t ?: l.translation)
    }
}

// ---- Providers ----

private val http = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()

private fun get(url: String, vararg headers: Pair<String, String>): String? {
    val b = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 roma")
    for ((k, v) in headers) b.header(k, v)
    http.newCall(b.build()).execute().use { r -> return if (r.isSuccessful) r.body.string() else null }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

private fun foldTitle(s: String) = Romanizer.stripMarks(s).lowercase().replace(Regex("""[(\[（【].*?[)\]）】]"""), "").replace(Regex("""[\s\p{P}]+"""), "")

/** Same song title, ignoring case, spaces, punctuation and bracketed extras such as "(TV Size)". */
fun sameTitle(a: String, b: String): Boolean {
    val x = foldTitle(a)
    val y = foldTitle(b)
    return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x))
}

/** Picks the candidate whose duration is closest, within 5 s when the song's duration is known. */
private fun <T> closest(items: List<T>, durationSec: Int, durationOf: (T) -> Double): T? {
    if (durationSec <= 0) return items.firstOrNull()
    return items.filter { abs(durationOf(it) - durationSec) <= 5 }.minByOrNull { abs(durationOf(it) - durationSec) }
}

/** LRCLIB: free, no key. Line-synced LRC, sometimes enhanced with word stamps. */
object LrcLib : LyricsProvider {
    override val id = "lrclib"
    override val name = "LRCLIB"

    override fun fetch(q: LyricsQuery): Lyrics? {
        val body = get("https://lrclib.net/api/search?track_name=${enc(q.title)}&artist_name=${enc(q.artist)}") ?: return null
        val items0 = Json.parseToJsonElement(body).arr()?.mapNotNull { it.obj() } ?: return null
        val items = items0.filter { sameTitle(it["trackName"].str() ?: "", q.title) }
        val withSynced = items.filter { !it["syncedLyrics"].str().isNullOrBlank() }
        val pick = closest(withSynced, q.durationSec) { it["duration"].str()?.toDoubleOrNull() ?: 0.0 }
        if (pick != null) return Lyrics(name, parseLrc(pick["syncedLyrics"].str()!!), true)
        val plain = closest(items.filter { !it["plainLyrics"].str().isNullOrBlank() }, q.durationSec) { it["duration"].str()?.toDoubleOrNull() ?: 0.0 }
            ?: return null
        return Lyrics(name, plain["plainLyrics"].str()!!.lines().map { Line(0, it) }, false)
    }
}

/** NetEase Cloud Music: word timing (YRC) for much C/J-pop, line LRC and Chinese translations otherwise. Unofficial API. */
object NetEase : LyricsProvider {
    override val id = "netease"
    override val name = "NetEase"
    private val referer = "Referer" to "https://music.163.com/"

    override fun fetch(q: LyricsQuery): Lyrics? {
        val body = get("https://music.163.com/api/cloudsearch/pc?s=${enc(q.title + " " + q.artist)}&type=1&limit=10", referer) ?: return null
        val songs = Json.parseToJsonElement(body).obj()?.get("result").obj()?.get("songs").arr()?.mapNotNull { it.obj() } ?: return null
        val song = closest(songs.filter { sameTitle(it["name"].str() ?: "", q.title) }, q.durationSec) { (it["dt"].str()?.toDoubleOrNull() ?: 0.0) / 1000 } ?: return null
        val id = song["id"].str() ?: return null
        val lyr = Json.parseToJsonElement(get("https://music.163.com/api/song/lyric?id=$id&lv=-1&tv=-1&yv=-1", referer) ?: return null).obj() ?: return null
        val translation = lyr["tlyric"].obj()?.get("lyric").str()
        val yrc = lyr["yrc"].obj()?.get("lyric").str()
        if (!yrc.isNullOrBlank()) {
            val lines = parseYrc(yrc)
            if (lines.isNotEmpty()) return Lyrics(name, withTranslation(lines, translation), true)
        }
        val lrc = lyr["lrc"].obj()?.get("lyric").str() ?: return null
        val lines = parseLrc(lrc).filter { !it.text.contains(" : ") && !it.text.contains("：") } // credits like "作词 : X"
        return if (lines.isEmpty()) null else Lyrics(name, withTranslation(lines, translation), true)
    }
}

/** KuGou: KRC word timing, encrypted with a well-known key. Unofficial API. */
object KuGou : LyricsProvider {
    override val id = "kugou"
    override val name = "KuGou"
    private val key = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)

    override fun fetch(q: LyricsQuery): Lyrics? {
        val keyword = enc("${q.artist} - ${q.title}")
        val search = get("https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=$keyword") ?: return null
        val songs = Json.parseToJsonElement(search).obj()?.get("data").obj()?.get("info").arr()?.mapNotNull { it.obj() } ?: return null
        val song = closest(songs.filter { sameTitle(it["songname"].str() ?: "", q.title) }, q.durationSec) { it["duration"].str()?.toDoubleOrNull() ?: 0.0 } ?: return null
        val hash = song["hash"].str() ?: return null
        val cands = get("https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=$keyword&duration=${q.durationSec * 1000}&hash=$hash")
            ?: return null
        val cand = Json.parseToJsonElement(cands).obj()?.get("candidates").arr()?.firstOrNull().obj() ?: return null
        val dl = get("https://krcs.kugou.com/download?ver=1&man=yes&client=pc&fmt=krc&charset=utf8&id=${cand["id"].str()}&accesskey=${cand["accesskey"].str()}")
            ?: return null
        val content = Json.parseToJsonElement(dl).obj()?.get("content").str() ?: return null
        val lines = parseKrc(decrypt(content))
        return if (lines.isEmpty()) null else Lyrics(name, lines, true)
    }

    fun decrypt(base64: String): String {
        val raw = Base64.getDecoder().decode(base64)
        val data = ByteArray(raw.size - 4) { i -> (raw[i + 4].toInt() xor key[i % key.size]).toByte() }
        val inflater = Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8).removePrefix("﻿")
    }
}

/** YouTube Music's own lyrics: plain text, no timing. */
object YouTubeLyrics : LyricsProvider {
    override val id = "youtube"
    override val name = "YouTube Music"

    override fun fetch(q: LyricsQuery): Lyrics? = runBlocking {
        val next = YouTube.next(WatchEndpoint(videoId = q.videoId)).getOrNull() ?: return@runBlocking null
        val endpoint = next.lyricsEndpoint ?: return@runBlocking null
        val text = YouTube.lyrics(endpoint).getOrNull() ?: return@runBlocking null
        Lyrics(name, text.lines().map { Line(0, it) }, false)
    }
}
