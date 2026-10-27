package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind

public data class TokenProviderCapabilities(
    val providers: Set<PoTokenProviderKind> = emptySet(),
    val usesWebView: Boolean = false,
)

/** Implementations handle sensitive cookies and attestation tokens; never log their values. */
public interface TokenProvider {
    public val capabilities: TokenProviderCapabilities
        get() = TokenProviderCapabilities()

    /** Cookie and returned token values are sensitive and must not be logged. */
    public suspend fun getPoToken(
        videoId: String,
        visitorData: String,
        cookie: String? = null,
    ): PoTokenResult?

    public suspend fun prewarm(cookie: String? = null) {}

    public suspend fun invalidateAttestation() {}

    public suspend fun close() {}
}

internal object UnavailableTokenProvider : TokenProvider {
    override suspend fun getPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? = null
}

public data class PoTokenResult(
    val playerRequestToken: String,
    val streamingDataToken: String,
    val visitorData: String,
) {
    override fun toString(): String =
        "PoTokenResult(" +
            "playerRequestToken=${playerRequestToken.presence()}, " +
            "streamingDataToken=${streamingDataToken.presence()}, " +
            "visitorData=${visitorData.presence()})"

    private fun String.presence(): String = if (isBlank()) "missing" else "present"
}
