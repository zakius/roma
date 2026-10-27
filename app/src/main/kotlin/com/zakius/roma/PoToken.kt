package com.zakius.roma

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * PoTokens for InnerTubeX's web clients (WEB_REMIX and others), minted by YouTube's BotGuard in a hidden WebView.
 * Without them those clients' streams stop after about 1 MB. Ported from Metrolist (GPL-3.0,
 * app/.../utils/potoken at 0dd3d747), which took it from NewPipe; assets/po_token.html is Metrolist's page.
 *
 * The streaming token is minted once per visitor data, a player token per video. Tokens and cookies are never
 * logged or stored.
 */
class PoTokens(context: Context) : TokenProvider {
    private val app = context.applicationContext
    private val lock = Mutex()
    private var view: PoTokenView? = null
    private var visitor: String? = null
    private var streamingToken: String? = null
    @Volatile private var broken = false // the system WebView can't run the page; don't try again this run

    override val capabilities = TokenProviderCapabilities(providers = setOf(PoTokenProviderKind.WEB_BOTGUARD), usesWebView = true)

    override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? {
        if (broken) return null
        return try {
            withTimeout(TIMEOUT_MS) { mint(videoId, visitorData, retried = false) }
        } catch (_: TimeoutCancellationException) {
            reset() // the WebView process may have been killed; the next song starts over
            null
        } catch (_: BadWebViewException) {
            broken = true
            null
        }
    }

    private suspend fun mint(videoId: String, visitorData: String, retried: Boolean): PoTokenResult {
        val (v, streaming, fresh) = lock.withLock {
            val current = view
            val fresh = retried || current == null || current.expired || current.dead || visitor != visitorData
            if (fresh) {
                withContext(Dispatchers.Main) { current?.close() }
                view = null; streamingToken = null; visitor = null
                val created = PoTokenView.create(app)
                // The streaming token must be minted before any player token.
                val token = try { created.mint(visitorData) } catch (t: Throwable) { created.close(); throw t }
                view = created; streamingToken = token; visitor = visitorData
            }
            Triple(view!!, streamingToken!!, fresh)
        }
        val player = try {
            v.mint(videoId)
        } catch (t: Throwable) {
            if (fresh) throw t
            return mint(videoId, visitorData, retried = true) // the page may be gone after the app was in the background
        }
        return PoTokenResult(playerRequestToken = streaming, streamingDataToken = player, visitorData = visitorData)
    }

    private suspend fun reset() = lock.withLock {
        withContext(Dispatchers.Main) { view?.close() }
        view = null; streamingToken = null; visitor = null
    }

    override suspend fun close() = reset()

    private companion object {
        // A cold start (WebView, BotGuard, first token) takes 2-5 s; past this the song falls back to other clients.
        const val TIMEOUT_MS = 10_000L
    }
}

private class PoTokenException(message: String) : Exception(message)
private class BadWebViewException(message: String) : Exception(message)

/** One BotGuard session in a WebView: created with [create], then [mint] per identifier until [expired]. */
private class PoTokenView private constructor(context: Context, private val ready: Continuation<PoTokenView>) {
    private val webView = WebView(context)
    private val main = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false) // [ready] resumed
    private val pending = ConcurrentHashMap<String, Continuation<String>>()
    private val counter = AtomicLong()
    private var expiresAt = Long.MAX_VALUE
    @Volatile private var closed = false
    @Volatile var dead = false
        private set
    val expired get() = System.currentTimeMillis() > expiresAt

    init {
        @Suppress("SetJavaScriptEnabled")
        webView.settings.javaScriptEnabled = true
        webView.settings.userAgentString = USER_AGENT
        webView.settings.blockNetworkLoads = true // BotGuard's requests go through OkHttp below
        webView.addJavascriptInterface(this, JS)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if ("Uncaught" in m.message()) {
                    // Before the page is up, a broken WebView; after, a transient error in Google's own script.
                    fail(if (started.get()) PoTokenException(m.message()) else BadWebViewException(m.message()))
                }
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail(PoTokenException("WebView render process gone"))
                return true // keep the app alive
            }
        }
    }

    private fun load() {
        val html = webView.context.assets.open("po_token.html").bufferedReader().use { it.readText() }
        val page = html.replaceFirst("</script>", "\n$JS.downloadAndRunBotguard()</script>")
        webView.loadDataWithBaseURL("https://www.youtube.com", page, "text/html", "utf-8", null)
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() = botguard("https://www.youtube.com/api/jnn/v1/Create", "[ \"$REQUEST_KEY\" ]") { body ->
        val challenge = parseChallenge(body)
        js("""try {
            data = $challenge
            runBotGuard(data).then(function (result) {
                this.webPoSignalOutput = result.webPoSignalOutput
                $JS.onRunBotguardResult(result.botguardResponse)
            }, function (error) { $JS.onJsInitializationError(error + "\n" + error.stack) })
        } catch (error) { $JS.onJsInitializationError(error + "\n" + error.stack) }""")
    }

    @JavascriptInterface
    fun onRunBotguardResult(response: String) = botguard("https://www.youtube.com/api/jnn/v1/GenerateIT", "[ \"$REQUEST_KEY\", \"$response\" ]") { body ->
        val data = Json.parseToJsonElement(body).jsonArray
        val integrity = u8Array(base64(data[0].jsonPrimitive.content))
        expiresAt = System.currentTimeMillis() + (data[1].jsonPrimitive.long - 600) * 1000 // 10 minutes' margin
        js("""try {
            this.integrityToken = $integrity
            createPoTokenMinter(webPoSignalOutput, integrityToken).then(function() { $JS.onMinterCreated() })
                .catch(function(error) { $JS.onJsInitializationError(error + "\n" + (error.stack || '')) })
        } catch (error) { $JS.onJsInitializationError(error + "\n" + error.stack) }""")
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) = fail(if ("SyntaxError" in error) BadWebViewException(error) else PoTokenException(error))

    @JavascriptInterface
    fun onMinterCreated() {
        if (started.compareAndSet(false, true)) ready.resume(this)
    }

    suspend fun mint(identifier: String): String {
        if (dead || closed) throw PoTokenException("PoToken WebView is gone")
        val key = "$identifier#${counter.incrementAndGet()}" // concurrent calls for one video keep their own answer
        return try {
            withTimeout(MINT_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        pending[key] = cont
                        js("""(function() {
                            var key = "$key"
                            try {
                                obtainPoToken(${u8Array(identifier.toByteArray())}).then(function(u8) { $JS.onToken(key, u8.join(",")) })
                                    .catch(function(error) { $JS.onTokenError(key, error + "\n" + (error.stack || '')) })
                            } catch (error) { $JS.onTokenError(key, error + "\n" + error.stack) }
                        })()""")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            dead = true
            pending.remove(key)
            throw PoTokenException("PoToken timed out")
        }
    }

    @JavascriptInterface
    fun onToken(key: String, u8: String) {
        val cont = pending.remove(key) ?: return
        runCatching {
            Base64.getEncoder().encodeToString(u8.split(",").map { it.toInt().toByte() }.toByteArray()).replace('+', '-').replace('/', '_')
        }.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
    }

    @JavascriptInterface
    fun onTokenError(key: String, error: String) {
        pending.remove(key)?.resumeWithException(PoTokenException(error))
    }

    private fun js(code: String) = main.post { if (!closed) webView.evaluateJavascript(code, null) }

    private fun botguard(url: String, body: String, then: (String) -> Unit) {
        Thread {
            try {
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json",
                    "Content-Type" to "application/json+protobuf",
                    "x-goog-api-key" to API_KEY,
                    "x-user-agent" to "grpc-web-javascript/0.1",
                )
                val (code, text) = roma.core.httpCall(url, body.toByteArray(), headers)
                if (code !in 200..299 || text.isEmpty()) throw PoTokenException("BotGuard request failed: $url")
                then(text)
            } catch (e: Exception) {
                fail(e)
            }
        }.start()
    }

    private fun fail(e: Exception) {
        dead = true
        close()
        if (started.compareAndSet(false, true)) runCatching { ready.resumeWithException(e) }
        pending.keys.toList().forEach { k -> pending.remove(k)?.let { runCatching { it.resumeWithException(e) } } }
    }

    fun close() {
        if (closed) return
        closed = true
        main.post { runCatching { webView.loadUrl("about:blank"); webView.destroy() } }
    }

    companion object {
        private const val JS = "PoTokenWebView"
        private const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val INIT_TIMEOUT_MS = 30_000L
        private const val MINT_TIMEOUT_MS = 5_000L

        suspend fun create(context: Context): PoTokenView {
            var created: PoTokenView? = null
            try {
                return withTimeout(INIT_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine { cont -> created = PoTokenView(context, cont).also { it.load() } }
                    }
                }
            } catch (e: Exception) {
                created?.let { it.started.set(true); withContext(Dispatchers.Main) { it.close() } }
                throw if (e is TimeoutCancellationException) PoTokenException("PoToken WebView didn't start") else e
            }
        }

        /** The Create answer, maybe scrambled (base64, each byte minus 97), as the object runBotGuard takes. */
        private fun parseChallenge(raw: String): String {
            val outer = Json.parseToJsonElement(raw).jsonArray
            val c = if (outer.size > 1 && (outer[1] as? JsonPrimitive)?.isString == true) {
                Json.parseToJsonElement(base64(outer[1].jsonPrimitive.content).map { (it + 97).toByte() }.toByteArray().decodeToString()).jsonArray
            } else {
                outer[0].jsonArray
            }
            fun firstString(i: Int) = (c[i] as? JsonArray)?.firstOrNull { (it as? JsonPrimitive)?.isString == true } ?: JsonNull
            return JsonObject(
                mapOf(
                    "messageId" to JsonPrimitive(c[0].jsonPrimitive.content),
                    "interpreterJavascript" to JsonObject(
                        mapOf(
                            "privateDoNotAccessOrElseSafeScriptWrappedValue" to firstString(1),
                            "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue" to firstString(2),
                        ),
                    ),
                    "interpreterHash" to JsonPrimitive(c[3].jsonPrimitive.content),
                    "program" to JsonPrimitive(c[4].jsonPrimitive.content),
                    "globalName" to JsonPrimitive(c[5].jsonPrimitive.content),
                    "clientExperimentsStateBlob" to JsonPrimitive(c[7].jsonPrimitive.content),
                ),
            ).toString()
        }

        // YouTube's base64 variant: URL-safe, '.' for padding.
        private fun base64(s: String): ByteArray = Base64.getDecoder().decode(s.replace('-', '+').replace('_', '/').replace('.', '='))

        private fun u8Array(bytes: ByteArray) = "new Uint8Array([" + bytes.joinToString(",") { (it.toInt() and 0xff).toString() } + "])"
    }
}
