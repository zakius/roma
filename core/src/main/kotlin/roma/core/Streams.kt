package roma.core

import com.metrolist.innertube.YouTube
import com.metrolist.innertubex.models.YouTubeClient.Companion.WEB
import com.metrolist.innertubex.models.YouTubeClient.Companion.WEB_REMIX
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
import java.util.concurrent.TimeUnit

/** A playable audio URL for one video, picked by NewPipeExtractor. */
class AudioSource(val url: String, val mimeType: String, val bitrate: Int)

object Streams {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var started = false

    @Synchronized
    private fun start() {
        if (started) return
        NewPipe.init(OkHttpDownloader(http))
        started = true
    }

    /**
     * Blocking: call off the main thread. The fast path asks NewPipe's stream extractor for the audio streams only,
     * and the downloader answers the requests whose results roma doesn't use itself (see [stub]): 5 of 7 requests,
     * about 1.2 s. If that fails, or [full] is set (the fast URL was refused), the full StreamInfo lookup runs.
     */
    fun audio(videoId: String, full: Boolean = false): AudioSource {
        start()
        val url = "https://music.youtube.com/watch?v=$videoId"
        val streams = (if (full) null else try {
            lean.set(true)
            val e = ServiceList.YouTube.getStreamExtractor(url)
            e.fetchPage()
            e.audioStreams.filter { it.content.isNotEmpty() }.ifEmpty { null }
        } catch (_: Exception) {
            visitor = null // perhaps refused; the next fast path asks for fresh visitor data
            null
        } finally {
            lean.set(false)
        }) ?: StreamInfo.getInfo(ServiceList.YouTube, url).audioStreams.filter { it.content.isNotEmpty() }
        // Prefer the original track over dubbed ones, then the highest bitrate.
        val best = streams
            .sortedWith(compareBy<AudioStream>({ it.audioTrackType != null && it.audioTrackType?.name != "ORIGINAL" }, { -it.averageBitrate }))
            .firstOrNull() ?: error("No audio stream for $videoId")
        return AudioSource(best.content, best.format?.mimeType ?: "audio/*", best.averageBitrate)
    }

    /** YouTube's loudness of the track against its reference level, in dB; positive means louder. Best effort, blocking. */
    fun loudness(videoId: String): Float? = runBlocking {
        YouTube.player(videoId, client = WEB_REMIX).getOrNull()?.playerConfig?.audioConfig?.loudnessDb?.toFloat()
    }
}

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
            if (r.isSuccessful && request.url().contains("/visitor_id") && request.dataToSend()?.let { String(it) }?.contains(""""clientName":"VISIONOS"""") == true) {
                visitorJson.find(text)?.let { visitor = it.groupValues[1] to System.currentTimeMillis() }
            }
            return Response(r.code, r.message, r.headers.toMultimap(), text, r.request.url.toString())
        }
    }
}
