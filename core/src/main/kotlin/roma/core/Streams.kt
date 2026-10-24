package roma.core

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
class AudioSource(val url: String, val mimeType: String, val bitrate: Int, val loudnessDb: Float?)

object Streams {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var started = false

    @Synchronized
    private fun start() {
        if (started) return
        NewPipe.init(OkHttpDownloader(http))
        started = true
    }

    /** Blocking: call off the main thread. */
    fun audio(videoId: String): AudioSource {
        start()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://music.youtube.com/watch?v=$videoId")
        val streams: List<AudioStream> = info.audioStreams.filter { it.content.isNotEmpty() }
        // Prefer the original track over dubbed ones, then the highest bitrate.
        val best = streams
            .sortedWith(compareBy<AudioStream>({ it.audioTrackType != null && it.audioTrackType?.name != "ORIGINAL" }, { -it.averageBitrate }))
            .firstOrNull() ?: error("No audio stream for $videoId")
        return AudioSource(best.content, best.format?.mimeType ?: "audio/*", best.averageBitrate, null)
    }
}

private class OkHttpDownloader(private val http: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body)
        builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (v in values) builder.addHeader(name, v)
        }
        http.newCall(builder.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body.string(), r.request.url.toString())
        }
    }
}
