package com.zakius.roma

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Chromium for what needs a real browser on the desktop: BotGuard's PoTokens and the YouTube sign-in, the jobs
 * Android's WebView does. It is the system's Chromium-family browser, started with roma's own profile (never the
 * user's) and driven over the DevTools protocol: the one that worked last time first, else every known browser in
 * [order] (oganesson first), else CEF, downloaded once on first need (jcefmaven, about 150 MB). Other engines can't
 * be used: BotGuard's tokens from JavaFX's WebKit are refused.
 */
object Browser {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private const val WORKED = "browser.worked"

    /** The Chromium-family browsers roma looks for, in order. Paths that don't exist are skipped. */
    fun order(): List<String> = if (windows) {
        val local = System.getenv("LOCALAPPDATA") ?: ""
        val pf = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val pf86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
        listOf(
            "$local\\oganesson\\Application\\chrome.exe", "$pf\\oganesson\\Application\\chrome.exe",
            "$local\\imput\\Helium\\Application\\chrome.exe",
            "$local\\Chromium\\Application\\chrome.exe",
            "$pf\\Google\\Chrome\\Application\\chrome.exe", "$pf86\\Google\\Chrome\\Application\\chrome.exe", "$local\\Google\\Chrome\\Application\\chrome.exe",
            "$pf\\BraveSoftware\\Brave-Browser\\Application\\brave.exe", "$local\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
            "$local\\Vivaldi\\Application\\vivaldi.exe", "$pf\\Vivaldi\\Application\\vivaldi.exe",
            "$local\\Programs\\Opera\\opera.exe", "$local\\Programs\\Opera GX\\opera.exe",
            "$pf86\\Microsoft\\Edge\\Application\\msedge.exe", "$pf\\Microsoft\\Edge\\Application\\msedge.exe",
        )
    } else {
        val path = (System.getenv("PATH") ?: "").split(':') + listOf("/usr/bin", "/usr/local/bin", "/opt/google/chrome")
        listOf(
            "oganesson", "helium", "chromium", "chromium-browser", "google-chrome-stable", "google-chrome", "chrome",
            "brave-browser", "brave", "vivaldi-stable", "vivaldi", "opera", "microsoft-edge-stable", "microsoft-edge",
        ).flatMap { name -> path.map { "$it/$name" } }.filter { File(it).canExecute() }.distinctBy { File(it).canonicalPath }
    }

    /**
     * A browser for [profile], [headless] or in a window: the one that worked last time, else the first in [order]
     * that starts and connects, else CEF. The one that worked is remembered. [test] runs on it before it counts as
     * working (BotGuard's first token); a failing browser is closed and the next one tried.
     */
    suspend fun <T> start(profile: String, headless: Boolean, test: suspend (Chrome) -> T): Pair<Chrome, T> {
        val last = Store.str(WORKED, "")
        val found = order().filter { File(it).canExecute() }
        val tries = (listOf(last).filter { it in found } + found.filter { it != last }).distinct()
        for (exe in tries + "cef") {
            val chrome = runCatching { if (exe == "cef") Cef.start() else Chrome.launch(exe, profileDir(profile), headless) }
                .onFailure { println("roma browser: $exe didn't start: ${it.message}") }.getOrNull() ?: continue
            val result = runCatching { test(chrome) }
            if (result.isSuccess) {
                if (exe != last) postMain { Store.set(WORKED, exe) }
                return chrome to result.getOrThrow()
            }
            println("roma browser: $exe failed: ${result.exceptionOrNull()}") // which browser, for the log; no tokens
            chrome.close()
        }
        throw BotGuardBroken("No Chromium-family browser worked, and CEF couldn't start")
    }

    fun profileDir(name: String) = File(Dirs.files, "browser/$name")
}

/**
 * One Chromium over the DevTools protocol: a browser process roma started ([launch]) or CEF ([Cef]). [page] is its
 * first tab.
 */
class Chrome(private val process: Process?, private val cdp: Cdp, private val onClose: () -> Unit = {}) : AutoCloseable {
    /** The first page target, attached; for CEF the browser it created, for a launched browser its first tab. */
    suspend fun page(urlPrefix: String = ""): Tab {
        // A browser just started (CEF's window) may not have its page yet.
        var t: JsonObject? = null
        for (i in 0 until 100) {
            val targets = cdp.call("Target.getTargets")["targetInfos"]!!.jsonArray.map { it.jsonObject }
            t = targets.firstOrNull { it.str("type") == "page" && it.str("url").startsWith(urlPrefix) } ?: run { delay(100); null } ?: continue
            break
        }
        t ?: error("No page")
        val session = cdp.call("Target.attachToTarget", buildJsonObject { put("targetId", t.str("targetId")); put("flatten", true) }).str("sessionId")
        return Tab(cdp, session, t.str("url"))
    }

    /** The URLs of the open pages. */
    suspend fun urls(): List<String> =
        cdp.call("Target.getTargets")["targetInfos"]!!.jsonArray.map { it.jsonObject }.filter { it.str("type") == "page" }.map { it.str("url") }

    val alive get() = cdp.open && (process?.isAlive ?: true)

    override fun close() {
        runCatching { cdp.close() }
        process?.let { p -> p.descendants().forEach { it.destroy() }; p.destroy() }
        onClose()
    }

    companion object {
        /** Starts [exe] with [profile] as its user data and connects; the port is read from DevToolsActivePort. */
        suspend fun launch(exe: String, profile: File, headless: Boolean): Chrome = withContext(Dispatchers.IO) {
            profile.mkdirs()
            val portFile = File(profile, "DevToolsActivePort").also { it.delete() }
            val args = mutableListOf(exe, "--remote-debugging-port=0", "--user-data-dir=${profile.path}", "--no-first-run", "--no-default-browser-check")
            if (headless) args += "--headless=new"
            args += "about:blank"
            val p = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            running += p
            try {
                var waited = 0
                while (!portFile.exists() || portFile.readLines().size < 2) {
                    if (!p.isAlive || waited > 10_000) error("$exe didn't open its DevTools port")
                    delay(100); waited += 100
                }
                val (port, path) = portFile.readLines()
                Chrome(p, Cdp.connect("ws://127.0.0.1:$port$path"))
            } catch (t: Throwable) {
                p.destroy()
                throw t
            }
        }

        fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

        // The browsers roma started go when roma does.
        private val running = java.util.Collections.newSetFromMap(ConcurrentHashMap<Process, Boolean>())
            .also { set -> Runtime.getRuntime().addShutdownHook(Thread { set.forEach { p -> p.descendants().forEach { it.destroy() }; p.destroy() } }) }
    }
}

/** A tab of a [Chrome], attached through a flat session. */
class Tab(private val cdp: Cdp, private val session: String, val url: String) {
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())) = cdp.call(method, params, session)

    /** Evaluates [expression] (a promise is awaited) and returns its value, as JSON. Throws on a JavaScript error. */
    suspend fun eval(expression: String): JsonElement? {
        val r = call("Runtime.evaluate", buildJsonObject { put("expression", expression); put("awaitPromise", true); put("returnByValue", true) })
        r["exceptionDetails"]?.let { throw IllegalStateException("JavaScript: " + (it.jsonObject["exception"]?.jsonObject?.get("description") ?: it)) }
        return r["result"]?.jsonObject?.get("value")
    }

    /**
     * Opens [url] with [html] as its page, served by roma, not fetched: the page gets [url]'s origin (BotGuard wants
     * youtube.com's) without a request leaving the machine.
     */
    suspend fun serve(url: String, html: String) {
        call("Fetch.enable", buildJsonObject { put("patterns", buildJsonArray { add(buildJsonObject { put("urlPattern", url) }) }) })
        val served = CompletableFuture<Unit>()
        cdp.on("Fetch.requestPaused", session) { e ->
            val body = java.util.Base64.getEncoder().encodeToString(html.toByteArray())
            cdp.send("Fetch.fulfillRequest", buildJsonObject {
                put("requestId", e.str("requestId")); put("responseCode", 200); put("body", body)
                put("responseHeaders", buildJsonArray { add(buildJsonObject { put("name", "Content-Type"); put("value", "text/html; charset=utf-8") }) })
            }, session)
            served.complete(Unit)
        }
        call("Page.navigate", buildJsonObject { put("url", url) })
        served.await()
        while (eval("document.readyState")?.jsonPrimitive?.contentOrNull != "complete") delay(50)
    }

    /** The cookies the browser would send to [url], as a Cookie header. */
    suspend fun cookies(url: String): String =
        call("Network.getCookies", buildJsonObject { put("urls", buildJsonArray { add(JsonPrimitive(url)) }) })["cookies"]!!.jsonArray
            .joinToString("; ") { c -> c.jsonObject.let { "${it.str("name")}=${it.str("value")}" } }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
}

/** The DevTools protocol over the JDK's WebSocket: calls by id, events by method (and session). */
class Cdp private constructor(private val ws: WebSocket, private val listener: Listener) : AutoCloseable {
    private val ids = AtomicInteger()
    val open get() = !listener.closed

    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), session: String? = null): JsonObject =
        send(method, params, session).await()

    fun send(method: String, params: JsonObject, session: String? = null): CompletableFuture<JsonObject> {
        val id = ids.incrementAndGet()
        val f = CompletableFuture<JsonObject>()
        listener.pending[id] = f
        val msg = buildJsonObject { put("id", id); put("method", method); put("params", params); session?.let { put("sessionId", it) } }
        synchronized(ws) { ws.sendText(msg.toString(), true).join() }
        if (listener.closed) f.completeExceptionally(IllegalStateException("DevTools connection closed"))
        return f
    }

    fun on(method: String, session: String?, handler: (JsonObject) -> Unit) { listener.events["$method $session"] = handler }

    override fun close() { runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "").join() } }

    private class Listener : WebSocket.Listener {
        val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
        val events = ConcurrentHashMap<String, (JsonObject) -> Unit>()
        @Volatile var closed = false
        private val text = StringBuilder()

        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) {
                val msg = Json.parseToJsonElement(text.toString()).jsonObject
                text.setLength(0)
                val id = (msg["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                if (id != null) {
                    val f = pending.remove(id)
                    msg["error"]?.let { f?.completeExceptionally(IllegalStateException("DevTools: $it")) } ?: f?.complete(msg["result"]?.jsonObject ?: JsonObject(emptyMap()))
                } else {
                    val method = (msg["method"] as? JsonPrimitive)?.contentOrNull
                    val session = (msg["sessionId"] as? JsonPrimitive)?.contentOrNull
                    events["$method $session"]?.let { h -> Thread { h(msg["params"]!!.jsonObject) }.start() }
                }
            }
            ws.request(1)
            return null
        }

        override fun onClose(ws: WebSocket, status: Int, reason: String?): CompletionStage<*>? { gone(); return null }
        override fun onError(ws: WebSocket, error: Throwable) = gone()

        private fun gone() {
            closed = true
            pending.values.forEach { it.completeExceptionally(IllegalStateException("DevTools connection closed")) }
            pending.clear()
        }
    }

    companion object {
        suspend fun connect(url: String): Cdp {
            val listener = Listener()
            val ws = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI(url), listener).await()
            return Cdp(ws, listener)
        }
    }
}

/**
 * CEF, the last resort: downloaded into roma's data folder the first time it is needed (jcefmaven), and run in its
 * own process, roma started again with --cef-host ([cefHost]): CEF must start before anything in the process opens
 * an AWT or X11 window, and roma's window is long open by then. roma drives it over DevTools like any other browser.
 */
object Cef {
    val installDir get() = File(Dirs.files, "cef")
    private val installed get() = File(installDir, "install.lock").exists()
    /** What the download is doing, for the sign-in page; null when nothing happens. */
    @Volatile var status: String? = null
        private set
    @Volatile private var installing = false

    /** Blocking: downloads CEF unless it is there. */
    fun install() {
        if (installed) return
        val builder = me.friwi.jcefmaven.CefAppBuilder()
        builder.setInstallDir(installDir)
        builder.setProgressHandler { state, percent ->
            status = if (state == me.friwi.jcefmaven.EnumProgress.DOWNLOADING && percent >= 0) "Downloading Chromium (CEF)… ${percent.toInt()}%" else null
        }
        builder.install()
        status = null
    }

    /**
     * CEF in its own process: a hidden page, or a window showing [url]. Before CEF is there, a hidden page fails at
     * once and the download starts in the background: a stream lookup can't wait for 150 MB, so that song goes
     * without a token. A window (sign-in) waits for the download.
     */
    suspend fun start(url: String? = null): Chrome = withContext(Dispatchers.IO) {
        if (!installed) {
            if (url == null) {
                if (!installing) { installing = true; Thread({ runCatching { install() }; installing = false }, "cef install").apply { isDaemon = true }.start() }
                error("CEF is downloading")
            }
            install()
        }
        val self = ProcessHandle.current().info().command().get()
        // From a plain JVM (a run from Gradle) the class path and main class come along; a packaged roma is its launcher.
        val cmd = if (File(self).name.startsWith("java")) listOf(self) + jvmArgs + listOf("-cp", System.getProperty("java.class.path"), "com.zakius.roma.MainKt") else listOf(self)
        val p = ProcessBuilder(cmd + listOfNotNull("--cef-host", url)).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            // jcefmaven prints a line of its own first.
            val out = p.inputStream.bufferedReader()
            // A host that says nothing for 20 s is ended, which ends the read.
            val watchdog = Thread { runCatching { Thread.sleep(20_000); p.destroy() } }.apply { isDaemon = true; start() }
            val line = generateSequence { out.readLine() }.firstOrNull { it.startsWith("DevTools port ") } ?: error("CEF didn't start")
            watchdog.interrupt()
            val port = line.removePrefix("DevTools port ").toInt()
            val json = URI("http://127.0.0.1:$port/json/version").toURL().readText()
            Chrome(p, Cdp.connect(Json.parseToJsonElement(json).jsonObject["webSocketDebuggerUrl"]!!.jsonPrimitive.content))
        } catch (t: Throwable) {
            p.destroy()
            throw t
        }
    }

    private val jvmArgs get() = java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments.filter { it.startsWith("--add-") || it.startsWith("-Dcompose") }
}

/**
 * roma --cef-host [url]: the CEF process (see [Cef]). CEF starts first (jcefmaven's build, before any window); then a hidden page, or a window showing
 * [url]; the DevTools port goes to stdout, and the process ends when roma closes its stdin or the window closes.
 */
fun cefHost(url: String?) {
    val port = java.net.ServerSocket(0).use { it.localPort }
    val builder = me.friwi.jcefmaven.CefAppBuilder()
    builder.setInstallDir(Cef.installDir)
    // JCEF draws only through X11 on Linux: on a Wayland desktop, XWayland.
    if (!System.getProperty("os.name").startsWith("Windows")) builder.addJcefArgs("--ozone-platform=x11")
    // The hidden page renders off-screen (no window); the sign-in page is a normal window.
    builder.cefSettings.windowless_rendering_enabled = url == null
    builder.cefSettings.remote_debugging_port = port
    builder.cefSettings.root_cache_path = Browser.profileDir("cef").path
    val app = builder.build()
    val browser = app.createClient().createBrowser(url ?: "about:blank", url == null, false)
    if (url == null) browser.createImmediately()
    else javax.swing.SwingUtilities.invokeLater {
        javax.swing.JFrame("Sign in to YouTube Music").apply {
            contentPane.add(browser.uiComponent)
            setSize(480, 720)
            defaultCloseOperation = javax.swing.WindowConstants.EXIT_ON_CLOSE
            isVisible = true
        }
    }
    println("DevTools port $port")
    System.out.flush()
    while (System.`in`.read() >= 0) Unit // roma closed it, or went
    app.dispose()
    kotlin.system.exitProcess(0)
}

/**
 * BotGuard in a hidden Chromium page: po_token.html served at youtube.com's origin, then the same steps as Android's
 * WebView (PoToken.kt), the page's functions called through [Tab.eval]. Closed after 5 idle minutes, so the
 * browser doesn't stay in memory; the next song needing a token starts it again (about 2 s).
 */
class ChromeBotGuard private constructor(private val chrome: Chrome, private val page: Tab, private val expiresAt: Long) : BotGuardSession {
    @Volatile private var closed = false
    @Volatile private var used = System.currentTimeMillis()
    override val expired get() = System.currentTimeMillis() > expiresAt
    override val dead get() = closed || !chrome.alive

    init {
        Thread({
            while (!closed) {
                Thread.sleep(30_000)
                if (System.currentTimeMillis() - used > 5 * 60_000) close()
            }
        }, "botguard idle").apply { isDaemon = true }.start()
    }

    override suspend fun mint(identifier: String): String {
        used = System.currentTimeMillis()
        val u8 = page.eval("obtainPoToken(${BotGuard.u8Array(identifier.toByteArray())}).then(u => Array.from(u).join(','))")
        return BotGuard.token(u8!!.jsonPrimitive.content)
    }

    override fun close() {
        if (closed) return
        closed = true
        chrome.close()
    }

    companion object {
        private val html by lazy { ChromeBotGuard::class.java.getResource("/po_token.html")!!.readText() }

        /** A session in the first browser that can run BotGuard (Browser.start); its first step is the test. */
        suspend fun start(): BotGuardSession {
            return Browser.start("botguard", headless = true) { chrome -> open(chrome) }.second
        }

        internal suspend fun open(chrome: Chrome): ChromeBotGuard {
            val page = chrome.page()
            page.serve("https://www.youtube.com/roma-botguard", html)
            val challenge = withContext(Dispatchers.IO) {
                BotGuard.parseChallenge(BotGuard.request("https://www.youtube.com/api/jnn/v1/Create", "[ \"${BotGuard.REQUEST_KEY}\" ]"))
            }
            val response = page.eval("runBotGuard($challenge).then(r => { window.webPoSignalOutput = r.webPoSignalOutput; return r.botguardResponse })")!!.jsonPrimitive.content
            val (integrity, expires) = withContext(Dispatchers.IO) {
                BotGuard.integrity(BotGuard.request("https://www.youtube.com/api/jnn/v1/GenerateIT", "[ \"${BotGuard.REQUEST_KEY}\", \"$response\" ]"))
            }
            page.eval("createPoTokenMinter(window.webPoSignalOutput, $integrity).then(() => '')")
            return ChromeBotGuard(chrome, page, expires)
        }
    }
}

/**
 * The YouTube Music sign-in, in a browser window with roma's own sign-in profile: when the window reaches
 * music.youtube.com with the account's cookies, they are taken (as Android's WebView sign-in does) and the window
 * closes. Returns false when the window was closed first.
 */
suspend fun browserSignIn(): Boolean {
    val url = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
    val chrome = Browser.order().filter { File(it).canExecute() }.let { found ->
        val last = Store.str("browser.worked", "")
        (listOf(last).filter { it in found } + found.filter { it != last }).firstNotNullOfOrNull { exe ->
            runCatching { Chrome.launch(exe, Browser.profileDir("signin"), headless = false) }.getOrNull()
        }
    }?.also { it.page().call("Page.navigate", buildJsonObject { put("url", url) }) } ?: Cef.start(url)
    try {
        while (chrome.alive) {
            delay(1_000)
            if (chrome.urls().none { it.startsWith("https://music.youtube.com") }) continue
            val page = chrome.page("https://music.youtube.com")
            val cookie = page.cookies("https://music.youtube.com")
            if ("SAPISID" !in cookie) continue
            val ids = page.eval("[window.yt && yt.config_ ? yt.config_.VISITOR_DATA : null, window.yt && yt.config_ ? yt.config_.DATASYNC_ID : null]")?.jsonArray
            fun id(i: Int) = ids?.getOrNull(i)?.jsonPrimitive?.contentOrNull
            withContext(Dispatchers.Main) { signedIn(cookie, id(0), id(1)) }
            return true
        }
        return false
    } catch (_: IllegalStateException) {
        return false // the window was closed
    } finally {
        chrome.close()
    }
}
