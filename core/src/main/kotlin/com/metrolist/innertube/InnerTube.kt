package com.metrolist.innertube

import com.metrolist.innertube.models.MediaInfo
import com.metrolist.innertube.models.ReturnYouTubeDislikeResponse
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertube.models.response.NextResponse
import com.metrolist.innertubex.InnerTube as InnerTubeX
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.models.response.PlayerResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.cache.storage.FileStorage
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * Compatibility facade that keeps Metrolist's parsed response models while InnerTubeX owns
 * YouTube request construction, session handling, retries, and authenticated mutations.
 */
class InnerTube(
    client: HttpClient? = null,
) {
    // Built on first use, not here: Ktor and InnerTubeX take tens of ms to set up, and roma sets the session
    // on the main thread at start. Settings made before then wait in [pending] and are applied when it's built.
    private val givenClient = client
    @Volatile private var built: InnerTubeX? = null
    private var builtClient: HttpClient? = null
    private val pending = ArrayList<(InnerTubeX) -> Unit>()
    private var innerTubeX: InnerTubeX
        get() = built ?: build()
        set(value) { built = value }
    private var httpClient: HttpClient
        get() { innerTubeX; return builtClient!! }
        set(value) { builtClient = value }

    @Synchronized
    private fun build(): InnerTubeX {
        built?.let { return it }
        val c = givenClient ?: createClient()
        builtClient = c
        return InnerTubeX(c).also { x -> pending.forEach { it(x) }; pending.clear(); built = x }
    }

    @Synchronized
    private fun configure(f: (InnerTubeX) -> Unit) { built?.let(f) ?: pending.add(f) }

    class ExtractionTransport internal constructor(
        val innerTube: InnerTubeX,
        val httpClient: HttpClient,
    )

    var locale: YouTubeLocale
        get() = innerTubeX.locale
        set(value) {
            configure { it.locale = value }
        }

    var visitorData: String?
        get() = innerTubeX.visitorData
        set(value) {
            configure { it.visitorData = value }
        }

    var dataSyncId: String?
        get() = innerTubeX.dataSyncId
        set(value) {
            configure { it.dataSyncId = value }
        }

    var authUser: String
        get() = innerTubeX.authUser
        set(value) {
            configure { it.authUser = value }
        }

    var cookie: String?
        get() = innerTubeX.cookie
        set(value) {
            configure { it.cookie = value }
        }

    var useLoginForBrowse: Boolean
        get() = innerTubeX.useLoginForBrowse
        set(value) {
            configure { it.useLoginForBrowse = value }
        }

    @Synchronized
    fun extractionTransport(): ExtractionTransport =
        ExtractionTransport(
            innerTube = innerTubeX,
            httpClient = httpClient,
        )

    @OptIn(ExperimentalSerializationApi::class)
    private fun createClient() =
        HttpClient(Android) {
            // InnerTubeX handles endpoint-specific status validation and transient retries.
            expectSuccess = false

            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                )
            }

            install(ContentEncoding) {
                gzip(0.9F)
                deflate(0.8F)
            }

            // Android's HttpURLConnection: HTTP/1.1 only, which measured the same as OkHttp's HTTP/2 for Home's requests
            // and covers.
            engine {
                connectTimeout = 30_000
                socketTimeout = 60_000
            }

            // On disk, so a new process revalidates InnerTubeX's 3 MB player script instead of downloading it again.
            install(HttpCache) {
                val dir = FileStorage(kotlinx.io.files.Path(System.getProperty("java.io.tmpdir"), "ktor_cache"))
                publicStorage(dir)
                privateStorage(dir)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 60_000
            }

            defaultRequest {
                url("https://music.youtube.com/youtubei/v1/")
                header("Accept", "application/json")
                header("Cache-Control", "no-cache")
            }
        }

    suspend fun search(
        client: YouTubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
    ) = innerTubeX.search(client, query, params, continuation, setLogin = setLogin)

    suspend fun player(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String? = null,
    ) = innerTubeX.player(client, videoId, playlistId, signatureTimestamp, poToken)

    suspend fun registerPlayback(
        videoId: String,
        cpn: String,
        isHistoryEnabled: () -> Boolean = { true },
    ): Boolean {
        val transport = innerTubeX
        val session = transport.sessionSnapshot()
        if (session.cookie.isNullOrBlank() || !isHistoryEnabled()) return false
        // Streaming clients and cached downloads may not carry account-bound tracking URLs.
        val response = transport.playerWithSessionBound(
            client = YouTubeClient.WEB_REMIX,
            videoId = videoId,
            playlistId = null,
            signatureTimestamp = null,
            poToken = null,
            requestVisitorData = null,
            requestSession = session,
        ).requireSuccess("playbackTracking").body<PlayerResponse>()
        val trackingUrl = requireNotNull(response.playbackTracking?.videostatsPlaybackUrl?.baseUrl) {
            "No playback tracking URL returned"
        }
        require(Url(trackingUrl).encodedPath == "/api/stats/playback") { "Invalid playback tracking URL" }
        if (transport !== innerTubeX || transport.sessionSnapshot() != session || !isHistoryEnabled()) return false
        transport.registerPlaybackWithSession(
            client = YouTubeClient.WEB_REMIX,
            url = trackingUrl,
            cpn = cpn,
            playlistId = null,
            requestSession = session,
        ).requireSuccess("registerPlayback")
        return true
    }

    suspend fun browse(
        client: YouTubeClient,
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
    ) = innerTubeX.browse(client, browseId, params, continuation, setLogin)

    suspend fun next(
        client: YouTubeClient,
        videoId: String?,
        playlistId: String?,
        playlistSetVideoId: String?,
        index: Int?,
        params: String?,
        continuation: String? = null,
    ) = innerTubeX.next(client, videoId, playlistId, playlistSetVideoId, index, params, continuation)

    suspend fun feedback(
        client: YouTubeClient,
        tokens: List<String>,
    ) = innerTubeX.feedback(client, tokens).requireSuccess("feedback")

    suspend fun getSearchSuggestions(
        client: YouTubeClient,
        input: String,
    ) = innerTubeX.getSearchSuggestions(client, input, setLogin = false)

    suspend fun getQueue(
        client: YouTubeClient,
        videoIds: List<String>?,
        playlistId: String?,
    ) = innerTubeX.getQueue(client, videoIds, playlistId)

    suspend fun getTranscript(
        client: YouTubeClient,
        videoId: String,
    ) = innerTubeX.getTranscript(client, videoId)

    suspend fun fetchFreshVisitorData() = innerTubeX.fetchFreshVisitorData()

    suspend fun accountMenu(client: YouTubeClient) = innerTubeX.accountMenu(client).requireSuccess("accountMenu")

    suspend fun accountsList() = innerTubeX.accountsList(YouTubeClient.WEB).requireSuccess("accountsList")

    suspend fun likeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = innerTubeX.likeVideo(client, videoId).requireSuccess("likeVideo")

    suspend fun unlikeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = innerTubeX.unlikeVideo(client, videoId).requireSuccess("unlikeVideo")

    suspend fun subscribeChannel(
        client: YouTubeClient,
        channelId: String,
        params: String? = null,
    ) = innerTubeX.subscribeChannel(client, channelId, params).requireSuccess("subscribeChannel")

    suspend fun unsubscribeChannel(
        client: YouTubeClient,
        channelId: String,
        params: String? = null,
    ) = innerTubeX.unsubscribeChannel(client, channelId, params).requireSuccess("unsubscribeChannel")

    suspend fun likePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = innerTubeX.likePlaylist(client, playlistId).requireSuccess("likePlaylist")

    suspend fun unlikePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = innerTubeX.unlikePlaylist(client, playlistId).requireSuccess("unlikePlaylist")

    suspend fun addToPlaylist(
        client: YouTubeClient,
        playlistId: String,
        videoId: String,
    ) = innerTubeX.addToPlaylist(client, playlistId, videoId).requireSuccess("addToPlaylist")

    suspend fun addPlaylistToPlaylist(
        client: YouTubeClient,
        playlistId: String,
        addedPlaylistId: String,
    ) = innerTubeX.addPlaylistToPlaylist(client, playlistId, addedPlaylistId).requireSuccess("addPlaylistToPlaylist")

    suspend fun removeFromPlaylist(
        client: YouTubeClient,
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ) = innerTubeX.removePlaylistSong(client, playlistId, setVideoId, videoId).requireSuccess("removeFromPlaylist")

    suspend fun moveSongPlaylist(
        client: YouTubeClient,
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String?,
    ) = innerTubeX.movePlaylistSong(client, playlistId, setVideoId, successorSetVideoId).requireSuccess("moveSongPlaylist")

    suspend fun createPlaylist(
        client: YouTubeClient,
        title: String,
    ) = innerTubeX.createPlaylist(client, title).requireSuccess("createPlaylist")

    suspend fun renamePlaylist(
        client: YouTubeClient,
        playlistId: String,
        name: String,
    ) = innerTubeX.renamePlaylist(client, playlistId, name).requireSuccess("renamePlaylist")

    suspend fun setPlaylistThumbnail(
        client: YouTubeClient,
        playlistId: String,
        image: ByteArray,
    ) = innerTubeX.setPlaylistThumbnail(client, playlistId, image).requireSuccess("setPlaylistThumbnail")

    suspend fun removePlaylistThumbnail(
        client: YouTubeClient,
        playlistId: String,
    ) = innerTubeX.removePlaylistThumbnail(client, playlistId).requireSuccess("removePlaylistThumbnail")

    suspend fun deletePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = innerTubeX.deletePlaylist(client, playlistId).requireSuccess("deletePlaylist")

    suspend fun uploadSong(
        filename: String,
        contentLength: Long,
        content: () -> InputStream,
    ) = withContext(Dispatchers.IO) {
        innerTubeX
            .uploadSong(filename, contentLength) {
                content().toByteReadChannel(Dispatchers.IO)
            }.requireSuccess("uploadSong")
    }

    suspend fun deletePrivatelyOwnedEntity(entityId: String) =
        innerTubeX
            .deletePrivatelyOwnedEntity(YouTubeClient.WEB_REMIX, entityId)
            .requireSuccess("deletePrivatelyOwnedEntity")

    private suspend fun HttpResponse.requireSuccess(operation: String): HttpResponse {
        if (!status.isSuccess()) {
            bodyAsChannel().cancel(null)
            throw InnerTubeHttpException(operation, status)
        }
        return this
    }

    private suspend fun returnYouTubeDislike(videoId: String) =
        withRetry {
            httpClient.get("https://returnyoutubedislikeapi.com/Votes?videoId=$videoId") {
                contentType(ContentType.Application.Json)
            }
        }

    private suspend fun <T> withRetry(
        maxAttempts: Int = 3,
        initialDelay: Long = 500L,
        factor: Double = 2.0,
        block: suspend () -> T,
    ): T {
        var currentDelay = initialDelay
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (exception: IOException) {
                attempt++
                if (attempt >= maxAttempts) throw exception
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong()
            }
        }
    }

    suspend fun getMediaInfo(videoId: String): Result<MediaInfo> =
        runCatching {
            val response =
                next(
                    client = YouTubeClient.WEB,
                    videoId = videoId,
                    playlistId = null,
                    playlistSetVideoId = null,
                    index = null,
                    params = null,
                    continuation = null,
                ).body<NextResponse>()

            val baseForInfo =
                response.contents.twoColumnWatchNextResults
                    ?.results
                    ?.results
                    ?.content
                    ?.find { it?.videoSecondaryInfoRenderer != null }
                    ?.videoSecondaryInfoRenderer

            val baseForTitle =
                response.contents.twoColumnWatchNextResults
                    ?.results
                    ?.results
                    ?.content
                    ?.find { it?.videoPrimaryInfoRenderer != null }
                    ?.videoPrimaryInfoRenderer

            val returnYouTubeDislikeResponse =
                returnYouTubeDislike(videoId).body<ReturnYouTubeDislikeResponse>()

            MediaInfo(
                videoId = videoId,
                title = baseForTitle?.title?.runs?.firstOrNull()?.text,
                author = baseForInfo?.owner?.videoOwnerRenderer?.title?.runs?.firstOrNull()?.text,
                authorId = baseForInfo?.owner?.videoOwnerRenderer?.navigationEndpoint?.browseEndpoint?.browseId,
                authorThumbnail =
                    baseForInfo
                        ?.owner
                        ?.videoOwnerRenderer
                        ?.thumbnail
                        ?.thumbnails
                        ?.find { it.height == 48 }
                        ?.url
                        ?.replace("s48", "s960"),
                description = baseForInfo?.attributedDescription?.content,
                subscribers = baseForInfo?.owner?.videoOwnerRenderer?.subscriberCountText?.simpleText?.split(" ")?.firstOrNull(),
                uploadDate = baseForTitle?.dateText?.simpleText,
                viewCount = returnYouTubeDislikeResponse.viewCount,
                like = returnYouTubeDislikeResponse.likes,
                dislike = returnYouTubeDislikeResponse.dislikes,
            )
        }
}
