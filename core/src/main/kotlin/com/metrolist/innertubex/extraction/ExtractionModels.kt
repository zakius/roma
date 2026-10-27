package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.models.response.PlayerResponse

/** roma passes no hints; the extractor sets these for its own retries (age gate, kids and embedded fallbacks). */
public data class ContentHints(
    public val isKidsContent: Boolean? = null,
    public val isAgeRestricted: Boolean? = null,
    public val playbackClientOverrideId: String? = null,
)

public data class PlayerConfig(
    val playerUrl: String,
    val signatureTimestamp: Int?,
    val visitorData: String?,
    val clientVersion: String?,
    val encryptedHostFlags: String? = null,
) {
    override fun toString(): String =
        "PlayerConfig(" +
            "playerUrl=${playerUrl.presence()}, " +
            "signatureTimestamp=${signatureTimestamp != null}, " +
            "visitorData=${visitorData.presence()}, " +
            "clientVersion=${clientVersion.presence()}, " +
            "encryptedHostFlags=${encryptedHostFlags.presence()})"
}

internal data class ClientResult(
    val clientName: String,
    val profileId: String,
    val userAgent: String,
    val response: PlayerResponse,
    val usedPoToken: Boolean = false,
    val streamingDataPoToken: String? = null,
    val clientId: Int = 0,
    val clientVersion: String = "",
) {
    override fun toString(): String =
        "ClientResult(" +
            "clientName=$clientName, " +
            "profileId=$profileId, " +
            "usedPoToken=$usedPoToken, " +
            "streamingDataPoToken=${streamingDataPoToken.presence()}, " +
            "clientId=$clientId, " +
            "clientVersion=${clientVersion.presence()})"
}

/** What roma reads from a resolved stream. The signed URL is sensitive and must not be logged. */
public class ExtractedStream(
    val audioUrl: String,
    val loudnessDb: Double?,
    val mimeType: String?,
    val bitrate: Int?,
    val profileId: String,
)

public class StreamResolveException(
    public val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    public enum class Reason {
        NO_PLAYABLE_STREAM,
        EXPLICIT_UNSUPPORTED,
        UNAVAILABLE,
        AGE_RESTRICTED,
        NO_MUSIC_VIDEO,
        NETWORK,
        UNKNOWN,
    }
}

private fun String?.presence(): String = if (isNullOrBlank()) "missing" else "present"
