package com.metrolist.innertubex.extraction.strategy

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.models.YouTubeClient

class ContentAwareFallbackStrategy(
    private val healthMonitor: ClientHealthMonitor = ClientHealthMonitor.NONE,
    private val catalog: PlaybackClientCatalog = PlaybackClientCatalog,
) : ClientFallbackStrategy {
    /**
     * Resolves only clients that are usable with conservative, signed-out runtime capabilities.
     * Call [selectClients] when authentication, token providers, or platform runtimes are available.
     */
    override fun resolveClients(hints: ContentHints): List<YouTubeClient> =
        selectClients(
            ClientSelectionRequest(
                hints = hints,
                authenticated = false,
                javaScriptRuntimeAvailable = false,
                webViewAvailable = false,
            ),
        ).candidates.map(SelectedClient::client)

    override fun selectClients(request: ClientSelectionRequest): ClientSelectionResult = selectClients(request, visitorDataAvailable = false)

    internal fun includesVisitorBackedLegacy(excludedClients: Set<String>): Boolean =
        catalog.findManifest("VISIONOS_0_1")?.let {
            it.selectionMode == ClientSelectionMode.PROBE_ONLY &&
                !it.isExcluded(excludedClients, emptySet())
        } == true

    internal fun selectClients(
        request: ClientSelectionRequest,
        visitorDataAvailable: Boolean,
    ): ClientSelectionResult {
        request.hints.playbackClientOverrideId?.let(catalog::findManifest)?.let { manifest ->
            if (
                !manifest.isExcluded(
                    request.excludedClients,
                    request.availablePoTokenProviders,
                )
            ) {
                return ClientSelectionResult(
                    candidates =
                        listOf(
                            SelectedClient(
                                client = manifest.client,
                                manifest = manifest,
                                score = Int.MAX_VALUE,
                                manualOverride = true,
                            ),
                        ),
                )
            }
        }

        val candidates = mutableListOf<SelectedClient>()
        val healthDemoted = mutableSetOf<String>()
        val visitorBackedLegacy =
            catalog.findManifest("VISIONOS_0_1")?.takeIf {
                visitorDataAvailable && it.selectionMode == ClientSelectionMode.PROBE_ONLY
            }
        (catalog.automaticManifests + listOfNotNull(visitorBackedLegacy)).forEach { manifest ->
            if (
                manifest.isExcluded(
                    request.excludedClients,
                    request.availablePoTokenProviders,
                )
            ) {
                return@forEach
            }
            if (isHardRejected(manifest, request)) return@forEach

            val (score, health) = score(manifest, request)
            if (health <= HEALTH_DEMOTION_THRESHOLD) healthDemoted += manifest.id
            candidates += SelectedClient(manifest.client, manifest, score)
        }

        val orderedCandidates =
            candidates.sortedWith(
                compareBy<SelectedClient> {
                    when {
                        // Repeatedly failing clients go behind every healthy one instead of only losing points.
                        it.manifest?.id in healthDemoted -> 3

                        request.hints.isKidsContent == true && it.client in kidsClients -> 0

                        else -> 1
                    }
                }.thenByDescending { it.score }
                    .thenBy { it.manifest?.id },
            )
        return ClientSelectionResult(candidates = orderedCandidates)
    }

    private val kidsClients = setOf(YouTubeClient.ANDROID_KIDS, YouTubeClient.WEB_KIDS)

    private fun isHardRejected(
        manifest: PlaybackClientManifest,
        request: ClientSelectionRequest,
    ): Boolean {
        val contentSupport = manifest.content.supportFor(request.hints)
        val tokenRules = listOf(manifest.poTokens.player, manifest.poTokens.gvs)
        return manifest.lifecycle == ClientLifecycle.BROKEN ||
            (manifest.authentication == AuthenticationPolicy.REQUIRED && !request.authenticated) ||
            contentSupport == CapabilitySupport.UNSUPPORTED ||
            (manifest.id == "VISIONOS_0_1" && contentSupport != CapabilitySupport.SUPPORTED) ||
            (!request.javaScriptRuntimeAvailable && manifest.request.signatureCipher == JavaScriptRequirement.REQUIRED) ||
            (!request.javaScriptRuntimeAvailable && manifest.request.nTransform == JavaScriptRequirement.REQUIRED) ||
            (manifest.request.webView == WebViewRequirement.FULL_PLAYER && !request.webViewAvailable) ||
            tokenRules.any { rule ->
                rule.requirement == PoTokenRequirement.REQUIRED && rule.providers.none { it in request.availablePoTokenProviders }
            } ||
            (
                request.fastPathOnly &&
                    (
                        manifest.request.signatureTimestamp ||
                            tokenRules.any { it.requirement == PoTokenRequirement.REQUIRED } ||
                            manifest.request.signatureCipher == JavaScriptRequirement.REQUIRED ||
                            manifest.request.nTransform == JavaScriptRequirement.REQUIRED
                    )
            )
    }

    private fun PlaybackClientManifest.isExcluded(
        excludedClients: Set<String>,
        availablePoTokenProviders: Set<PoTokenProviderKind>,
    ): Boolean =
        client.clientName in excludedClients ||
            id in excludedClients ||
            run {
                val noPoExcluded = catalog.profileIds(this, usedPoToken = false).any { it in excludedClients }
                val poExcluded = catalog.profileIds(this, usedPoToken = true).any { it in excludedClients }
                val tokenRules = listOf(poTokens.player, poTokens.gvs)
                val canUsePoTokens =
                    tokenRules.any { rule ->
                        rule.requirement != PoTokenRequirement.NONE &&
                            rule.providers.any { it in availablePoTokenProviders }
                    }
                val requiresPoTokens = tokenRules.any { it.requirement == PoTokenRequirement.REQUIRED }
                (noPoExcluded && poExcluded) ||
                    (!canUsePoTokens && noPoExcluded) ||
                    (requiresPoTokens && poExcluded)
            }

    private fun score(
        manifest: PlaybackClientManifest,
        request: ClientSelectionRequest,
    ): Pair<Int, Int> {
        var score = manifest.priority

        fun adjust(value: Int) {
            score += value
        }

        when (manifest.content.supportFor(request.hints)) {
            CapabilitySupport.SUPPORTED -> adjust(25)
            CapabilitySupport.LIMITED -> adjust(-12)
            CapabilitySupport.UNKNOWN -> adjust(-4)
            CapabilitySupport.UNSUPPORTED -> Unit
        }

        val restrictedContent = request.hints.isKidsContent == true || request.hints.isAgeRestricted == true
        if (restrictedContent && manifest.authentication == AuthenticationPolicy.OPTIONAL) {
            adjust(if (request.authenticated) 18 else -18)
        }
        if (manifest.authentication == AuthenticationPolicy.REQUIRED) adjust(8)


        when (manifest.lifecycle) {
            ClientLifecycle.STABLE -> Unit
            ClientLifecycle.CANARY -> adjust(-15)
            ClientLifecycle.EXPERIMENTAL -> adjust(-10)
            ClientLifecycle.UNRELEASED -> adjust(-5)
            ClientLifecycle.DEPRECATED -> adjust(-20)
            ClientLifecycle.BROKEN -> adjust(-100)
        }

        val tokenRules = listOf(manifest.poTokens.player, manifest.poTokens.gvs)
        val requiredTokenCount = tokenRules.count { it.requirement == PoTokenRequirement.REQUIRED }
        val optionalTokenCount = tokenRules.count { it.requirement == PoTokenRequirement.OPTIONAL }
        if (requiredTokenCount > 0) adjust(-12 * requiredTokenCount)
        if (optionalTokenCount > 0) adjust(-4 * optionalTokenCount)

        if (manifest.request.nTransform == JavaScriptRequirement.RESPONSE_DEPENDENT) adjust(-4)
        if (manifest.request.signatureCipher == JavaScriptRequirement.RESPONSE_DEPENDENT) adjust(-3)
        when (manifest.request.webView) {
            WebViewRequirement.NONE -> Unit
            WebViewRequirement.OPTIONAL_TOKEN_MINTING -> adjust(-2)
            WebViewRequirement.REQUIRED_TOKEN_MINTING -> adjust(-8)
            WebViewRequirement.FULL_PLAYER -> adjust(-25)
        }

        val healthAdjustment =
            healthMonitor.scoreAdjustment(
                manifest.id,
                ClientHealthScope.from(request.hints, request.authenticated),
            )
        if (healthAdjustment != 0) adjust(healthAdjustment)
        return score to healthAdjustment
    }
}

/** About three consecutive failures, or any cooldown, in the default and host trackers. */
private const val HEALTH_DEMOTION_THRESHOLD = -50
