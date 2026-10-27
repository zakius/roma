package com.metrolist.innertubex.models.response

import com.metrolist.innertubex.models.PlayerFormatListSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class PlayerResponse(
    val playabilityStatus: PlayabilityStatus,
    val streamingData: StreamingData? = null,
    val videoDetails: VideoDetails? = null,
    val playbackTracking: PlaybackTracking? = null,
    val playerConfig: PlayerConfig? = null,
) {
    @Serializable
    data class PlayabilityStatus(
        val status: String,
        val reason: String? = null,
    ) {
        // Keep this wire-only signal out of the public constructor and copy contract.
        internal var desktopLegacyAgeGateReason: Int? = null
    }

    @Serializable
    data class StreamingData(
        @Serializable(with = PlayerFormatListSerializer::class)
        val formats: List<Format>? = null,
        @Serializable(with = PlayerFormatListSerializer::class)
        val adaptiveFormats: List<Format> = emptyList(),
        val hlsManifestUrl: String? = null,
    ) {
        @Serializable
        data class Format(
            val itag: Int = -1,
            val url: String? = null,
            val mimeType: String = "",
            val bitrate: Int = 0,
            val width: Int? = null,
            val contentLength: Long? = null,
            val audioSampleRate: Int? = null,
            val audioChannels: Int? = null,
            val loudnessDb: Double? = null,
            val xtags: String? = null,
            val audioTrack: AudioTrack? = null,
            @JsonNames("signature_cipher")
            val signatureCipher: String? = null,
            val cipher: String? = null,
        ) {
            val isAudio: Boolean
                get() = width == null

            @Serializable
            data class AudioTrack(
                val audioIsDefault: Boolean? = null,
            )
        }
    }

    @Serializable
    class PlayerConfig {
        var audioConfig: AudioConfig? = null
            private set

        @Serializable
        data class AudioConfig(
            val loudnessDb: Double? = null,
        )
    }

    @Serializable
    data class VideoDetails(
        val videoId: String = "",
    )

    @Serializable
    data class PlaybackTracking(
        val videostatsPlaybackUrl: VideostatsUrl? = null,
    ) {
        @Serializable
        data class VideostatsUrl(
            val baseUrl: String,
        )
    }
}
