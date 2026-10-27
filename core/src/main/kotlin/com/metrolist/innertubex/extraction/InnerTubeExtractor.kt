package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.InMemoryClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.models.YouTubeLocale
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import com.metrolist.innertubex.utils.encodeQueryComponent
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.util.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class InnerTubeExtractor internal constructor(
    private val configParser: YtConfigParser,
    private val clientDirector: PlayerClientDirector,
    private val cipherService: ExtractionCipherService,
    private val innerTube: InnerTube,
    private val tokenProvider: TokenProvider = UnavailableTokenProvider,
    private val now: () -> Instant = { Clock.System.now() },
    private val extractionTimeBudgetMs: Long = 30_000L,
) : StreamExtractor {
    constructor(
        configParser: YtConfigParser,
        /** Usually a [YouTubeCipherService]; hosts may isolate cipher solving behind their own implementation. */
        cipherService: ExtractionCipherService,
        innerTube: InnerTube,
        /** Defaults to a [ContentAwareFallbackStrategy] that scores clients with [clientHealthMonitor]. */
        fallbackStrategy: ClientFallbackStrategy? = null,
        tokenProvider: TokenProvider? = null,
        /** Defaults to process-local memory; pass [ClientHealthMonitor.NONE] for stateless selection. */
        clientHealthMonitor: ClientHealthMonitor =
            InMemoryClientHealthMonitor(sessionGeneration = { innerTube.sessionSnapshot().generation }),
    ) : this(
        configParser = configParser,
        clientDirector =
            PlayerClientDirector(
                innerTube = innerTube,
                fallbackStrategy = fallbackStrategy ?: ContentAwareFallbackStrategy(clientHealthMonitor),
                tokenProvider = tokenProvider ?: UnavailableTokenProvider,
                clientHealthMonitor = clientHealthMonitor,
            ),
        cipherService = cipherService,
        innerTube = innerTube,
        tokenProvider = tokenProvider ?: UnavailableTokenProvider,
    )

    private companion object {

        // An unusable cached config is refetched immediately, so the TTL only bounds idle staleness.
        private const val PLAYER_CONFIG_CACHE_TTL_MS = 3 * 60 * 60 * 1000L
        private const val WEB_EMBEDDED_PLAYER_ID = "WEB_EMBEDDED_PLAYER"
        private const val WEB_KIDS_ID = "WEB_KIDS"
        private val N_PARAMETER_REGEX = Regex("(?:[?&]|%26)n(?:=|%3[dD])", RegexOption.IGNORE_CASE)

        // Allow a cached-config pass and a fresh-config pass, plus native probes: 4 per automatic client plus 2, for
        // InnerTubeX's 12 (roma's catalog keeps the 6 it can select).
        private const val MAX_PLAYER_REQUESTS_PER_EXTRACTION = 50
    }

    private data class CachedPlayerConfig(
        val config: PlayerConfig,
        val cachedAtMs: Long,
        val identity: ConfigIdentity,
        val usedLoginCookies: Boolean,
    )

    /** The only session fields a watch or embed page request depends on. */
    private data class ConfigIdentity(
        val locale: YouTubeLocale,
        val cookieFingerprint: String?,
    )

    private fun configIdentity(useLoginCookies: Boolean): ConfigIdentity =
        innerTube.sessionSnapshot().let {
            ConfigIdentity(
                it.locale,
                it.cookie?.takeIf { useLoginCookies }?.let { cookie ->
                    sha256(cookie.encodeToByteArray()).toHexString()
                },
            )
        }

    private val playerConfigCache = mutableMapOf<Boolean, CachedPlayerConfig>()

    private data class TerminalCacheKey(
        val videoId: String,
        val generation: Long,
        val hints: ContentHints,
        val audioQuality: AudioQuality,
        val excludedClients: Set<String>,
    ) {
        override fun toString(): String = "TerminalCacheKey(redacted)"
    }

    private data class TerminalVerdict(
        val reason: StreamResolveException.Reason,
        val expiresAt: Instant,
    )

    private val terminalCache = linkedMapOf<TerminalCacheKey, TerminalVerdict>()
    private val terminalCacheMutex = Mutex()

    private suspend fun cachedTerminalVerdict(key: TerminalCacheKey): TerminalVerdict? =
        terminalCacheMutex.withLock {
            val currentTime = now()
            terminalCache.entries.removeAll { it.key.generation != key.generation || it.value.expiresAt <= currentTime }
            terminalCache.remove(key)?.also { terminalCache[key] = it }
        }

    private suspend fun cacheTerminalVerdict(
        key: TerminalCacheKey,
        diagnostics: ExtractionDiagnostics,
        reason: StreamResolveException.Reason,
    ) {
        if (innerTube.sessionSnapshot().generation != key.generation || diagnostics.sawPlayableResponse ||
            (reason == StreamResolveException.Reason.UNAVAILABLE && !diagnostics.unavailable) ||
            diagnostics.requestFailures.isNotEmpty() || diagnostics.requestBudget.terminalFailureReason() != reason ||
            diagnostics.attempts.any { !it.outcome.startsWith("selection:") && !it.outcome.startsWith("playability:") }
        ) {
            return
        }
        terminalCacheMutex.withLock {
            terminalCache.remove(key)
            terminalCache[key] = TerminalVerdict(reason, now() + 60.seconds)
            if (terminalCache.size > 256) terminalCache.remove(terminalCache.keys.first())
        }
    }

    private val playerConfigCacheMutex = Mutex()
    private val playerConfigFetchMutexes = mapOf(false to Mutex(), true to Mutex())

    private fun ensureSessionGeneration(generation: Long) {
        if (innerTube.sessionSnapshot().generation != generation) {
            throw CancellationException("InnerTube session changed")
        }
    }

    private fun currentExtractionSession(expected: InnerTube.SessionSnapshot): InnerTube.SessionSnapshot {
        val current = innerTube.sessionSnapshot()
        if (current.generation == expected.generation) return expected
        return current.visitorData
            ?.takeIf(String::isNotBlank)
            ?.let { innerTube.sessionSnapshotWithVisitorData(expected, it) }
            ?: throw CancellationException("InnerTube session changed")
    }

    override suspend fun extract(
        videoId: String,
        hints: ContentHints,
        excludedClients: Set<String>,
        audioQuality: AudioQuality,
        clientPlaybackNonce: String,
    ): ExtractedStream? {
        val terminalKey =
            if (hints.playbackClientOverrideId == null) {
                TerminalCacheKey(videoId, innerTube.sessionSnapshot().generation, hints, audioQuality, excludedClients.toSet())
            } else {
                null
            }
        terminalKey?.let { key ->
            cachedTerminalVerdict(key)?.let { verdict ->
                ensureSessionGeneration(key.generation)
                throw StreamResolveException(
                    reason = verdict.reason,
                    message =
                        if (verdict.reason ==
                            StreamResolveException.Reason.AGE_RESTRICTED
                        ) {
                            "This track is age-restricted."
                        } else {
                            "This track is unavailable."
                        },
                )
            }
        }
        val diagnostics =
            ExtractionDiagnostics(
                maxPlayerRequests = if (hints.playbackClientOverrideId != null) 1 else MAX_PLAYER_REQUESTS_PER_EXTRACTION,
            )
        return try {
            diagnostics.requestBudget.withinTime {
                diagnostics.requestBudget.withTokenScope {
                    extractWithDiagnostics(
                        videoId,
                        hints,
                        excludedClients,
                        audioQuality,
                        clientPlaybackNonce,
                        diagnostics,
                    ).also { diagnostics.ensureSessionCurrent() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlayerRequestBudgetExhaustedException) {
            throw StreamResolveException(
                reason = StreamResolveException.Reason.NO_PLAYABLE_STREAM,
                message = "Extraction time budget exhausted.",
                cause = e,
                diagnostics = diagnostics.snapshot(),
            )
        } catch (e: StreamResolveException) {
            terminalKey?.let { cacheTerminalVerdict(it, diagnostics, e.reason) }
            if (e.diagnostics != null) throw e
            throw StreamResolveException(
                reason = e.reason,
                message = "Unable to resolve stream data.",
                cause = e,
                diagnostics = diagnostics.snapshot(),
            )
        } catch (e: Exception) {
            throw StreamResolveException(
                reason = StreamResolveException.Reason.NETWORK,
                message = "Unable to fetch stream data from YouTube.",
                cause = e,
                diagnostics = diagnostics.snapshot(),
            )
        }
    }

    private suspend fun extractWithDiagnostics(
        videoId: String,
        hints: ContentHints,
        excludedClients: Set<String>,
        audioQuality: AudioQuality,
        clientPlaybackNonce: String,
        diagnostics: ExtractionDiagnostics,
    ): ExtractedStream? {

        val embeddedOverrideId =
            hints.playbackClientOverrideId?.takeIf { id ->
                PlaybackClientCatalog.findManifest(id)?.let { manifest ->
                    manifest.request.embedded
                } == true
            }
        val embeddedOverride = embeddedOverrideId != null
        if (embeddedOverride) {
            val embeddedStream =
                extractWithEmbeddedConfig(
                    videoId = videoId,
                    hints = hints,
                    excludedClients = excludedClients,
                    clientPlaybackNonce = clientPlaybackNonce,
                    audioQuality = audioQuality,
                    diagnostics = diagnostics,
                )
            if (embeddedStream != null) return embeddedStream
            throwExtractionFailure(diagnostics)
        }

        val authenticatedPremiumHighQuality =
            hints.premium && audioQuality == AudioQuality.HIGH && innerTube.hasSapCookieAuth()
        val configFreeEligible =
            hints.playbackClientOverrideId == null &&
                hints.isAgeRestricted != true &&
                hints.isUploaded != true && hints.isLive != true &&
                !authenticatedPremiumHighQuality
        val deferConfigFree =
            configFreeEligible && hints.isKidsContent != true &&
                innerTube.sessionSnapshot().visitorData.isNullOrBlank() &&
                clientDirector.includesVisitorBackedLegacy(excludedClients)

        suspend fun extractConfigFree(): ExtractedStream? =
            extractWithConfig(
                videoId = videoId,
                hints = hints,
                excludedClients = excludedClients,
                clientPlaybackNonce = clientPlaybackNonce,
                playerConfig = PlayerConfig("", null, innerTube.sessionSnapshot().visitorData, null),
                allowCipherProcessing = false,
                audioQuality = audioQuality,
                diagnostics = diagnostics,
            )
        if (configFreeEligible && !deferConfigFree) extractConfigFree()?.let { return it }
        if (diagnostics.unavailable) throwExtractionFailure(diagnostics)

        suspend fun extractWithWatchConfig(useLoginCookies: Boolean): ExtractedStream? =
            try {
                extractWithCachedConfig(
                    videoId = videoId,
                    hints = hints,
                    excludedClients = excludedClients,
                    clientPlaybackNonce = clientPlaybackNonce,
                    useLoginCookies = useLoginCookies,
                    audioQuality = audioQuality,
                    diagnostics = diagnostics,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.requestFailures += error
                null
            }

        val cookieFirst =
            hints.playbackClientOverrideId == null &&
                innerTube.hasSapCookieAuth() &&
                (
                    authenticatedPremiumHighQuality ||
                        hints.isExplicit == true ||
                        hints.isAgeRestricted == true ||
                        hints.isUploaded == true
                )
        val stream = extractWithWatchConfig(useLoginCookies = cookieFirst)
        if (stream != null) return stream
        if (diagnostics.unavailable) throwExtractionFailure(diagnostics)
        if (deferConfigFree) extractConfigFree()?.let { return it }
        if (diagnostics.unavailable) throwExtractionFailure(diagnostics)

        if (cookieFirst && !diagnostics.triedPublicConfigBeforeLogin) {
            val signedOutConfigStream = extractWithWatchConfig(useLoginCookies = false)
            if (signedOutConfigStream != null) return signedOutConfigStream
        } else if (!cookieFirst && innerTube.hasSapCookieAuth()) {
            val authenticatedStream = extractWithWatchConfig(useLoginCookies = true)
            if (authenticatedStream != null) return authenticatedStream
        }

        if (diagnostics.unavailable) throwExtractionFailure(diagnostics)

        // Embedded player context fallback when the regular watch-page clients fail.
        // Uploaded songs intentionally do not use this path because embedded
        // playback does not expose the user's private upload library.
        if (hints.playbackClientOverrideId == null && hints.isUploaded != true) {
            val embeddedStream =
                extractWithEmbeddedConfig(
                    videoId = videoId,
                    hints = hints,
                    excludedClients = excludedClients,
                    clientPlaybackNonce = clientPlaybackNonce,
                    audioQuality = audioQuality,
                    diagnostics = diagnostics,
                )
            if (embeddedStream != null) return embeddedStream
        }

        if (diagnostics.unavailable) throwExtractionFailure(diagnostics)
        val classifiedFailure = classifyPlayabilityFailures(diagnostics.failures)
        if (shouldTryUnknownKidsFallback(diagnostics.requestBudget.withKnownAge(hints), classifiedFailure)) {
            val kidsStream =
                extractWithWebKidsFallback(
                    videoId = videoId,
                    hints = hints,
                    excludedClients = excludedClients,
                    clientPlaybackNonce = clientPlaybackNonce,
                    audioQuality = audioQuality,
                    diagnostics = diagnostics,
                )
            if (kidsStream != null) return kidsStream
        }

        throwExtractionFailure(diagnostics)
    }

    private fun shouldTryUnknownKidsFallback(
        hints: ContentHints,
        classifiedFailure: StreamResolveException.Reason?,
    ): Boolean =
        hints.playbackClientOverrideId == null &&
            hints.isKidsContent == null &&
            hints.isExplicit != true &&
            hints.isAgeRestricted != true &&
            hints.isLive != true &&
            hints.isUploaded != true &&
            classifiedFailure != StreamResolveException.Reason.AGE_RESTRICTED

    private suspend fun extractWithWebKidsFallback(
        videoId: String,
        hints: ContentHints,
        excludedClients: Set<String>,
        clientPlaybackNonce: String,
        audioQuality: AudioQuality,
        diagnostics: ExtractionDiagnostics,
    ): ExtractedStream? {
        val config =
            getCachedPlayerConfig(
                useLoginCookies = false,
                nowMs = Clock.System.now().toEpochMilliseconds(),
            )?.config ?: return null
        val fallbackHints =
            hints.copy(
                isKidsContent = true,
                playbackClientOverrideId = WEB_KIDS_ID,
            )
        return extractWithConfig(
            videoId = videoId,
            hints = fallbackHints,
            excludedClients = excludedClients,
            clientPlaybackNonce = clientPlaybackNonce,
            playerConfig = config,
            audioQuality = audioQuality,
            diagnostics = diagnostics,
        )
    }

    private suspend fun extractWithEmbeddedConfig(
        videoId: String,
        hints: ContentHints,
        excludedClients: Set<String>,
        clientPlaybackNonce: String,
        audioQuality: AudioQuality,
        diagnostics: ExtractionDiagnostics,
    ): ExtractedStream? {
        diagnostics.ensureSessionCurrent()
        if (diagnostics.requestBudget.remaining <= 0) return null
        if (hints.playbackClientOverrideId == null &&
            (hints.isAgeRestricted == true || diagnostics.requestBudget.ageRestricted)
        ) {
            return null
        }
        val config =
            try {
                configParser.fetchEmbeddedConfig(videoId, useLoginCookies = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.requestFailures += error
                return null
            }
        diagnostics.ensureSessionCurrent()
        if (config.encryptedHostFlags.isNullOrBlank()) {
            return null
        }
        if (config.signatureTimestamp == null) {
            return null
        }
        val fallbackHints =
            hints.copy(
                playbackClientOverrideId =
                    hints.playbackClientOverrideId?.takeIf { id ->
                        PlaybackClientCatalog.findManifest(id)?.request?.embedded == true
                    } ?: WEB_EMBEDDED_PLAYER_ID,
            )
        return extractWithConfig(
            videoId = videoId,
            hints = fallbackHints,
            excludedClients = excludedClients,
            clientPlaybackNonce = clientPlaybackNonce,
            playerConfig = config,
            audioQuality = audioQuality,
            diagnostics = diagnostics,
        )
    }

    private fun throwExtractionFailure(diagnostics: ExtractionDiagnostics): Nothing {
        diagnostics.requestBudget.timeLeft(Duration.INFINITE)
        if (!diagnostics.sawPlayableResponse) {
            classifyPlayabilityFailures(diagnostics.failures)?.let { reason ->
                throw StreamResolveException(
                    reason = reason,
                    message =
                        when (reason) {
                            StreamResolveException.Reason.AGE_RESTRICTED -> "This track is age-restricted."
                            else -> "This track is unavailable."
                        },
                    diagnostics = diagnostics.snapshot(),
                )
            }
            diagnostics.requestFailures.lastOrNull()?.let { failure ->
                throw StreamResolveException(
                    reason = StreamResolveException.Reason.NETWORK,
                    message = "Unable to fetch stream data from YouTube.",
                    cause = failure,
                    diagnostics = diagnostics.snapshot(),
                )
            }
        }
        throw StreamResolveException(
            reason = StreamResolveException.Reason.NO_PLAYABLE_STREAM,
            message = "No playable stream found for this track.",
            diagnostics = diagnostics.snapshot(),
        )
    }

    private suspend fun extractWithCachedConfig(
        videoId: String,
        hints: ContentHints,
        excludedClients: Set<String>,
        clientPlaybackNonce: String,
        useLoginCookies: Boolean,
        audioQuality: AudioQuality = AudioQuality.AUTO,
        diagnostics: ExtractionDiagnostics,
    ): ExtractedStream? {
        diagnostics.ensureSessionCurrent()
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val cachedConfig =
            getCachedPlayerConfig(useLoginCookies, nowMs)
                ?: if (useLoginCookies && !diagnostics.session.visitorData.isNullOrBlank()) {
                    getCachedPlayerConfig(false, nowMs)?.also { diagnostics.triedPublicConfigBeforeLogin = true }
                } else {
                    null
                }
        if (cachedConfig != null) {
            diagnostics.usedAuthenticatedWatchPage =
                diagnostics.usedAuthenticatedWatchPage || cachedConfig.usedLoginCookies
            val nonGatePassesBefore = diagnostics.nonGatePasses
            val failuresBefore = diagnostics.failures.size
            val cachedStream =
                try {
                    extractWithConfig(
                        videoId = videoId,
                        hints = hints,
                        excludedClients = excludedClients,
                        clientPlaybackNonce = clientPlaybackNonce,
                        playerConfig = cachedConfig.config,
                        audioQuality = audioQuality,
                        diagnostics = diagnostics,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!useLoginCookies || cachedConfig.usedLoginCookies || error is PlayerRequestBudgetExhaustedException) throw error
                    diagnostics.requestFailures += error
                    null
                }
            if (cachedStream != null) return cachedStream
            if (diagnostics.failures.size > failuresBefore && diagnostics.nonGatePasses == nonGatePassesBefore) return null
        }
        if (diagnostics.requestBudget.remaining <= 0) return null
        diagnostics.ensureSessionCurrent()
        val freshCachedConfig = fetchPlayerConfig(videoId, useLoginCookies, cachedConfig, diagnostics.session)
        diagnostics.ensureSessionCurrent()
        diagnostics.usedAuthenticatedWatchPage =
            diagnostics.usedAuthenticatedWatchPage || freshCachedConfig.usedLoginCookies
        return extractWithConfig(
            videoId = videoId,
            hints = hints,
            excludedClients = excludedClients,
            clientPlaybackNonce = clientPlaybackNonce,
            playerConfig = freshCachedConfig.config,
            audioQuality = audioQuality,
            diagnostics = diagnostics,
        )
    }

    private suspend fun fetchPlayerConfig(
        videoId: String,
        useLoginCookies: Boolean,
        unusableConfig: CachedPlayerConfig? = null,
        requestSession: InnerTube.SessionSnapshot,
    ): CachedPlayerConfig =
        playerConfigFetchMutexes.getValue(useLoginCookies).withLock {
            currentExtractionSession(requestSession)
            playerConfigCacheMutex
                .withLock {
                    if (unusableConfig != null && playerConfigCache[useLoginCookies] === unusableConfig) {
                        playerConfigCache.remove(useLoginCookies)
                    }
                    getCachedPlayerConfigLocked(useLoginCookies, Clock.System.now().toEpochMilliseconds())
                }?.let { return@withLock it }
            val expectedIdentity = configIdentity(useLoginCookies)
            val config = configParser.fetchConfig(videoId, useLoginCookies)
            currentExtractionSession(requestSession)
            playerConfigCacheMutex.withLock {
                if (configIdentity(useLoginCookies) != expectedIdentity) {
                    throw CancellationException("InnerTube session changed")
                }
                cachePlayerConfig(useLoginCookies, config, expectedIdentity)
            }
        }

    private suspend fun getCachedPlayerConfig(
        useLoginCookies: Boolean,
        nowMs: Long,
    ): CachedPlayerConfig? =
        playerConfigCacheMutex.withLock { getCachedPlayerConfigLocked(useLoginCookies, nowMs) }

    private fun getCachedPlayerConfigLocked(
        useLoginCookies: Boolean,
        nowMs: Long,
    ): CachedPlayerConfig? {
        val cached = playerConfigCache[useLoginCookies] ?: return null
        if (cached.identity != configIdentity(useLoginCookies)) {
            playerConfigCache.remove(useLoginCookies)
            return null
        }
        if (nowMs - cached.cachedAtMs <= PLAYER_CONFIG_CACHE_TTL_MS) return cached

        playerConfigCache.remove(useLoginCookies)
        return null
    }

    private fun cachePlayerConfig(
        useLoginCookies: Boolean,
        config: PlayerConfig,
        identity: ConfigIdentity,
    ): CachedPlayerConfig =
        CachedPlayerConfig(
            config = config,
            cachedAtMs = Clock.System.now().toEpochMilliseconds(),
            identity = identity,
            usedLoginCookies = useLoginCookies,
        ).also { playerConfigCache[useLoginCookies] = it }

    private suspend fun extractWithConfig(
        videoId: String,
        hints: ContentHints,
        excludedClients: Set<String>,
        clientPlaybackNonce: String,
        playerConfig: PlayerConfig,
        allowCipherProcessing: Boolean = true,
        audioQuality: AudioQuality = AudioQuality.AUTO,
        diagnostics: ExtractionDiagnostics,
    ): ExtractedStream? {
        diagnostics.ensureSessionCurrent()
        if (diagnostics.requestBudget.remaining <= 0) return null
        val batch =
            clientDirector.fetchPlayerResponses(
                videoId = videoId,
                playerConfig = playerConfig,
                hints = hints,
                excludedClients = excludedClients,
                acceptCipherOnlyResponse = allowCipherProcessing,
                directAudioOnlyClients = !allowCipherProcessing,
                premiumHighQuality = hints.premium && audioQuality == AudioQuality.HIGH,
                requestBudget = diagnostics.requestBudget,
            )
        diagnostics.ensureSessionCurrent()
        if (!batch.onlyBootstrapIndependentFailures) diagnostics.nonGatePasses++
        if (batch.onlyUnavailableFailures) diagnostics.unavailable = true
        diagnostics.failures += batch.failures
        diagnostics.requestFailures += batch.requestFailures
        diagnostics.attempts += batch.attempts
        val results = batch.playableResponses
        if (results.isNotEmpty()) diagnostics.sawPlayableResponse = true
        if (results.isEmpty()) {
            return null
        }

        for (result in results) {
            val response = result.response
            val requireBoundedRange = requiresBoundedMediaRange(result.clientName)
            val streamingData = response.streamingData
            if (streamingData == null) {
                continue
            }

            val allFormats =
                (streamingData.formats ?: emptyList()) +
                    streamingData.adaptiveFormats
            val directAudioItags =
                allFormats
                    .asSequence()
                    .filter { it.width == null && !it.url.isNullOrBlank() }
                    .map { it.itag }
                    .toSet()


            val directAudioFormats =
                allFormats.filter {
                    it.width == null &&
                        !it.url.isNullOrBlank() &&
                        (it.itag in directAudioItags)
                }
            val directFastPathCandidate = selectBestAudioFormat(directAudioFormats, audioQuality)
            val bestAvailableAudioFormat =
                selectBestAudioFormat(
                    allFormats.filter { format ->
                        format.isAudio &&
                            (
                                !format.url.isNullOrBlank() ||
                                    !format.signatureCipher.isNullOrBlank() ||
                                    !format.cipher.isNullOrBlank()
                            )
                    },
                    audioQuality,
                    requireUrl = false,
                )
            val directAudioIsBest = directFastPathCandidate === bestAvailableAudioFormat
            if (directFastPathCandidate != null && directAudioIsBest) {
                val directUrl =
                    appendClientPlaybackNonce(
                        directFastPathCandidate.url.orEmpty().withPoToken(result.streamingDataPoToken),
                        clientPlaybackNonce,
                    )
                if (!isAllowedMediaUrl(directUrl)) continue
                // Fast path: if the selected URL is already playback-ready, skip cipher/ejs work.
                if (!directUrl.hasNParameter()) {
                    val directHeaders =
                        buildHeaders(
                            diagnostics,
                            result.clientName,
                            result.userAgent,
                            directUrl,
                            hints.isUploaded == true,
                        )
                    val contentLength =
                        resolveBoundedContentLength(
                            directFastPathCandidate.contentLength,
                            directUrl,
                            directHeaders,
                            requireBoundedRange,
                            diagnostics.requestBudget,
                        )
                    if (requireBoundedRange && contentLength == null) {
                        continue
                    }

                    return ExtractedStream(
                        audioUrl = directUrl,
                        loudnessDb = response.playerConfig?.audioConfig?.loudnessDb ?: directFastPathCandidate.loudnessDb,
                        mimeType = directFastPathCandidate.mimeType.substringBefore(";").trim(),
                        bitrate = directFastPathCandidate.bitrate,
                        profileId = result.profileId,
                    ).also { diagnostics.ensureSessionCurrent() }
                }
            }

            if (!allowCipherProcessing) {
                continue
            }
            val rawAudioFormats = allFormats.filter { it.width == null }
            val preferredRawAudioFormat = selectBestAudioFormat(rawAudioFormats, audioQuality, requireUrl = false)
            val audioFormatsForCipher = preferredRawAudioFormat?.let { listOf(it) } ?: rawAudioFormats
            var processedFormats =
                cipherService
                    .processFormats(
                        playerUrl = playerConfig.playerUrl,
                        formats = audioFormatsForCipher,
                    ).also { diagnostics.ensureSessionCurrent() }

            var audioFormats = processedFormats.filter(PlayerResponse.StreamingData.Format::isAudio)
            var usableAudioFormats = audioFormats.filter { !it.url.isNullOrBlank() }
            var directUrlAudioFormats = usableAudioFormats.filter { it.itag in directAudioItags }
            var selectionPool =
                directUrlAudioFormats.ifEmpty { usableAudioFormats }
            var audioFormat = selectBestAudioFormat(selectionPool, audioQuality)

            if (audioFormat == null && audioFormatsForCipher.size != rawAudioFormats.size) {
                processedFormats =
                    cipherService
                        .processFormats(
                            playerUrl = playerConfig.playerUrl,
                            formats = rawAudioFormats,
                        ).also { diagnostics.ensureSessionCurrent() }
                audioFormats = processedFormats
                usableAudioFormats = audioFormats.filter { !it.url.isNullOrBlank() }
                directUrlAudioFormats = usableAudioFormats.filter { it.itag in directAudioItags }
                selectionPool =
                    directUrlAudioFormats.ifEmpty { usableAudioFormats }
                audioFormat = selectBestAudioFormat(selectionPool, audioQuality)
            }

            if (audioFormat == null) {
                continue
            }

            val url =
                audioFormat.url
                    ?.withPoToken(result.streamingDataPoToken)
                    ?.let { appendClientPlaybackNonce(it, clientPlaybackNonce) }
            if (url.isNullOrBlank()) {
                continue
            }
            if (!isAllowedMediaUrl(url)) continue
            val directHeaders =
                buildHeaders(
                    diagnostics,
                    result.clientName,
                    result.userAgent,
                    url,
                    hints.isUploaded == true,
                )
            val contentLength =
                resolveBoundedContentLength(
                    audioFormat.contentLength,
                    url,
                    directHeaders,
                    requireBoundedRange,
                    diagnostics.requestBudget,
                )
            if (requireBoundedRange && contentLength == null) {
                continue
            }

            return ExtractedStream(
                audioUrl = url,
                loudnessDb = response.playerConfig?.audioConfig?.loudnessDb ?: audioFormat.loudnessDb,
                mimeType = audioFormat.mimeType.substringBefore(";").trim(),
                bitrate = audioFormat.bitrate,
                profileId = result.profileId,
            ).also { diagnostics.ensureSessionCurrent() }
        }

        val failedProfiles =
            results
                .map { result -> result.profileId }
                .filter(String::isNotBlank)
                .toSet()
        val nextExcludedClients = excludedClients + failedProfiles
        if (nextExcludedClients.size == excludedClients.size) return null
        return extractWithConfig(
            videoId = videoId,
            hints = hints,
            excludedClients = nextExcludedClients,
            clientPlaybackNonce = clientPlaybackNonce,
            playerConfig = playerConfig,
            allowCipherProcessing = allowCipherProcessing,
            audioQuality = audioQuality,
            diagnostics = diagnostics,
        )
    }

    private inner class ExtractionDiagnostics(
        maxPlayerRequests: Int,
    ) {
        var session = innerTube.sessionSnapshot()
            private set

        fun ensureSessionCurrent() {
            session = currentExtractionSession(session)
        }

        val requestBudget = PlayerRequestBudget(maxPlayerRequests, extractionTimeBudgetMs.milliseconds)
        val failures = mutableListOf<PlayabilityFailure>()
        val requestFailures = mutableListOf<Throwable>()
        val attempts = mutableListOf<StreamAttemptDiagnostic>()
        var nonGatePasses = 0
        var unavailable = false
        var sawPlayableResponse = false
        var usedAuthenticatedWatchPage = false
        var triedPublicConfigBeforeLogin = false

        fun snapshot() =
            StreamDiagnostics(
                attempts = attempts.takeLast(32),
                usedAuthenticatedWatchPage = usedAuthenticatedWatchPage,
            )
    }

    private fun String.hasNParameter(): Boolean = N_PARAMETER_REGEX.containsMatchIn(this)

    private suspend fun resolveBoundedContentLength(
        contentLength: Long?,
        url: String,
        headers: Map<String, String>,
        requireBoundedRange: Boolean,
        requestBudget: PlayerRequestBudget,
    ): Long? {
        contentLength?.takeIf { it > 0L }?.let { return it }
        if (!requireBoundedRange) return null
        val timeout = requestBudget.timeLeftOrNull(8.seconds) ?: return null
        return withTimeoutOrNull(timeout) {
            innerTube.mediaContentLength(url, headers)
        }
    }

    private fun buildHeaders(
        diagnostics: ExtractionDiagnostics,
        clientName: String,
        userAgent: String,
        mediaUrl: String? = null,
        includeLoginCookies: Boolean = false,
    ): Map<String, String> {
        diagnostics.ensureSessionCurrent()
        val session = diagnostics.session
        val headers = linkedMapOf<String, String>()
        if (clientName != "ANDROID_VR" && clientName != "VISIONOS" && clientName != "TVHTML5_SIMPLY") {
            headers["User-Agent"] = userAgent
            headers["Accept"] = "*/*"
            headers["Accept-Language"] = session.locale.acceptLanguageHeader()

            when (clientName) {
                "WEB_REMIX" -> {
                    headers["Referer"] = "https://music.youtube.com/"
                    headers["Origin"] = "https://music.youtube.com"
                }

                "MWEB" -> {
                    headers["Referer"] = "https://m.youtube.com/"
                    headers["Origin"] = "https://m.youtube.com"
                }

                "WEB_CREATOR" -> {
                    headers["Referer"] = "https://studio.youtube.com/"
                    headers["Origin"] = "https://studio.youtube.com"
                }

                "WEB",
                "WEB_EMBEDDED_PLAYER",
                -> {
                    headers["Referer"] = "https://www.youtube.com/"
                    headers["Origin"] = "https://www.youtube.com"
                }
            }
        }
        if (includeLoginCookies && mediaUrl?.let(::isAllowedMediaUrl) == true) {
            session.cookie
                ?.takeIf(String::isNotBlank)
                ?.let { headers["Cookie"] = it }
        }
        return headers
    }

    private fun String.withPoToken(poToken: String?): String {
        if (poToken.isNullOrBlank() || contains("&pot=") || contains("?pot=")) return this
        val fragmentStart = indexOf('#').takeIf { it >= 0 } ?: length
        val separator = if (indexOf('?').let { it >= 0 && it < fragmentStart }) "&" else "?"
        return buildString(length + poToken.length + 6) {
            append(this@withPoToken, 0, fragmentStart)
            append(separator)
            append("pot=")
            append(encodeQueryComponent(poToken))
            append(this@withPoToken, fragmentStart, this@withPoToken.length)
        }
    }

    private fun isAllowedMediaUrl(value: String): Boolean =
        runCatching { Url(value) }.getOrNull()?.let {
            it.protocol == URLProtocol.HTTPS &&
                it.port == 443 &&
                (
                    it.host == "googlevideo.com" || it.host.endsWith(".googlevideo.com") ||
                        it.host == "youtube.com" || it.host.endsWith(".youtube.com")
                ) &&
                it.encodedPath == "/videoplayback" && it.user == null && it.password == null
        } == true
}

internal fun requiresBoundedMediaRange(clientName: String): Boolean =
    clientName == "ANDROID_VR" || clientName == "IOS" || clientName == "TVHTML5_SIMPLY"
