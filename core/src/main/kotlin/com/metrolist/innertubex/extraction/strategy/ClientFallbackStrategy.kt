package com.metrolist.innertubex.extraction.strategy

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.models.YouTubeClient

data class ClientSelectionRequest(
    val hints: ContentHints,
    val authenticated: Boolean,
    val availablePoTokenProviders: Set<PoTokenProviderKind> = emptySet(),
    val javaScriptRuntimeAvailable: Boolean = true,
    val webViewAvailable: Boolean = false,
    val fastPathOnly: Boolean = false,
    val excludedClients: Set<String> = emptySet(),
)

data class SelectedClient(
    val client: YouTubeClient,
    val manifest: PlaybackClientManifest? = null,
    val score: Int = 0,
    val manualOverride: Boolean = false,
)

data class ClientSelectionResult(
    val candidates: List<SelectedClient>,
)

interface ClientFallbackStrategy {
    fun resolveClients(hints: ContentHints): List<YouTubeClient>

    fun selectClients(request: ClientSelectionRequest): ClientSelectionResult =
        ClientSelectionResult(resolveClients(request.hints).map(::SelectedClient))
}
