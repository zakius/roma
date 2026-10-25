package com.zakius.roma

import android.app.Application
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.serviceLoaderEnabled
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.metrolist.innertube.YouTube
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import roma.core.Bans
import roma.core.Icu
import roma.core.Item
import roma.core.LyricsProvider
import roma.core.LyricsProviders
import roma.core.Override
import roma.core.RadioSettings
import roma.core.RomanizeSettings
import roma.core.Romanizer
import roma.core.Song
import roma.core.again
import roma.core.matchDistance
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.concurrent.thread

class App : Application(), coil3.SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Dictionary.init(this)
        Playback.init(this)
        Startup.later {
            thread(name = "sudachi") {
                // Below the UI threads, so loading the dictionary doesn't slow them.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val t0 = System.nanoTime()
                Store.romanizer.warmUp()
                android.util.Log.i("roma", "Sudachi dictionary ready in ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
            thread(name = "images") { coil3.SingletonImageLoader.get(this) } // built before the first picture needs it
        }
    }

    // Coil asks the application for its image loader when the first picture needs it, so start-up registers nothing.
    // The network fetcher is named here instead of found by a ServiceLoader scan of the APK.
    override fun newImageLoader(context: coil3.PlatformContext) =
        coil3.ImageLoader.Builder(context).serviceLoaderEnabled(false).components { add(OkHttpNetworkFetcherFactory()) }.build()
}

/** Work the first frame doesn't need waits until it is drawn, so it doesn't compete with it for the CPU or the main thread. */
object Startup {
    private var started = false
    private val later = ArrayList<() -> Unit>()

    /** Runs [f] once roma's first frame is drawn (or now, if it was). Main thread only. */
    fun later(f: () -> Unit) { if (started) f() else later += f }

    /** The first frame is drawn, or the media service started without a screen: the deferred work runs now. */
    fun go() {
        if (started) return
        started = true
        later.forEach { it() }
        later.clear()
    }
}

@Serializable
class Banned(val kind: String, val id: String, val name: String)

/**
 * Settings and everything the app keeps on disk. Compose reads [version] to redraw after a change, and [dataVersion]
 * to load pages again: only signing in or out and romanization changes alter what a page holds. A ban or a display
 * setting must not send every open page back to the network.
 */
object Store {
    lateinit var prefs: SharedPreferences
    /** Data the user would lose: settings, sign-ins, corrections, the queue, unsent scrobbles. */
    lateinit var files: File
    /** Everything that can be fetched or rebuilt again, so Android may clear it: dictionary, index, likes, pages, streams. */
    lateinit var cache: File
    private lateinit var pages: File
    val json = Json { ignoreUnknownKeys = true }
    var version by mutableIntStateOf(0)
        private set
    var dataVersion by mutableIntStateOf(0)
        private set

    val icu = Icu { id, text -> android.icu.text.Transliterator.getInstance(id).transliterate(text) }
    lateinit var context: Context

    // The dictionary is its own app (Dictionary.kt), stored uncompressed, so it is memory-mapped in place from that
    // APK and stays out of the heap. Nothing is unpacked. While it is missing, or Japanese is in the scripts the user
    // reads (then nothing would show the romaji), Japanese gets kana only and the dictionary isn't loaded.
    val romanizer = Romanizer(icu) {
        try {
            check("ja" !in readScripts) { "Japanese is read as written" }
            val fd = Dictionary.assets(context).openFd("system_core.dic")
            fd.createInputStream().channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length) }.order(ByteOrder.LITTLE_ENDIAN)
                .also {
                    // Names romanized without it, in this run or an earlier one, are redone.
                    if (prefs.getBoolean("dict.failed", false)) {
                        prefs.edit().remove("dict.failed").apply()
                        android.os.Handler(android.os.Looper.getMainLooper()).post { Playback.romanizationChanged(); dataChanged() }
                    }
                }
        } catch (e: Exception) {
            prefs.edit().putBoolean("dict.failed", true).apply()
            throw e
        }
    }

    fun init(c: Context) {
        prefs = c.getSharedPreferences("roma", Context.MODE_PRIVATE)
        context = c.applicationContext
        files = c.filesDir
        cache = c.cacheDir
        pages = File(c.cacheDir, "pages")
        // Up to 0.1.26 these sat in filesDir and the mixes in prefs. Copied, not renamed: a renamed file keeps its
        // group, and Android counts a file as cache by its group. The dictionary unpacked by 0.1.27 and earlier goes.
        // Off the main thread, before the index and the liked songs are read from the cache folder.
        indexThread.execute {
            listOf(files, cache).flatMap { it.listFiles().orEmpty().toList() }.filter { it.name.startsWith("sudachi") }.forEach { it.delete() }
            for (n in listOf("index.json", "liked.json", "streams.txt")) File(files, n).takeIf { it.exists() }?.let { runCatching { it.copyTo(File(cache, n), true) }; it.delete() }
            prefs.edit().remove("home.mixes").apply()
        }
        romanizer.settings = romanizeSettings()
        romanizer.loadOverrides(indexThread.submit<List<Override>> { loadOverrides() })
        cookie = prefs.getString("cookie", null)
        YouTube.cookie = cookie
        // InnerTubeX sends the account cookies on browse and next calls (home, radio) only with this on; without it
        // Home is the anonymous one and never has the personal mixes. Metrolist turns it on too.
        YouTube.useLoginForBrowse = true
        prefs.getString("visitorData", null)?.let { YouTube.visitorData = it }
        prefs.getString("dataSyncId", null)?.let { YouTube.dataSyncId = it }
        Startup.later { indexThread.execute { index.addAll(runCatching { json.decodeFromString<List<Song>>(File(cache, "index.json").readText()) }.getOrDefault(emptyList())) } }
    }

    // Reading version subscribes the calling composable, so it redraws when set() changes a setting.
    // SharedPreferences alone isn't observable, and skipped composables (Switch1, Options) never saw the change.
    fun str(key: String, def: String): String { version; return prefs.getString(key, def) ?: def }
    fun bool(key: String, def: Boolean): Boolean { version; return prefs.getBoolean(key, def) }

    fun set(key: String, value: Any?) {
        prefs.edit().apply {
            when (value) {
                null -> remove(key)
                is Boolean -> putBoolean(key, value)
                else -> putString(key, value.toString())
            }
        }.apply()
        if (key.startsWith("rom.")) {
            romanizer.settings = romanizeSettings()
            Playback.romanizationChanged()
        }
        if (key == READ_KEY) {
            Playback.romanizationChanged() // the notification's title follows
            romanizer.retryDictionary() // Japanese read as written doesn't load the dictionary; romanized again, it does
        }
        if (key == "cookie") cookie = value as String?
        if (key.startsWith("rom.") || key == "cookie") dataChanged()
        version++
        if (key == "play.ahead" && value == true && Playback.player.isPlaying) Playback.downloadAhead()
    }

    /** The account's cookie, as saved. YouTube's own copy isn't read on the main thread: that would build its client. */
    @Volatile var cookie: String? = null
        private set
    val signedIn get() = cookie?.contains("SAPISID") == true

    /** Signed out, continuation pages come back empty unless every request carries the same visitor data. */
    suspend fun ensureVisitorData() {
        if (YouTube.visitorData != null) return
        val v = YouTube.visitorData().getOrNull() ?: return
        YouTube.visitorData = v
        prefs.edit().putString("visitorData", v).apply() // not set(): that bumps version and reloads every page
    }

    /** The pinned row's last known mixes, so it isn't empty while Home loads. Null before the first lookup. */
    var mixes: List<Item>?
        get() = runCatching { json.decodeFromString<List<Item>>(File(cache, "mixes.json").readText()) }.getOrNull()
        set(v) { runCatching { File(cache, "mixes.json").writeText(json.encodeToString(v)) } }

    // ---- Pages on disk: the last copy of Home, the library tabs and pages, shown at once on the next start ----

    // Pages are read, written and dropped on their own thread, in order, so a read never sees a dropped page.
    private val pageThread = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun pageFile(key: String) = File(pages, key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    /** Blocking: call it off the main thread. */
    fun <T> readPage(key: String, serializer: KSerializer<T>): T? =
        pageThread.submit<T?> { runCatching { json.decodeFromString(serializer, pageFile(key).readText()) }.getOrNull() }.get()

    fun <T> writePage(key: String, value: T, serializer: KSerializer<T>) = pageThread.execute {
        pages.mkdirs()
        pageFile(key).writeText(json.encodeToString(serializer, value))
    }

    /** What pages hold changed (account, romanization): the disk copies go, and open pages load again. */
    private fun dataChanged() {
        pageThread.execute { pages.listFiles()?.forEach { it.delete() } }
        dataVersion++
    }

    // ---- Romanization ----

    /** Changes with every romanization setting or correction change; it keys the kept romanized lyrics. */
    val romanizationVersion get() = prefs.getInt("rom.cacheVersion", 0)
    fun bumpRomanizationVersion() = prefs.edit().putInt("rom.cacheVersion", romanizationVersion + 1).apply()

    private fun romanizeSettings() = RomanizeSettings(
        japaneseMacrons = bool("rom.macrons", true),
        chineseTones = bool("rom.tones", true),
        cyrillic = str("rom.cyrillic", "english"),
        greek = str("rom.greek", "classical"),
        hanDefault = str("rom.han", "ja"),
    )

    private val overridesFile get() = File(files, "overrides.json")

    private fun loadOverrides(): List<Override> =
        runCatching { json.decodeFromString<List<Override>>(overridesFile.readText()) }.getOrDefault(emptyList())

    /** "Don't romanize": a correction whose Latin form is the original. Saving it again removes it. */
    fun keptOriginal(scope: String, id: String?, original: String) =
        romanizer.overrides.any { it.scope == scope && it.id == id && it.original == original && it.latin == original }

    fun toggleKeepOriginal(scope: String, id: String, original: String) =
        saveOverride(Override(scope, id, original, if (keptOriginal(scope, id, original)) "" else original))

    fun saveOverride(o: Override) {
        val list = romanizer.overrides.filterNot { it.scope == o.scope && it.id == o.id && it.original == o.original }
        setOverrides(if (o.latin.isBlank()) list else list + o)
    }

    /** The reading the user gave this word: this song's own first, then the everywhere one. Null if none. */
    fun wordOverride(word: String, songId: String?): Override? =
        romanizer.overrides.firstOrNull { it.scope == "word" && it.id == songId && it.original == word }
            ?: romanizer.overrides.firstOrNull { it.scope == "word" && it.id == "" && it.original == word }

    /**
     * A word's reading, in one song or everywhere; a blank latin removes it. Saving it everywhere also
     * drops this song's own reading of the word, which would otherwise still win here.
     */
    fun saveWord(word: String, songId: String, everywhere: Boolean, latin: String) {
        val list = romanizer.overrides.filterNot { it.scope == "word" && it.original == word && (it.id == songId || everywhere && it.id == "") }
        setOverrides(if (latin.isBlank()) list else list + Override("word", if (everywhere) "" else songId, word, latin.trim()))
    }

    fun setOverrides(list: List<Override>) {
        romanizer.overrides = list
        overridesFile.writeText(json.encodeToString(list))
        Playback.romanizationChanged()
        dataChanged()
        version++
    }

    fun exportOverrides(): String = overridesFile.takeIf { it.exists() }?.readText() ?: "[]"

    // ---- Display ----

    /** Scripts the user reads ("ja", "zh", "ko", "cyrillic", "greek", "other"): text only in those shows as written. */
    val readScripts: Set<String> get() = str(READ_KEY, "").split(',').filter { it.isNotEmpty() }.toSet()

    fun reads(original: String, siblings: List<String> = emptyList()): Boolean {
        val read = readScripts
        return read.isNotEmpty() && romanizer.scripts(original, siblings).let { it.isNotEmpty() && read.containsAll(it) }
    }

    /** The form a single-line surface (notification, menu) shows: the original in a script the user reads, else Latin. */
    fun shown(t: roma.core.Text) = if (reads(t.original)) t.original else t.latin

    const val READ_KEY = "display.read"

    /** Japanese romanized again needs the dictionary app: its install starts here if it is missing. */
    fun setReads(script: String, read: Boolean) {
        val r = readScripts
        set(READ_KEY, (if (read) r + script else r - script).joinToString(","))
        if (script == "ja" && !read) Dictionary.install(context)
    }

    /** "romanized", "both", "ruby" or "original". */
    val listMode get() = str("display.list", "both")
    val playerMode get() = str("display.player", "both")

    // ---- Bans ----

    var banned: List<Banned>
        get() = runCatching { json.decodeFromString<List<Banned>>(str("bans", "[]")) }.getOrDefault(emptyList())
        set(v) = set("bans", json.encodeToString(v))

    fun bans() = banned.let { b -> Bans(b.filter { it.kind == "song" }.map { it.id }.toSet(), b.filter { it.kind == "artist" }.map { it.id }.toSet()) }

    fun ban(kind: String, id: String, name: String) {
        banned = banned.filterNot { it.kind == kind && it.id == id } + Banned(kind, id, name)
    }

    fun unban(kind: String, id: String) {
        banned = banned.filterNot { it.kind == kind && it.id == id }
    }

    // ---- Radio and queue ----

    fun radioSettings() = RadioSettings(
        leash = str("radio.leash", "free"),
        preferAudio = bool("radio.preferAudio", true),
        hideVersions = bool("radio.hideVersions", true),
        skipBanned = bool("radio.skipBanned", true),
    )

    val stopAtEnd get() = bool("queue.stopAtEnd", false)

    // ---- Lyrics ----

    /** Providers in the user's order. */
    fun lyricsOrder(): List<LyricsProvider> {
        val ids = str("lyrics.order", LyricsProviders.all.joinToString(",") { it.id }).split(",")
        val known = ids.mapNotNull { id -> LyricsProviders.all.firstOrNull { it.id == id } }
        return known + LyricsProviders.all.filter { it !in known }
    }

    fun lyricsEnabled(p: LyricsProvider) = bool("lyrics.on.${p.id}", true)

    /** Whether a translation in this language is shown: the chosen language, or by default the device's, then English. */
    fun translationWanted(lang: String?): Boolean {
        val set = str("lyrics.translationLang", "device")
        return lang != null && lang in (if (set == "device") listOf(java.util.Locale.getDefault().language, "en") else listOf(set))
    }

    // ---- Local index: every song that entered the app, searchable by its Latin form ----

    // The index is read, changed and saved only on its own thread, so a tap never waits for it: with 5000 songs, loading
    // took about 100 ms of app start and each save about 15 ms. Saves that queue up behind each other are written once.
    private val index = ArrayList<Song>()
    private val indexThread = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var saveQueued = false

    /** Blocking: call it off the main thread. */
    fun anySong(): Song? = indexThread.submit<Song?> { index.firstOrNull() }.get() // the newest: old ones may be gone

    fun remember(songs: List<Song>) {
        if (songs.isEmpty()) return
        indexThread.execute {
            val ids = songs.map { it.id }.toSet()
            index.removeAll { it.id in ids }
            index.addAll(0, songs)
            while (index.size > 5000) index.removeAt(index.size - 1)
            if (!saveQueued) {
                saveQueued = true
                indexThread.execute {
                    saveQueued = false
                    File(cache, "index.json").writeText(json.encodeToString(index.toList()))
                }
            }
        }
    }

    /** Blocking: call it off the main thread. The stored Latin forms date from when each song was seen; the hits are
     *  romanized again so they follow today's rules. */
    fun searchLocal(q: String): List<Song> = indexThread.submit<List<Song>> {
        index.mapNotNull { s -> matchDistance(s, q)?.let { s to it } }.sortedBy { it.second }.take(20).map { it.first }
    }.get().map { romanizer.again(it) }
}
