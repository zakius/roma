package roma.core

import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.models.YouTubeClient.Companion.WEB
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.util.concurrent.TimeUnit

/** A playable audio URL for one video. */
class AudioSource(val url: String, val mimeType: String, val bitrate: Int)

/** No path found a stream. The message is the reason in plain words, as YouTube or the extractor gave it. */
class StreamException(message: String) : java.io.IOException(message)

object Streams {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var started = false

    /**
     * Testing only, off in releases: acts as if YouTube refused the VISIONOS client, as it does for some songs on some
     * networks. Both NewPipe paths are skipped (they use VISIONOS) and InnerTubeX doesn't ask it either.
     */
    @Volatile var refuseVisionOs = false

    /** The path that last found a stream for each song, tried first next time. InnerTubeX ones are kept, see [keepIn]. */
    private val worked = java.util.concurrent.ConcurrentHashMap<String, Path>()

    private enum class Path { FAST, FULL, INNERTUBEX }

    /**
     * Songs every path refused just now, with the reason and when. The precache and the player both look a song up;
     * within [REFUSED_MS] the second one gets the same answer without asking YouTube again.
     */
    private val refused = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private const val REFUSED_MS = 30_000L
    const val NO_CONNECTION = "No connection to YouTube."

    @Synchronized
    private fun start() {
        if (started) return
        NewPipe.init(OkHttpDownloader(http))
        started = true
    }

    /**
     * Blocking: call off the main thread. Tries, in order: NewPipe's fast path (the stream extractor alone, with the
     * downloader answering the requests roma doesn't use itself, see [stub]; about 0.2 s), NewPipe's full StreamInfo
     * lookup, then InnerTubeX's extractor ([InnerTubeXStreams]), which asks other clients with their own context.
     * The path that worked for this song goes first next time. [full] (the last URL was refused) skips the fast path.
     */
    fun audio(videoId: String, full: Boolean = false): AudioSource {
        start()
        refused[videoId]?.let { (why, at) -> if (System.currentTimeMillis() - at < REFUSED_MS) throw StreamException(why) }
        val order = listOfNotNull(worked[videoId]) + Path.entries
        val failures = mutableListOf<Exception>()
        for (path in order.distinct()) {
            if (path == Path.FAST && full) continue
            if (path != Path.INNERTUBEX && refuseVisionOs) continue
            try {
                val a = when (path) {
                    Path.FAST -> fast(videoId)
                    Path.FULL -> best(videoId, StreamInfo.getInfo(ServiceList.YouTube, watchUrl(videoId)).audioStreams)
                    Path.INNERTUBEX -> InnerTubeXStreams.audio(videoId, if (refuseVisionOs) setOf("VISIONOS") else emptySet())
                }
                val before = worked.put(videoId, path)
                if (before != path && (path == Path.INNERTUBEX || before == Path.INNERTUBEX)) {
                    note(videoId, if (path == Path.INNERTUBEX) "X ${System.currentTimeMillis()}" else "N")
                }
                return a
            } catch (e: Exception) {
                if (path == Path.FAST) visitor = null // perhaps refused; the next fast path asks for fresh visitor data
                failures += e
            }
        }
        worked.remove(videoId)
        val why = reason(failures)
        if (why != NO_CONNECTION) refused[videoId] = why to System.currentTimeMillis() // offline: Retry asks again
        throw StreamException(why)
    }

    private fun watchUrl(videoId: String) = "https://music.youtube.com/watch?v=$videoId"

    private fun fast(videoId: String): AudioSource = try {
        lean.set(true)
        val e = ServiceList.YouTube.getStreamExtractor(watchUrl(videoId))
        e.fetchPage()
        best(videoId, e.audioStreams)
    } finally {
        lean.set(false)
    }

    // Prefer the original track over dubbed ones, then the highest bitrate.
    private fun best(videoId: String, streams: List<AudioStream>): AudioSource {
        val best = streams.filter { it.content.isNotEmpty() }
            .sortedWith(compareBy<AudioStream>({ it.audioTrackType != null && it.audioTrackType?.name != "ORIGINAL" }, { -it.averageBitrate }))
            .firstOrNull() ?: error("No audio stream for $videoId")
        return AudioSource(best.content, best.format?.mimeType ?: "audio/*", best.averageBitrate)
    }

    /**
     * The reason to show when every path failed: the last one that says why (YouTube's playability text, an age
     * check), over generic ones ("no playable stream"); a network failure in plain words.
     */
    internal fun reason(failures: List<Exception>): String {
        fun plain(e: Throwable): String? = when {
            e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException -> NO_CONNECTION
            e is StreamResolveException && e.reason in setOf(StreamResolveException.Reason.NO_PLAYABLE_STREAM, StreamResolveException.Reason.UNKNOWN) -> null
            e is StreamResolveException && e.reason == StreamResolveException.Reason.NETWORK -> e.cause?.let(::plain) ?: NO_CONNECTION
            e is IllegalStateException && e.message?.startsWith("No audio stream") == true -> null
            else -> e.message?.trim()?.takeIf { it.isNotEmpty() }
        }
        return failures.asReversed().firstNotNullOfOrNull(::plain) ?: "YouTube gave no playable stream for this song."
    }

    /**
     * YouTube's loudness of the track against its reference level, in dB; positive means louder. Read from the player
     * response of the last [audio] lookup of this video, so it costs no request of its own; null if it had none.
     */
    fun loudness(videoId: String): Float? = loudnessDb[videoId]

    internal fun putLoudness(videoId: String, db: Float) {
        if (loudnessDb.put(videoId, db) != db) note(videoId, "L $db")
    }

    private var file: File? = null

    /**
     * Keeps two facts per song across starts, one line per change in [f]: its loudness, and that only InnerTubeX found
     * its stream. A song played to the end replays from the cache without a lookup, so without the first it would play
     * at full loudness after a restart; without the second, the next start would first wait for the NewPipe paths
     * YouTube refused (a second or more). An InnerTubeX mark lasts [FALLBACK_DAYS] days, so a song YouTube serves
     * again gets the fast path back. Blocking: reads the file (at most about 150 KB) and compacts it.
     */
    fun keepIn(f: File) {
        val loud = LinkedHashMap<String, Float>()
        val fallback = HashMap<String, Long>()
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
        for (line in lines) {
            val p = line.split(' ')
            when (p.getOrNull(1)) {
                "L" -> p.getOrNull(2)?.toFloatOrNull()?.let { loud.remove(p[0]); loud[p[0]] = it }
                "X" -> p.getOrNull(2)?.toLongOrNull()?.let { fallback[p[0]] = it }
                "N" -> fallback.remove(p[0])
            }
        }
        val since = System.currentTimeMillis() - FALLBACK_DAYS * 86_400_000L
        fallback.values.removeAll { it < since }
        val keep = loud.entries.toList().takeLast(5000)
        for ((id, db) in keep) loudnessDb.putIfAbsent(id, db)
        for ((id, at) in fallback) { worked.putIfAbsent(id, Path.INNERTUBEX); fallbackAt[id] = at }
        if (lines.size > keep.size + fallback.size + 500) {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(keep.joinToString("") { (id, db) -> "$id L $db\n" } + fallback.entries.joinToString("") { (id, at) -> "$id X $at\n" })
            tmp.renameTo(f)
        }
        file = f
    }

    private val fallbackAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val FALLBACK_DAYS = 7

    /** A song that only InnerTubeX could play in the last [FALLBACK_DAYS] days, the latest; null if none. */
    fun recentFallback(): String? = fallbackAt.entries.maxByOrNull { it.value }?.key

    private fun note(videoId: String, what: String) {
        if (what.startsWith("X")) fallbackAt[videoId] = System.currentTimeMillis()
        if (what == "N") fallbackAt.remove(videoId)
        val f = file ?: return
        synchronized(this) { runCatching { f.appendText("$videoId $what\n") } }
    }
}

private val loudnessDb = java.util.concurrent.ConcurrentHashMap<String, Float>()
private val loudnessJson = Regex(""""loudnessDb":(-?[\d.]+)""")

/** Set while [Streams.audio] takes its fast path, on that thread. */
private val lean = ThreadLocal.withInitial { false }

/**
 * The VISIONOS client's visitor data from its last real visitor_id answer, and when it came. NewPipe asks for new
 * visitor data before every player request; the fast path reuses it for an hour, which saves a round trip per song.
 */
@Volatile private var visitor: Pair<String, Long>? = null
private const val VISITOR_MS = 3600_000L
private val visitorJson = Regex(""""visitorData":"([^"]+)"""")

/**
 * The fast path's local answers. NewPipe 0.26.5 takes its streams from the VISIONOS client, which also checks
 * playability. It asks the ANDROID client too (its streams need a PoToken we don't send, so they go unused) and
 * the WEB client for metadata and `next`. A minimal answer leaves those fields blank; NewPipe rejects a JSON
 * answer shorter than 50 characters.
 */
private fun stub(request: Request): String? {
    // NewPipe reads the WEB client's version from sw.js before building a WEB request (and when that fails, from a
    // 255 KB search page); the WEB requests are answered here anyway, so InnerTubeX's WEB version does.
    if (request.url().startsWith("https://www.youtube.com/sw.js")) return """"INNERTUBE_CONTEXT_CLIENT_VERSION":"${WEB.clientVersion}""""
    val body = request.dataToSend()?.let { String(it) } ?: return null
    val client = Regex(""""clientName":"(\w+)"""").find(body)?.groupValues?.get(1)
    val videoId = Regex(""""videoId":"([\w-]+)"""").find(body)?.groupValues?.get(1)
    val v = visitor
    return when {
        client == "VISIONOS" && request.url().contains("/visitor_id") && v != null && System.currentTimeMillis() - v.second < VISITOR_MS ->
            """{"responseContext": {"visitorData": "${v.first}"}}"""
        client == "WEB" -> """{"responseContext": {}, "roma": "WEB metadata and next are not used"}"""
        client == "ANDROID" && request.url().contains("/visitor_id") -> """{"responseContext": {"visitorData": "roma-skipped-android-client"}}"""
        client == "ANDROID" && videoId != null ->
            """{"playerResponse": {"playabilityStatus": {"status": "OK"}, "videoDetails": {"videoId": "$videoId"}}}"""
        else -> null
    }
}

private class OkHttpDownloader(private val http: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        if (lean.get()) stub(request)?.let { return Response(200, "OK", emptyMap(), it, request.url()) }
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body)
        builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (v in values) builder.addHeader(name, v)
        }
        http.newCall(builder.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            val text = r.body.string()
            if (r.isSuccessful && request.url().contains("/player")) {
                val id = request.dataToSend()?.let { Regex(""""videoId":"([\w-]+)""").find(String(it)) }?.groupValues?.get(1)
                val db = loudnessJson.find(text)?.groupValues?.get(1)?.toFloatOrNull()
                if (id != null && db != null) Streams.putLoudness(id, db)
            }
            if (r.isSuccessful && request.url().contains("/visitor_id") && request.dataToSend()?.let { String(it) }?.contains(""""clientName":"VISIONOS"""") == true) {
                visitorJson.find(text)?.let { visitor = it.groupValues[1] to System.currentTimeMillis() }
            }
            return Response(r.code, r.message, r.headers.toMultimap(), text, r.request.url.toString())
        }
    }
}

/**
 * The fallback: InnerTubeX's extractor, as Metrolist uses it. It rolls over its own client list (VISIONOS 0.1 with
 * visitor data from a watch page, the embedded web player, others) and solves signatures with QuackJS and the
 * cipher hints from [CIPHER_CONFIG]. Account cookies ([com.metrolist.innertube.YouTube.cookie]) go with clients
 * that accept them; signed in, it retries age-restricted songs with an authenticated watch page. Clients that need
 * a PoToken (the music web client WEB_REMIX, the only one that plays age-restricted songs for an account without
 * Premium) are used only with a [tokenProvider]: without a token their streams stop after about 1 MB.
 */
object InnerTubeXStreams {
    private const val CIPHER_CONFIG = "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"

    /** Hands PoTokens to the clients that need one (a WebView BotGuard generator in the app); none by default. */
    @Volatile var tokenProvider: TokenProvider? = null
        set(value) { field = value; bundle = null }

    private class Bundle(val generation: Long, val extractor: InnerTubeExtractor)
    @Volatile private var bundle: Bundle? = null

    // The cipher config, kept for the process; RemotePlayerConfigStore refreshes it when a player hash is missing.
    private val configRepository = object : PlayerConfigRepository {
        override val enabled = true
        override val sourceUrl = CIPHER_CONFIG
        override val defaultSourceUrl = CIPHER_CONFIG
        override var cachedJson = ""
        override var cachedAtMs = 0L
        override var cachedSourceUrl = ""
        override var cachedEtag = ""
    }

    // Rebuilt when innertube recreates its HTTP client (a proxy change), as Metrolist does.
    @Synchronized
    private fun extractor(): InnerTubeExtractor {
        val t = com.metrolist.innertube.YouTube.extractionTransport()
        bundle?.takeIf { it.generation == t.generation }?.let { return it.extractor }
        val store = RemotePlayerConfigStore(t.httpClient, configRepository, InnerTubeLogger.NONE)
        val cipher = YouTubeCipherService(t.httpClient, store, InnerTubeLogger.NONE)
        val parser = YtConfigParserImpl(t.httpClient, t.innerTube, store, InnerTubeLogger.NONE, cipher)
        return InnerTubeExtractor(parser, cipher, t.innerTube, tokenProvider = tokenProvider).also { bundle = Bundle(t.generation, it) }
    }

    /** Blocking. Throws [StreamResolveException] with a plain reason ("This track is age-restricted.") on refusal. */
    fun audio(videoId: String, excludedClients: Set<String> = emptySet()): AudioSource = runBlocking {
        val hints = ContentHints().withStreamCapabilities(false, false, true) // direct URLs only: no HLS, no SABR
        val s = extractor().extract(videoId, hints, excludedClients, AudioQuality.HIGH, generateClientPlaybackNonce())
            ?: throw StreamResolveException(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "No playable stream found for this track.", null, null)
        println("roma streams: $videoId via InnerTubeX ${s.profileId ?: s.clientName}") // which client, for logcat; no tokens
        s.loudnessDb?.let { Streams.putLoudness(videoId, it.toFloat()) }
        AudioSource(s.audioUrl, s.mimeType ?: "audio/*", s.bitrate ?: 0)
    }
}
