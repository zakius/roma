package com.zakius.roma

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.metrolist.innertube.YouTube
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import roma.core.Bans
import roma.core.Icu
import roma.core.LyricsProvider
import roma.core.LyricsProviders
import roma.core.Override
import roma.core.RadioSettings
import roma.core.RomanizeSettings
import roma.core.Romanizer
import roma.core.Song
import roma.core.matches
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Playback.init(this)
    }
}

@Serializable
class Banned(val kind: String, val id: String, val name: String)

/** Settings and everything the app keeps on disk. Compose reads [version] to redraw after a change. */
object Store {
    lateinit var prefs: SharedPreferences
    lateinit var files: File
    val json = Json { ignoreUnknownKeys = true }
    var version by mutableIntStateOf(0)
        private set

    val romanizer = Romanizer(Icu { id, text -> android.icu.text.Transliterator.getInstance(id).transliterate(text) })

    fun init(c: Context) {
        prefs = c.getSharedPreferences("roma", Context.MODE_PRIVATE)
        files = c.filesDir
        romanizer.settings = romanizeSettings()
        romanizer.overrides = loadOverrides()
        YouTube.cookie = prefs.getString("cookie", null)
        prefs.getString("visitorData", null)?.let { YouTube.visitorData = it }
        prefs.getString("dataSyncId", null)?.let { YouTube.dataSyncId = it }
        index.addAll(runCatching { json.decodeFromString<List<Song>>(File(files, "index.json").readText()) }.getOrDefault(emptyList()))
    }

    fun str(key: String, def: String) = prefs.getString(key, def) ?: def
    fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)

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
        version++
    }

    val signedIn get() = YouTube.cookie?.contains("SAPISID") == true

    // ---- Romanization ----

    private fun romanizeSettings() = RomanizeSettings(
        japaneseMacrons = bool("rom.macrons", true),
        chineseTones = bool("rom.tones", true),
        cyrillic = str("rom.cyrillic", "english"),
        hanDefault = str("rom.han", "ja"),
    )

    private val overridesFile get() = File(files, "overrides.json")

    private fun loadOverrides(): List<Override> =
        runCatching { json.decodeFromString<List<Override>>(overridesFile.readText()) }.getOrDefault(emptyList())

    fun saveOverride(o: Override) {
        val list = romanizer.overrides.filterNot { it.scope == o.scope && it.id == o.id && it.original == o.original }
        setOverrides(if (o.latin.isBlank()) list else list + o)
    }

    fun setOverrides(list: List<Override>) {
        romanizer.overrides = list
        overridesFile.writeText(json.encodeToString(list))
        Playback.romanizationChanged()
        version++
    }

    fun exportOverrides(): String = overridesFile.takeIf { it.exists() }?.readText() ?: "[]"

    // ---- Display ----

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
        leash = str("radio.leash", "medium"),
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

    // ---- Local index: every song that entered the app, searchable by its Latin form ----

    private val index = ArrayList<Song>()

    fun remember(songs: List<Song>) {
        if (songs.isEmpty()) return
        val ids = songs.map { it.id }.toSet()
        synchronized(index) {
            index.removeAll { it.id in ids }
            index.addAll(0, songs)
            while (index.size > 5000) index.removeAt(index.size - 1)
            File(files, "index.json").writeText(json.encodeToString(index.toList()))
        }
    }

    fun searchLocal(q: String): List<Song> = synchronized(index) { index.filter { matches(it, q) }.take(20) }
}
