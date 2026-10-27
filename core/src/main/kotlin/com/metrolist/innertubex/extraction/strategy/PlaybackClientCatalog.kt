package com.metrolist.innertubex.extraction.strategy

import com.metrolist.innertubex.models.PoTokenBinding
import com.metrolist.innertubex.models.YouTubeClient

/** InnerTubeX's playback clients, only those roma's lookups can select (see docs/design.md). */
object PlaybackClientCatalog {
    private val webProviders =
        setOf(PoTokenProviderKind.WEB_BOTGUARD, PoTokenProviderKind.WEBPAGE_ATTESTATION, PoTokenProviderKind.EXTERNAL)

    // Current Web enforcement is GVS-only. A video-bound GVS token must not be reused by the player request.
    private val webPlayerNotRequired = PoTokenRule()
    private val webSessionPlayerRequired =
        PoTokenRule(PoTokenRequirement.REQUIRED, PoTokenBinding.VISITOR_DATA, webProviders)
    private val webGvsRequired =
        PoTokenRule(
            PoTokenRequirement.REQUIRED,
            PoTokenBinding.VIDEO_ID,
            webProviders,
        )

    private val webRequest =
        ClientRequestCapabilities(
            signatureTimestamp = true,
            signatureCipher = JavaScriptRequirement.RESPONSE_DEPENDENT,
            nTransform = JavaScriptRequirement.RESPONSE_DEPENDENT,
            webView = WebViewRequirement.OPTIONAL_TOKEN_MINTING,
            cookies = true,
        )

    private val validatedRestrictedWebContent =
        ClientContentCapabilities(
            kids = CapabilitySupport.SUPPORTED,
            ageRestricted = CapabilitySupport.SUPPORTED,
        )

    val manifests: List<PlaybackClientManifest> =
        listOf(
            manifest(
                id = "VISIONOS_0_1",
                client = YouTubeClient.VISIONOS_0_1,
                lifecycle = ClientLifecycle.DEPRECATED,
                selectionMode = ClientSelectionMode.PROBE_ONLY,
                priority = 100,
                content = normalSongsOnlyContent(),
            ),
            manifest(
                id = "WEB_REMIX",
                client = YouTubeClient.WEB_REMIX,
                lifecycle = ClientLifecycle.STABLE,
                selectionMode = ClientSelectionMode.AUTOMATIC,
                priority = 70,
                authentication = AuthenticationPolicy.OPTIONAL,
                poTokens = ClientPoTokenCapabilities(player = webPlayerNotRequired, gvs = webGvsRequired),
                request = webRequest,
                content = validatedRestrictedWebContent,
            ),
            manifest(
                id = "WEB_CREATOR",
                client = YouTubeClient.WEB_CREATOR,
                lifecycle = ClientLifecycle.STABLE,
                selectionMode = ClientSelectionMode.AUTOMATIC,
                priority = 55,
                authentication = AuthenticationPolicy.REQUIRED,
                poTokens = ClientPoTokenCapabilities(player = webPlayerNotRequired, gvs = webGvsRequired),
                request = webRequest,
                content = validatedRestrictedWebContent,
            ),
            manifest(
                id = "TVHTML5_SIMPLY",
                client = YouTubeClient.TVHTML5_SIMPLY,
                lifecycle = ClientLifecycle.EXPERIMENTAL,
                selectionMode = ClientSelectionMode.AUTOMATIC,
                priority = 42,
                poTokens = ClientPoTokenCapabilities(player = webSessionPlayerRequired, gvs = webGvsRequired),
                request =
                    webRequest.copy(
                        webView = WebViewRequirement.REQUIRED_TOKEN_MINTING,
                        cookies = false,
                    ),
                content =
                    ClientContentCapabilities(
                        kids = CapabilitySupport.SUPPORTED,
                        ageRestricted = CapabilitySupport.UNSUPPORTED,
                    ),
            ),
            manifest(
                id = "WEB_EMBEDDED_PLAYER",
                client = YouTubeClient.WEB_EMBEDDED_PLAYER,
                lifecycle = ClientLifecycle.STABLE,
                selectionMode = ClientSelectionMode.AUTOMATIC,
                priority = 65,
                authentication = AuthenticationPolicy.OPTIONAL,
                request =
                    webRequest.copy(
                        embedded = true,
                        encryptedHostFlags = true,
                        webView = WebViewRequirement.NONE,
                    ),
                content =
                    ClientContentCapabilities(
                        normal = CapabilitySupport.SUPPORTED,
                        kids = CapabilitySupport.SUPPORTED,
                        ageRestricted = CapabilitySupport.UNSUPPORTED,
                    ),
            ),
            manifest(
                id = "WEB_KIDS",
                client = YouTubeClient.WEB_KIDS,
                lifecycle = ClientLifecycle.EXPERIMENTAL,
                selectionMode = ClientSelectionMode.AUTOMATIC,
                priority = 55,
                request = webRequest.copy(cookies = false),
                content =
                    ClientContentCapabilities(
                        normal = CapabilitySupport.UNSUPPORTED,
                        kids = CapabilitySupport.SUPPORTED,
                        ageRestricted = CapabilitySupport.UNSUPPORTED,
                    ),
            ),
            manifest(
                id = "ANDROID_KIDS",
                client = YouTubeClient.ANDROID_KIDS,
                lifecycle = ClientLifecycle.EXPERIMENTAL,
                selectionMode = ClientSelectionMode.AUTOMATIC,
                priority = 60,
                content =
                    ClientContentCapabilities(
                        normal = CapabilitySupport.UNSUPPORTED,
                        kids = CapabilitySupport.SUPPORTED,
                        ageRestricted = CapabilitySupport.UNSUPPORTED,
                    ),
            ),
        )

    val automaticManifests: List<PlaybackClientManifest> = manifests.filter(PlaybackClientManifest::isAutomatic)

    fun findManifest(id: String): PlaybackClientManifest? = manifests.firstOrNull { it.id == id }

    fun profileIds(
        manifest: PlaybackClientManifest,
        usedPoToken: Boolean,
    ): Set<String> =
        setOf(
            "${manifest.id}__${if (usedPoToken) "po" else "nopo"}",
            manifest.client.legacyProfileId(usedPoToken),
        )

    private fun YouTubeClient.legacyProfileId(usedPoToken: Boolean): String {
        val base =
            buildString {
                append(clientName)
                append('_')
                append(friendlyName ?: clientVersion)
                if (isEmbedded) append("_embedded")
            }
        return "${base.replace(Regex("[^A-Za-z0-9_.-]"), "_")}_${if (usedPoToken) "po" else "nopo"}"
    }

    private fun manifest(
        id: String,
        client: YouTubeClient,
        lifecycle: ClientLifecycle,
        selectionMode: ClientSelectionMode,
        authentication: AuthenticationPolicy = AuthenticationPolicy.UNSUPPORTED,
        poTokens: ClientPoTokenCapabilities = ClientPoTokenCapabilities(),
        request: ClientRequestCapabilities =
            ClientRequestCapabilities(
                signatureTimestamp = client.useSignatureTimestamp,
                embedded = client.isEmbedded,
            ),
        content: ClientContentCapabilities,
        priority: Int,
    ) = PlaybackClientManifest(
        id = id,
        client = client,
        lifecycle = lifecycle,
        selectionMode = selectionMode,
        authentication = authentication,
        poTokens = poTokens,
        request = request,
        content = content,
        priority = priority,
    )

    private fun normalSongsOnlyContent() =
        ClientContentCapabilities(
            kids = CapabilitySupport.UNSUPPORTED,
            ageRestricted = CapabilitySupport.UNSUPPORTED,
        )
}
