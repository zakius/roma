package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.models.response.PlayerResponse

public class ContentHints(
    public val isExplicit: Boolean? = null,
    public val isKidsContent: Boolean? = null,
    public val isAgeRestricted: Boolean? = null,
    public val isLive: Boolean? = null,
    public val isUploaded: Boolean? = null,
    public val isLocal: Boolean? = null,
    public val endpointParams: String? = null,
    public val playbackClientOverrideId: String? = null,
) {
    /** True only when the caller has independently confirmed YouTube Premium entitlement. */
    public var premium: Boolean = false
        private set

    public operator fun component1(): Boolean? = isExplicit

    public operator fun component2(): Boolean? = isKidsContent

    public operator fun component3(): Boolean? = isAgeRestricted

    public operator fun component4(): Boolean? = isLive

    public operator fun component5(): Boolean? = isUploaded

    public operator fun component6(): Boolean? = isLocal

    public operator fun component7(): String? = endpointParams

    public operator fun component8(): String? = playbackClientOverrideId

    public fun copy(
        isExplicit: Boolean? = this.isExplicit,
        isKidsContent: Boolean? = this.isKidsContent,
        isAgeRestricted: Boolean? = this.isAgeRestricted,
        isLive: Boolean? = this.isLive,
        isUploaded: Boolean? = this.isUploaded,
        isLocal: Boolean? = this.isLocal,
        endpointParams: String? = this.endpointParams,
        playbackClientOverrideId: String? = this.playbackClientOverrideId,
    ): ContentHints =
        ContentHints(
            isExplicit = isExplicit,
            isKidsContent = isKidsContent,
            isAgeRestricted = isAgeRestricted,
            isLive = isLive,
            isUploaded = isUploaded,
            isLocal = isLocal,
            endpointParams = endpointParams,
            playbackClientOverrideId = playbackClientOverrideId,
        ).also {
            it.premium = premium
        }

    /** Returns a copy carrying the caller-confirmed entitlement state. */
    public fun withPremium(premium: Boolean = true): ContentHints =
        copy().also {
            it.premium = premium
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContentHints) return false
        return isExplicit == other.isExplicit &&
            isKidsContent == other.isKidsContent &&
            isAgeRestricted == other.isAgeRestricted &&
            isLive == other.isLive &&
            isUploaded == other.isUploaded &&
            isLocal == other.isLocal &&
            endpointParams == other.endpointParams &&
            playbackClientOverrideId == other.playbackClientOverrideId &&
            premium == other.premium
    }

    override fun hashCode(): Int {
        var result = isExplicit?.hashCode() ?: 0
        result = 31 * result + (isKidsContent?.hashCode() ?: 0)
        result = 31 * result + (isAgeRestricted?.hashCode() ?: 0)
        result = 31 * result + (isLive?.hashCode() ?: 0)
        result = 31 * result + (isUploaded?.hashCode() ?: 0)
        result = 31 * result + (isLocal?.hashCode() ?: 0)
        result = 31 * result + (endpointParams?.hashCode() ?: 0)
        result = 31 * result + (playbackClientOverrideId?.hashCode() ?: 0)
        return 31 * result + premium.hashCode()
    }

    override fun toString(): String = diagnosticSummary()
}

public fun ContentHints.diagnosticSummary(): String =
    "{explicit=$isExplicit, kids=$isKidsContent, ageRestricted=$isAgeRestricted, " +
        "live=$isLive, uploaded=$isUploaded, local=$isLocal, " +
        "endpointParamsPresent=${endpointParams != null}, " +
        "clientOverride=$playbackClientOverrideId, " +
        "premium=$premium}"

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

public data class StreamAttemptDiagnostic(
    val clientName: String,
    val profileId: String?,
    val userAgent: String,
    val outcome: String,
)

public data class StreamDiagnostics(
    val attempts: List<StreamAttemptDiagnostic>,
    val usedAuthenticatedWatchPage: Boolean,
)

public class StreamResolveException(
    public val reason: Reason,
    message: String,
    cause: Throwable? = null,
    public val diagnostics: StreamDiagnostics? = null,
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
