package com.metrolist.innertubex.extraction.strategy

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.models.PoTokenBinding
import com.metrolist.innertubex.models.YouTubeClient

enum class ClientLifecycle {
    STABLE,
    CANARY,
    EXPERIMENTAL,
    UNRELEASED,
    DEPRECATED,
    BROKEN,
}

enum class ClientSelectionMode {
    AUTOMATIC,
    MANUAL_ONLY,
    PROBE_ONLY,
    API_ONLY,
    DISABLED,
}

enum class AuthenticationPolicy {
    UNSUPPORTED,
    OPTIONAL,
    REQUIRED,
}

enum class CapabilitySupport {
    SUPPORTED,
    LIMITED,
    UNKNOWN,
    UNSUPPORTED,
}

enum class JavaScriptRequirement {
    NEVER,
    RESPONSE_DEPENDENT,
    REQUIRED,
}

enum class WebViewRequirement {
    NONE,
    OPTIONAL_TOKEN_MINTING,
    REQUIRED_TOKEN_MINTING,
    FULL_PLAYER,
}

enum class PoTokenRequirement {
    NONE,
    OPTIONAL,
    REQUIRED,
}

enum class PoTokenProviderKind {
    WEB_BOTGUARD,
    WEBPAGE_ATTESTATION,
    ANDROID_DROIDGUARD,
    IOS_ATTESTATION,
    EXTERNAL,
}

data class PoTokenRule(
    val requirement: PoTokenRequirement = PoTokenRequirement.NONE,
    val binding: PoTokenBinding? = null,
    val providers: Set<PoTokenProviderKind> = emptySet(),
) {
    init {
        require(requirement == PoTokenRequirement.NONE || binding != null) {
            "A PO token rule must declare its content binding"
        }
        require(requirement == PoTokenRequirement.NONE || providers.isNotEmpty()) {
            "A PO token rule must declare at least one compatible provider"
        }
    }
}

data class ClientPoTokenCapabilities(
    val player: PoTokenRule = PoTokenRule(),
    val gvs: PoTokenRule = PoTokenRule(),
    val subtitles: PoTokenRule = PoTokenRule(),
)

data class ClientContentCapabilities(
    val normal: CapabilitySupport = CapabilitySupport.SUPPORTED,
    val kids: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val ageRestricted: CapabilitySupport = CapabilitySupport.UNKNOWN,
) {
    fun supportFor(hints: ContentHints): CapabilitySupport =
        when {
            (hints.isAgeRestricted == true && ageRestricted == CapabilitySupport.UNSUPPORTED) ||
                (hints.isKidsContent == true && kids == CapabilitySupport.UNSUPPORTED) -> CapabilitySupport.UNSUPPORTED

            hints.isAgeRestricted == true -> ageRestricted

            hints.isKidsContent == true -> kids

            else -> normal
        }
}

data class ClientRequestCapabilities(
    val signatureTimestamp: Boolean = false,
    val signatureCipher: JavaScriptRequirement = JavaScriptRequirement.NEVER,
    val nTransform: JavaScriptRequirement = JavaScriptRequirement.NEVER,
    val webView: WebViewRequirement = WebViewRequirement.NONE,
    val cookies: Boolean = false,
    val embedded: Boolean = false,
    val encryptedHostFlags: Boolean = false,
)

data class PlaybackClientManifest(
    val id: String,
    val client: YouTubeClient,
    val lifecycle: ClientLifecycle,
    val selectionMode: ClientSelectionMode,
    val authentication: AuthenticationPolicy,
    val poTokens: ClientPoTokenCapabilities = ClientPoTokenCapabilities(),
    val request: ClientRequestCapabilities = ClientRequestCapabilities(),
    val content: ClientContentCapabilities = ClientContentCapabilities(),
    val priority: Int,
) {
    val isAutomatic: Boolean
        get() = selectionMode == ClientSelectionMode.AUTOMATIC
}
