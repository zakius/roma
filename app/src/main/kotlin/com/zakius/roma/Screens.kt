package com.zakius.roma

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import roma.core.Item
import roma.core.LastFm
import roma.core.Override
import roma.core.Romanizer
import roma.core.Song
import roma.core.isPersonalMix
import roma.core.item
import roma.core.personalMixes
import roma.core.song

// ---------------------------------------------------------------- Shared pieces

/** A name in the chosen display mode: romanized, both (Latin, original small underneath), ruby (reading above) or original. */
@Composable
fun Name(t: roma.core.Text, mode: String, style: TextStyle = MaterialTheme.typography.bodyLarge, center: Boolean = false, maxLines: Int = 2) {
    val align = if (center) TextAlign.Center else TextAlign.Start
    val small = MaterialTheme.typography.bodySmall
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val mod = if (center) Modifier.fillMaxWidth() else Modifier
    when {
        t.isLatin || Store.reads(t.original) -> Text(t.original, mod, style = style, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        mode == "romanized" -> Text(t.latin, mod, style = style, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        mode == "original" -> Text(t.original, mod, style = style, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        mode == "ruby" -> Column(mod) {
            Text(t.latin, Modifier.fillMaxWidth(), style = small, color = dim, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.original, Modifier.fillMaxWidth(), style = style, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        else -> Column(mod) {
            Text(t.latin, Modifier.fillMaxWidth(), style = style, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.original, Modifier.fillMaxWidth(), style = small, color = dim, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Google-hosted art at the size it is drawn: innertube hands over the largest thumbnail (often 544 px or more,
 * 80–115 KB), which a 48–128 dp image doesn't need. YouTube video stills come in fixed sizes and stay as they are.
 */
@Composable
fun shownThumb(url: String?, size: Dp): String? {
    val px = with(LocalDensity.current) { size.roundToPx() }
    if (url == null || "googleusercontent.com/" !in url) return url
    return url.replace(SIZE_WH, "=w$px-h$px").replace(SIZE_S, "=s$px")
}

// Compiled once: shownThumb runs for every row drawn while a list scrolls.
private val SIZE_WH = Regex("=w\\d+-h\\d+")
private val SIZE_S = Regex("=s\\d+")

@Composable
fun SongRow(s: Song, highlight: Boolean = false, extra: String? = null, onMenu: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().lookUpOnPress(s).clickable(onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(shownThumb(s.thumb, 48.dp), null, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val style = if (highlight) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) else MaterialTheme.typography.bodyLarge
            Name(s.title, Store.listMode, style)
            val sub = s.artistLatin + (extra?.let { " · $it" } ?: "")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, "Song menu") }
    }
}

@Composable
fun ItemRow(item: Item, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().lookUpOnPress(item.playable).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(shownThumb(item.thumb, 56.dp), null, Modifier.size(56.dp).clip(if (item.kind == "artist") CircleShape else RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Name(item.title, Store.listMode)
            if (item.subtitle.latin.isNotBlank()) Text(item.subtitle.latin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun Card(item: Item, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).lookUpOnPress(item.playable).clickable(onClick = onClick).padding(6.dp)) {
        AsyncImage(shownThumb(item.thumb, 128.dp), null, Modifier.size(128.dp).clip(if (item.kind == "artist") CircleShape else RoundedCornerShape(6.dp)))
        Spacer(Modifier.height(4.dp))
        Name(item.title, Store.listMode, MaterialTheme.typography.bodyMedium)
        if (item.subtitle.latin.isNotBlank()) Text(item.subtitle.latin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The song a tap on this card or row plays; null when the tap opens a page. */
private val Item.playable get() = song?.takeIf { kind == "song" }

/** Starts the song's stream lookup when a finger lands on its row, so it is under way before the tap is recognized. */
private fun Modifier.lookUpOnPress(s: Song?) = if (s == null) this else pointerInput(s.id) {
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false); Playback.touched(s) }
}

/** A song plays alone and YouTube's autoplay follows; anything else opens its page. */
fun openItem(item: Item) {
    val s = item.playable
    if (s != null) {
        Playback.play(listOf(s))
        Nav.tab = TAB_PLAYER
    } else Nav.open(Page(item.kind, item.id))
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
/** The ⋯ menu of a song: the same items in the same order everywhere; unavailable ones are dimmed. */
@Composable
fun SongMenu(s: Song, queueIndex: Int?, onClose: () -> Unit) {
    var fixing by remember { mutableStateOf(false) }
    val inQueue = queueIndex != null && queueIndex != Playback.player.currentMediaItemIndex
    val liked = s.id in Playback.liked
    val context = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Fully open at once: half open, the bans at the bottom would need a drag first.
    ModalBottomSheet(onDismissRequest = onClose, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Name(s.title, "both", MaterialTheme.typography.titleMedium)
                Text(s.artistLatin, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            MenuItem("Play next", true) { Playback.playNext(s); onClose() }
            MenuItem("Add to queue", true) { Playback.addToQueue(s); onClose() }
            MenuItem("Start radio", true) { Playback.startRadio(s); Nav.tab = TAB_PLAYER; onClose() }
            MenuItem(if (liked) "Unlike" else "Like", Store.signedIn) { Playback.toggleLike(s); onClose() }
            MenuItem("Go to artist", s.firstArtistId != null) { Nav.open(Page("artist", s.firstArtistId!!)); onClose() }
            MenuItem("Go to album", s.album?.id != null) { Nav.open(Page("album", s.album!!.id!!)); onClose() }
            MenuItem("Correct romanization…", true) { fixing = true }
            val titleKept = Store.keptOriginal("song", s.id, s.title.original)
            MenuItem(if (titleKept) "Romanize title" else "Don't romanize title", titleKept || !s.title.isLatin) {
                Store.toggleKeepOriginal("song", s.id, s.title.original); onClose()
            }
            val artist = s.artists.firstOrNull { it.id != null }
            val artistKept = artist != null && Store.keptOriginal("artist", artist.id, artist.name.original)
            MenuItem(if (artistKept) "Romanize artist" else "Don't romanize artist", artist != null && (artistKept || !artist.name.isLatin)) {
                Store.toggleKeepOriginal("artist", artist!!.id!!, artist.name.original); onClose()
            }
            MenuItem("Remove from queue", inQueue) { Playback.remove(queueIndex!!); onClose() }
            MenuItem("Ban song", true) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject)
                if (Playback.current()?.song?.id == s.id) Playback.never() // bans it, skips it and offers Undo
                else { Store.ban("song", s.id, s.title.latin); Toast.makeText(context, "Banned ${s.title.latin}", Toast.LENGTH_SHORT).show() }
                onClose()
            }
            MenuItem("Ban artist", s.firstArtistId != null) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject)
                val a = s.artists.first { it.id != null }
                Store.ban("artist", a.id!!, a.name.latin)
                Toast.makeText(context, "Banned ${a.name.latin}", Toast.LENGTH_SHORT).show()
                onClose()
            }
        }
    }
    if (fixing) CorrectDialog(s) { fixing = false; onClose() }
}

@Composable
private fun MenuItem(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.35f).padding(horizontal = 24.dp, vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
}

/**
 * Correct the Latin form of the song's title, an artist or the album, for this string, this song or this artist; or,
 * through the field's words underneath, one word's reading.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CorrectDialog(s: Song, onDone: () -> Unit) {
    val fields = listOf(s.title) + s.artists.map { it.name } + listOfNotNull(s.album?.name)
    var field by remember { mutableIntStateOf(0) }
    var scope by remember { mutableStateOf("song") }
    var latin by remember(field) { mutableStateOf(fields[field].latin) }
    var word by remember { mutableStateOf<String?>(null) }
    word?.let { w -> return WordDialog(w, null, s.id, onDone) }
    // The field's words, each a button that opens its reading: only when there is more than one to choose from.
    val words = remember(field) {
        val f = fields[field]
        if (f.isLatin) emptyList() else Store.romanizer.pieces(f.original, fields.map { it.original }).map { it.trim() }.filter { Romanizer.hasNonLatin(it) }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Correct romanization") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                fields.forEachIndexed { i, t -> Choice(t.original, field == i) { field = i } }
                if (words.size > 1) {
                    Text("Or one word's reading:", style = MaterialTheme.typography.bodySmall)
                    androidx.compose.foundation.layout.FlowRow { words.forEach { w -> TextButton(onClick = { word = w }) { Text(w) } } }
                }
                HorizontalDivider()
                Choice("Only this song", scope == "song") { scope = "song" }
                Choice("This artist's songs", scope == "artist") { scope = "artist" }
                Choice("Everywhere", scope == "text") { scope = "text" }
                OutlinedTextField(latin, { latin = it }, label = { Text("Latin form (empty: automatic)") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = when (scope) { "song" -> s.id; "artist" -> s.firstArtistId ?: ""; else -> "" }
                Store.saveOverride(Override(scope, id, fields[field].original, latin.trim()))
                onDone()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val id = when (scope) { "song" -> s.id; "artist" -> s.firstArtistId ?: ""; else -> "" }
                    Store.saveOverride(Override(scope, id, fields[field].original, fields[field].original))
                    onDone()
                }) { Text("Keep original") }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        },
    )
}

/**
 * A word's reading: what the dictionary says (kana, romaji, dictionary form, part of speech), and the user's own
 * reading for this song or everywhere. [inContext] is the romaji the word has where it was picked; it fills the field.
 * The scope last used is offered first, so a run of fixes during a song needs no extra tap.
 */
@Composable
fun WordDialog(word: String, inContext: String?, songId: String, onDone: () -> Unit) {
    val existing = Store.wordOverride(word, songId)
    val info = remember(word) { runCatching { Store.romanizer.lookUp(word) }.getOrDefault(emptyList()) }
    var everywhere by remember { mutableStateOf(existing?.let { it.id == "" } ?: Store.bool("fix.everywhere", false)) }
    // In context the word may carry the line's capital ("Kimi" first in a line); the reading itself doesn't.
    var latin by remember { mutableStateOf(existing?.latin ?: (inContext ?: Store.romanizer.machine(word, sentence = true, songId = songId)).let { if (it.getOrNull(1)?.isUpperCase() == true) it else it.replaceFirstChar(Char::lowercase) }) }
    fun save(value: String) {
        Store.set("fix.everywhere", everywhere)
        Store.saveWord(word, songId, everywhere, value)
        onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(word) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val dim = MaterialTheme.colorScheme.onSurfaceVariant
                info.forEach { t ->
                    val base = if (t.dictionaryForm != t.surface) " · from ${t.dictionaryForm} (${t.dictionaryRomaji})" else ""
                    Text("${t.surface}  ${t.kana} · ${t.romaji} · ${t.partOfSpeech}$base", style = MaterialTheme.typography.bodySmall, color = dim)
                }
                OutlinedTextField(latin, { latin = it }, Modifier.padding(top = 8.dp), singleLine = true, label = { Text("Reading (empty: automatic)") })
                Choice("In this song", !everywhere) { everywhere = false }
                Choice("Everywhere: every song, title and line", everywhere) { everywhere = true }
            }
        },
        confirmButton = { TextButton(onClick = { save(latin) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { save(word) }) { Text("Keep original") }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, onDone: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(value); onDone() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label)
    }
}

/** A loaded page, when it was fetched, and with which [Store.dataVersion]. */
private class Kept(val value: Any, val at: Long, val dataVersion: Int)

// The last results by key, so going back to a page or a tab shows it at once (with its scroll position, see Root).
// ponytail: 20 entries, enough for a few tabs' back stacks; the disk keeps pages beyond that.
private val loaded = object : LinkedHashMap<String, Kept>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept>) = size > 20
}

/**
 * Loads a page and shows [loading] (a spinner), the error, or the content.
 *
 * A page loaded before shows at once: from memory, else (with [disk]) from the copy kept across starts. It loads
 * again behind it unless it was fetched in the last 10 minutes with today's [Store.dataVersion], and a failed load
 * keeps the older copy on screen. [rows] lists what each item of the page's LazyColumn ([list]) shows, by index;
 * with it, the new copy takes over only while the items on screen are the same in both, so nothing moves under the
 * finger. Until then it waits for the user to scroll, and the next visit shows it. [load] may hand over its first
 * pages through `partial` while it fetches the rest (they only add rows at the bottom); they show when nothing else
 * is shown yet.
 */
@Composable
private fun <T : Any> Loader(
    key: String,
    load: suspend (partial: suspend (T) -> Unit) -> T,
    list: LazyListState? = null,
    rows: ((T) -> List<Any?>)? = null,
    disk: KSerializer<T>? = null,
    loading: @Composable () -> Unit = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
    content: @Composable (T) -> Unit,
) {
    @Suppress("UNCHECKED_CAST")
    var shown by remember(key) { mutableStateOf(loaded[key]?.value as T?) }
    var failed by remember(key) { mutableStateOf<String?>(null) }
    var attempt by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, attempt, Store.dataVersion) {
        failed = null
        val kept = loaded[key]
        if (attempt == 0 && kept != null && kept.dataVersion == Store.dataVersion && System.currentTimeMillis() - kept.at < 10 * 60_000) return@LaunchedEffect
        if (shown == null && disk != null) shown = withContext(Dispatchers.IO) { Store.readPage(key, disk) }
        var partial = false
        val result = withContext(Dispatchers.IO) {
            runCatching { load { p -> withContext(Dispatchers.Main) { if (shown == null || partial) { shown = p; partial = true } } } }
        }
        val fresh = result.getOrElse { if (shown == null || partial) failed = plainError(it); return@LaunchedEffect }
        loaded[key] = Kept(fresh, System.currentTimeMillis(), Store.dataVersion)
        if (disk != null) Store.writePage(key, fresh, disk)
        val old = shown
        if (old != null && !partial && rows != null && list != null) {
            val a = rows(old)
            val b = rows(fresh)
            snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.index } }.first { on -> on.all { a.getOrNull(it) == b.getOrNull(it) } }
        }
        shown = fresh
    }
    val f = failed
    val r = shown
    when {
        f != null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Couldn't load this page.", textAlign = TextAlign.Center)
            Text(f, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            TextButton(onClick = { attempt++ }) { Text("Retry") }
        }
        r == null -> loading()
        else -> content(r)
    }
}

@Composable
private fun Header(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
}

// ---------------------------------------------------------------- Home

@Serializable
class Section(val title: roma.core.Text, val items: List<Item>)

private const val MIX_PAGES = 10

/**
 * Home: the pinned "Your mixes" row, then YouTube's shelves. Signed in, the first [MIX_PAGES] pages load at once
 * to collect the mixes; after that a page loads when the user nears the end.
 *
 * The state lives here rather than in the screen, so going back to Home shows it at once, where it was, and the
 * first page starts loading before the screen is drawn ([start] runs in MainActivity.onCreate). The last Home is
 * kept on disk and shows at once on the next start. It loads again after 30 minutes or a romanization change, and
 * the fresh shelves never move what is on screen: a shelf gets its new contents only if they hold the same items or
 * the shelf is off-screen (otherwise once it scrolls off), new shelves go at the bottom, and nothing is reordered or
 * removed. The mixes row follows the same rule. Signing in or out starts Home over.
 */
object Home {
    var sections by mutableStateOf(listOf<Section>())
        private set
    var mixes by mutableStateOf(Store.mixes)
        private set
    var more by mutableStateOf(true)
        private set
    var failed by mutableStateOf<String?>(null)
        private set
    val list = LazyListState()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var dataVersion = -1
    private var account: String? = null
    private var fetchedAt = 0L
    // Where paging stands; kept across Retry so it carries on from the failed page.
    private var cont: String? = null
    private var page = 0
    private val found = ArrayList<Item>()
    private var looking = false
    // This run's shelves: what the disk copy becomes.
    private val fresh = ArrayList<Section>()
    // New contents for shelves that were on screen, by shelf index, and for the mixes row.
    private val waiting = HashMap<Int, Section>()
    private var waitingMixes: List<Item>? = null

    /** Loads Home unless it is loading or fresh. Call it whenever Home may be shown. */
    fun start() {
        val stale = System.currentTimeMillis() - fetchedAt > 30 * 60_000 || dataVersion != Store.dataVersion
        if (job != null && failed == null && !stale) return
        dataVersion = Store.dataVersion
        if (account != YouTube.cookie) {
            account = YouTube.cookie
            if (job != null) {
                sections = emptyList()
                mixes = Store.mixes
                waiting.clear()
                waitingMixes = null
            }
        }
        if (failed == null) {
            cont = null
            page = 0
            found.clear()
            fresh.clear()
            more = true
            looking = Store.signedIn
        }
        failed = null
        fetchedAt = System.currentTimeMillis()
        job?.cancel()
        job = scope.launch { load() }
    }

    private suspend fun load() = coroutineScope {
        if (sections.isEmpty()) launch {
            val disk = withContext(Dispatchers.IO) { Store.readPage("home", ListSerializer(Section.serializer())) }
            if (sections.isEmpty() && disk != null) sections = disk
        }
        // Waiting contents go in as their shelf leaves the screen. This runs only when the list scrolls.
        launch {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.index }.toSet() }.collect { on ->
                if (waitingMixes != null && 0 !in on) { mixes = waitingMixes; waitingMixes = null }
                val off = waiting.keys.filter { it + 1 !in on }
                if (off.isNotEmpty()) sections = sections.toMutableList().also { l -> off.forEach { l[it] = waiting.remove(it)!! } }
            }
        }
        while (more) {
            if (!looking && page > 0) snapshotFlow { list.layoutInfo.run { (visibleItemsInfo.lastOrNull()?.index ?: 0) >= totalItemsCount - 3 } }.first { it }
            val from = cont
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Store.ensureVisitorData()
                    val p = YouTube.home(from).getOrThrow()
                    p.continuation to p.sections.map { sec ->
                        val items = sec.items.mapNotNull { Store.romanizer.item(it) }
                        Store.remember(items.mapNotNull { it.song })
                        Section(Store.romanizer.text(sec.title), items)
                    }
                }
            }
            val (next, shelves) = result.getOrElse { failed = plainError(it); fetchedAt = 0; return@coroutineScope }
            page++
            cont = next
            more = next != null
            val before = found.size
            found += personalMixes(shelves.flatMap { it.items }).filter { m -> found.none { it.id.removePrefix("VL") == m.id.removePrefix("VL") } }
            // The mixes live in the pinned row only.
            val add = shelves.map { Section(it.title, it.items.filterNot { i -> i.kind == "playlist" && isPersonalMix(i.id) }) }.filter { it.items.isNotEmpty() }
            fresh += add
            Store.writePage("home", fresh.toList(), ListSerializer(Section.serializer()))
            merge(add)
            // The mixes are spread over several pages (the numbered ones often come after the Supermix), so the
            // row waits for the first MIX_PAGES pages; mixes on pages after those still join it.
            if (looking && (page >= MIX_PAGES || !more) || !looking && found.size > before) {
                looking = false
                val m = personalMixes(found)
                Store.mixes = m
                val old = mixes
                if (old == null || sameItems(old, m) || 0 !in visible()) mixes = m else waitingMixes = m
            }
        }
    }

    private fun visible() = list.layoutInfo.visibleItemsInfo.map { it.index }.toSet()

    private fun sameItems(a: List<Item>, b: List<Item>) = a.map { it.id } == b.map { it.id }

    /** Fresh shelves into the shown ones, by title; see [Home]. LazyColumn item i + 1 is shelf i. */
    private fun merge(add: List<Section>) {
        val shown = sections.toMutableList()
        val on = visible()
        val matched = HashSet<Int>()
        for (s in add) {
            val i = shown.indices.firstOrNull { it !in matched && shown[it].title.original == s.title.original }
            if (i == null) {
                matched += shown.size
                shown += s
            } else {
                matched += i
                if (sameItems(shown[i].items, s.items) || i + 1 !in on) { shown[i] = s; waiting.remove(i) } else waiting[i] = s
            }
        }
        sections = shown
    }
}

@Composable
fun HomeScreen() {
    LaunchedEffect(Store.dataVersion) { Home.start() }
    LazyColumn(Modifier.fillMaxSize(), Home.list) {
        item(key = "mixes") { MixesRow(Home.mixes) }
        items(Home.sections) { sec ->
            Box(Modifier.padding(start = 16.dp, top = 16.dp)) { Name(sec.title, Store.listMode, MaterialTheme.typography.titleMedium) }
            LazyRow { items(sec.items) { item -> Card(item) { openItem(item) } } }
        }
        item(key = "end") {
            val f = Home.failed
            when {
                f != null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load Home.", textAlign = TextAlign.Center)
                    Text(f, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    TextButton(onClick = { Home.start() }) { Text("Retry") }
                }
                Home.more -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

/** Always first on Home and never gone: signed out or with no mixes it holds a dimmed hint instead. */
@Composable
private fun MixesRow(mixes: List<Item>?) {
    Text("Your mixes", Modifier.padding(start = 16.dp, top = 16.dp), style = MaterialTheme.typography.titleMedium)
    @Composable
    fun Hint(text: String) = Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).alpha(0.5f), style = MaterialTheme.typography.bodyMedium)
    if (!Store.signedIn) return Hint("Sign in to see your mixes")
    // Signed in, the row keeps a card's height while it loads or is empty, so the shelves below never jump.
    Box(Modifier.height(212.dp)) {
        when {
            mixes == null -> Hint("Looking for your mixes…")
            mixes.isEmpty() -> Hint("No mixes yet")
            else -> LazyRow { items(mixes) { m -> Card(m) { Playback.playMix(m.id); Nav.tab = TAB_PLAYER } } }
        }
    }
}

// ---------------------------------------------------------------- Search

private val FILTERS = listOf(
    "Songs" to YouTube.SearchFilter.FILTER_SONG,
    "Albums" to YouTube.SearchFilter.FILTER_ALBUM,
    "Artists" to YouTube.SearchFilter.FILTER_ARTIST,
    "Playlists" to YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST,
)

private class Found(val items: List<Item>, val corrected: String?, val autoCorrected: Boolean, val originalParams: String?)

@Composable
fun SearchScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    // Set by "Search for … instead": YouTube's params for the query as typed, without its spelling correction.
    var asTyped by rememberSaveable { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<Song?>(null) }
    val focus = remember { FocusRequester() }
    // The keyboard comes up for a new search, not when coming back to results.
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    fun search(q: String) { query = q; submitted = q.trim(); asTyped = null }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { query = it },
            Modifier.fillMaxWidth().padding(12.dp).focusRequester(focus),
            placeholder = { Text("Songs, albums, artists") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search(query) }),
        )
        // Fixed order; videos are left out on purpose. With a large system font they wrap onto a second line, in the
        // same order, instead of squeezing the last one into a column of letters.
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FILTERS.forEachIndexed { i, (label, _) -> FilterChip(filter == i, { filter = i; asTyped = null }, label = { Text(label) }) }
        }
        // While the text differs from the last search, YouTube's suggestions take the results' place. The ones shown
        // stay while the next keystroke's load, so typing doesn't flash the old results between them.
        var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(query) {
            if (query.isBlank() || query.trim() == submitted) { suggestions = emptyList(); return@LaunchedEffect }
            delay(250)
            suggestions = withContext(Dispatchers.IO) { YouTube.searchSuggestions(query).getOrNull()?.queries.orEmpty() }
        }
        if (suggestions.isNotEmpty() && query.trim() != submitted) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(suggestions) { q -> Text(q, Modifier.fillMaxWidth().clickable { search(q) }.padding(horizontal = 16.dp, vertical = 12.dp)) }
            }
        } else if (submitted.isNotEmpty()) {
            // The local index holds the Latin forms, so "kimi no" finds 君の声 even though YouTube wouldn't. These rows
            // show as soon as the local lookup is done and keep their place when YouTube's results arrive below them.
            // The lookup runs off the main thread, once per search, before this search's own results join the index.
            val local by produceState(emptyList<Song>(), submitted, filter) {
                value = if (filter == 0) withContext(Dispatchers.IO) { Store.searchLocal(submitted) } else emptyList()
            }
            // The top row is the likely tap: the first "Seen before" song at once, YouTube's top song when it comes.
            LaunchedEffect(local) { Playback.prepare(local.take(1)) }
            fun LazyListScope.localRows() {
                if (local.isEmpty()) return
                item { Header("Seen before") }
                items(local) { s -> SongRow(s, onMenu = { menuFor = s }) { Playback.play(listOf(s)); Nav.tab = TAB_PLAYER } }
                item { Header("YouTube Music") }
            }
            Loader(listOf(submitted, filter, asTyped).toString(), {
                val r = YouTube.search(submitted, asTyped?.let { YouTube.SearchFilter(it) } ?: FILTERS[filter].second).getOrThrow()
                val items = r.items
                    .filter { it !is SongItem || !it.isVideoSong || filter != 0 }
                    .mapNotNull { Store.romanizer.item(it) }
                    .also { list -> Store.remember(list.mapNotNull { it.song }) }
                Found(items, r.correctedQuery, r.autoCorrected, r.originalParams)
            }, loading = {
                LazyColumn(Modifier.fillMaxSize()) {
                    localRows()
                    item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                }
            }) { found ->
                LaunchedEffect(found) { Playback.prepare((local.take(1) + listOfNotNull(found.items.firstOrNull()?.playable)).distinctBy { it.id }) }
                LazyColumn(Modifier.fillMaxSize()) {
                    localRows()
                    // YouTube's spelling correction, one line above its results. The corrected query gives way to "…",
                    // so the action stays readable on one line.
                    val corrected = found.corrected
                    if (corrected != null) item {
                        Row(
                            Modifier.fillMaxWidth().clickable { if (found.autoCorrected) asTyped = found.originalParams else search(corrected) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            val style = MaterialTheme.typography.bodyMedium
                            val color = MaterialTheme.colorScheme.primary
                            val (lead, action) = if (found.autoCorrected) "Showing results for “$corrected”" to " · Search as typed" else "Did you mean “$corrected”" to "?"
                            Text(lead, Modifier.weight(1f, fill = false), style = style, color = if (found.autoCorrected) Color.Unspecified else color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(action, style = style, color = color, maxLines = 1)
                        }
                    }
                    if (found.items.isEmpty()) item { Text("No results for “$submitted”. Check the spelling, or try another filter above.", Modifier.padding(16.dp)) }
                    items(found.items) { item ->
                        val s = item.song
                        if (s != null) SongRow(s, onMenu = { menuFor = s }) { openItem(item) } else ItemRow(item) { openItem(item) }
                    }
                }
            }
        }
    }
    menuFor?.let { SongMenu(it, null) { menuFor = null } }
}

// ---------------------------------------------------------------- Library

@Composable
fun LibraryScreen() {
    if (!Store.signedIn) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Sign in to YouTube Music to see your playlists and albums.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { Nav.open(Page("login", "")) }) { Text("Sign in") }
        }
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Playlists" to "FEmusic_liked_playlists", "Albums" to "FEmusic_liked_albums", "Artists" to "FEmusic_library_corpus_track_artists")
    Column(Modifier.fillMaxSize()) {
        TabRow(tab) { tabs.forEachIndexed { i, (label, _) -> Tab(tab == i, { tab = i }, text = { Text(label) }) } }
        val id = tabs[tab].second
        val list = key(tab) { rememberLazyListState() }
        Loader("library:$id", { partial -> loadLibrary(id, partial) }, list, { items -> items.map { it.id } }, ListSerializer(Item.serializer())) { items ->
            if (items.isEmpty()) Centered("Nothing here yet. ${tabs[tab].first} you save in YouTube Music show here.")
            Box {
                LazyColumn(Modifier.fillMaxSize(), list) { items(items) { item -> ItemRow(item) { openItem(item) } } }
                FastScroll(list)
            }
        }
    }
}

// ---------------------------------------------------------------- Album, playlist, artist pages

@Serializable
class Detail(val title: roma.core.Text, val subtitle: String, val thumb: String?, val songs: List<Song>, val items: List<Item>)

@Composable
fun PageScreen(p: Page) {
    if (p.kind == "login") return LoginScreen()
    var menuFor by remember { mutableStateOf<Song?>(null) }
    val list = rememberLazyListState()
    Loader("${p.kind}:${p.id}", { partial -> loadDetail(p, partial) }, list, ::detailRows, Detail.serializer()) { d ->
        // Play and the first row are the likely taps.
        LaunchedEffect(d.songs.firstOrNull()?.id) { Playback.prepare(d.songs.take(1)) }
        Box {
        LazyColumn(Modifier.fillMaxSize(), list) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(shownThumb(d.thumb, 96.dp), null, Modifier.size(96.dp).clip(if (p.kind == "artist") CircleShape else RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Name(d.title, Store.listMode, MaterialTheme.typography.titleLarge)
                        if (d.subtitle.isNotBlank()) Text(d.subtitle, style = MaterialTheme.typography.bodySmall)
                        if (d.songs.isNotEmpty()) TextButton(onClick = { playList(p, d.songs); Nav.tab = TAB_PLAYER }) { Text("Play") }
                    }
                }
            }
            itemsIndexed(d.songs) { i, s -> SongRow(s, onMenu = { menuFor = s }) { Playback.play(d.songs, i); Nav.tab = TAB_PLAYER } }
            if (d.items.isNotEmpty()) {
                item { Header("Albums and more") }
                items(d.items) { item -> ItemRow(item) { openItem(item) } }
            }
        }
        FastScroll(list)
        }
    }
    menuFor?.let { SongMenu(it, null) { menuFor = null } }
}

/** Generated playlists (mixes, editorial RD… lists) page on as they play; anything else plays what the page holds. */
private fun playList(p: Page, songs: List<Song>) =
    if (p.kind == "playlist" && p.id.removePrefix("VL").startsWith("RD")) Playback.playMix(p.id, songs) else Playback.play(songs)

/** What each LazyColumn item of a page shows, in order: see [Loader]. */
private fun detailRows(d: Detail): List<Any?> =
    listOf(listOf(d.title.original, d.subtitle, d.thumb, d.songs.isEmpty())) + d.songs.map { it.id } +
        (if (d.items.isEmpty()) emptyList() else listOf("more") + d.items.map { it.id })

private suspend fun loadDetail(p: Page, partial: suspend (Detail) -> Unit): Detail {
    val r = Store.romanizer
    return when (p.kind) {
        "album" -> {
            val a = YouTube.album(p.id).getOrThrow()
            val artists = a.album.artists.orEmpty().joinToString(", ") { it.name }
            val songs = a.songs.map { r.song(it) }
            Store.remember(songs)
            Detail(r.text(a.album.title, listOf(artists)), listOfNotNull(r.text(artists).latin, a.album.year?.toString()).joinToString(" · "), a.album.thumbnail, songs, emptyList())
        }
        "playlist" -> {
            val id = p.id.removePrefix("VL")
            val pl = YouTube.playlist(id).getOrThrow()
            val title = r.text(pl.playlist.title)
            val author = pl.playlist.author?.name?.let { r.text(it).latin } ?: ""
            // Generated playlists (RD…) loop: their continuations return songs already listed.
            val songs = ArrayList<Song>()
            val seen = HashSet<String>()
            fun add(items: List<SongItem>) = items.filter { seen.add(it.id) }.map { r.song(it) }.also { songs += it; Store.remember(it) }
            add(pl.songs)
            var cont = pl.songsContinuation
            // ponytail: loads at most 10 continuation pages (about 1000 songs); page on scroll if libraries get bigger.
            repeat(10) {
                val c = cont ?: return@repeat
                partial(Detail(title, author, pl.playlist.thumbnail, songs.toList(), emptyList()))
                val more = YouTube.playlistContinuation(c).getOrNull() ?: return@repeat
                add(more.songs)
                cont = more.continuation
            }
            if (id == "LM") Playback.markLiked(songs.map { it.id })
            Detail(title, author, pl.playlist.thumbnail, songs, emptyList())
        }
        else -> {
            val a = YouTube.artist(p.id).getOrThrow()
            val songs = a.sections.flatMap { it.items }.filterIsInstance<SongItem>().map { r.song(it) }
            Store.remember(songs)
            val items = a.sections.flatMap { it.items }.filter { it !is SongItem }.mapNotNull { r.item(it) }
            Detail(r.text(a.artist.title, artistId = a.artist.id), a.monthlyListenerCount ?: a.subscriberCountText ?: "", a.artist.thumbnail, songs, items)
        }
    }
}

// ---------------------------------------------------------------- Sign-in

/** YouTube Music sign-in in a WebView; the cookies are kept. No Google account picker, so no Play Services. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginScreen() {
    val context = LocalContext.current
    AndroidView(factory = { c ->
        WebView(c).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!url.startsWith("https://music.youtube.com")) return
                    val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com") ?: return
                    if (!cookie.contains("SAPISID")) return
                    view.evaluateJavascript("JSON.stringify([window.yt&&yt.config_?yt.config_.VISITOR_DATA:null, window.yt&&yt.config_?yt.config_.DATASYNC_ID:null])") { raw ->
                        val values = runCatching { Store.json.decodeFromString<List<String?>>(Store.json.decodeFromString<String>(raw)) }.getOrDefault(listOf(null, null))
                        YouTube.cookie = cookie
                        values[0]?.let { YouTube.visitorData = it; Store.set("visitorData", it) }
                        values[1]?.let { YouTube.dataSyncId = it.substringBefore("||"); Store.set("dataSyncId", it.substringBefore("||")) }
                        Store.set("cookie", cookie)
                        Toast.makeText(context, "Signed in", Toast.LENGTH_SHORT).show()
                        Nav.stacks[Nav.tab].clear()
                        Playback.syncLikes()
                    }
                }
            }
            loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
        }
    }, modifier = Modifier.fillMaxSize())
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen() {
    Store.version
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.use { o -> o.write(Store.exportOverrides().toByteArray()) } }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            Store.setOverrides(Store.json.decodeFromString<List<Override>>(text))
        }.onFailure { Toast.makeText(context, "Not an overrides file: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) } // key, title

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Account")
        if (Store.signedIn) Row1("Signed in to YouTube Music", "Tap to sign out") {
            CookieManager.getInstance().removeAllCookies(null)
            YouTube.cookie = null
            Store.set("cookie", null)
        } else Row1("Sign in to YouTube Music", "For your library and likes") { Nav.open(Page("login", "")) }

        Header("Display")
        Options("Lists", "display.list", "both", MODES)
        Options("Player", "display.player", "both", MODES)
        Switch1("Left-handed player controls", "ui.leftHanded", false)
        Switch1("True black in dark mode (saves power on OLED screens)", "ui.trueBlack", false)
        Switch1("Accent colour from the song's artwork", "ui.artColors", false)

        Header("Romanization")
        Switch1("Japanese: long vowels with macrons (ō)", "rom.macrons", true)
        Switch1("Chinese: pinyin with tone marks", "rom.tones", true)
        Options("Cyrillic (other languages use their national Latin form)", "rom.cyrillic", "english", listOf("english" to "Russian: English-style", "polish" to "Russian: Polish-style", "iso9" to "ISO 9, every language"))
        Options("Greek", "rom.greek", "classical", listOf("classical" to "Classical, no accents (β = b)", "elot" to "Modern, ELOT 743 (β = v)"))
        Options("Han text without kana is", "rom.han", "ja", listOf("ja" to "Japanese", "zh" to "Chinese"))
        // Text only in scripts the user reads shows as written, in lists, the player, the notification and lyrics.
        Text("Scripts you read (shown as written, not romanized)", Modifier.padding(start = 16.dp, top = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        val read = Store.readScripts
        SCRIPTS.forEach { (k, label) ->
            Row(Modifier.fillMaxWidth().clickable { Store.setReads(k, k !in read) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(k in read, null, Modifier.padding(end = 8.dp))
                Text(label)
            }
        }
        val dictionary = when {
            Dictionary.installed -> "Installed"
            Dictionary.status != null -> Dictionary.status!!
            else -> "Not installed: kanji stay as written. Tap to install (${Dictionary.DOWNLOAD})"
        }
        Row1("Japanese dictionary", dictionary, enabled = !Dictionary.installed) { Store.setReads("ja", false) }
        Row1("Corrections: ${Store.romanizer.overrides.size}", "Export them to one file") { export.launch("roma-corrections.json") }
        Row1("Import corrections", "Replaces the current ones") { import.launch(arrayOf("application/json", "*/*")) }
        Store.romanizer.overrides.forEach { o ->
            val scope = when (o.scope) {
                "song" -> "This song"; "artist" -> "This artist"
                "word" -> if (o.id == "") "Word, everywhere" else "Word, in one song"
                else -> "Everywhere"
            }
            Row1(if (o.latin == o.original) "${o.original}: not romanized" else "${o.original} → ${o.latin}", "$scope · Tap to remove") {
                Store.saveOverride(Override(o.scope, o.id, o.original, ""))
            }
        }

        Header("Lyrics")
        val order = Store.lyricsOrder()
        order.forEachIndexed { i, p ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${p.name}", Modifier.weight(1f))
                IconButton(onClick = { move(order, i, -1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                IconButton(onClick = { move(order, i, 1) }, enabled = i < order.size - 1) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                Switch(Store.lyricsEnabled(p), { Store.set("lyrics.on.${p.id}", it) }, Modifier.padding(end = 16.dp))
            }
        }
        Text("All are asked at once. YouTube Music's text is the reference: word timing that matches it wins, then its own timed lyrics, then matching line timing, then its plain text. The order above only matters when YouTube Music has no lyrics.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        Row1("Search lyrics again for this song", "After changing the providers") { Playback.refetchLyrics() }
        Switch1("Show original", "lyrics.layer.original", true)
        Switch1("Show romanization", "lyrics.layer.latin", true)
        Options("Romanization layout", "lyrics.layout", "lines", listOf("lines" to "A line under the original", "ruby" to "Above each word, like furigana"))
        Text("Long-press a word in the lyrics to see its reading and correct it, for that song or everywhere.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        // A global setting: never dimmed by the current song. A song whose translation isn't in the chosen language
        // just shows none.
        Switch1("Show translation", "lyrics.layer.translation", true)
        val device = java.util.Locale.getDefault().let { it.getDisplayLanguage(it) }
        Options("Translation language", "lyrics.translationLang", "device", listOf("device" to "Device language ($device), then English", "en" to "English", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "ru" to "Russian"))

        Header("Queue and radio")
        Options("Radio may drift", "radio.leash", "free", listOf("close" to "Close: same and related artists", "medium" to "Medium: at most half other artists", "free" to "Free: YouTube's autoplay as is"))
        Switch1("Prefer audio tracks over music videos", "radio.preferAudio", true)
        Switch1("Hide live, sped up, nightcore, slowed", "radio.hideVersions", true)
        Switch1("Skip banned songs and artists", "radio.skipBanned", true)
        Switch1("Stop when the queue ends", "queue.stopAtEnd", false)
        Switch1("Even out loudness", "play.normalize", true)
        Switch1("Download songs ahead", "play.ahead", false, subtitle = "The whole song and the next 3, so playback goes on without a network. Uses more data: about 4 MB per song you skip")
        Switch1("Resume when headphones reconnect", "play.resumeOnHeadphones", false, subtitle = "Within an hour of them pausing the song by disconnecting")
        Switch1("Get likely songs ready on mobile data and in Battery Saver", "prepare.always", false, subtitle = "roma looks up the song you're likely to tap next (a page's first song, Search's top result, the queue's next songs) and loads its first second, so it starts at once. About 45 KB each, at most 3 MB an hour. Off: only on Wi-Fi and outside Battery Saver")

        Header("Banned")
        val banned = Store.banned
        if (banned.isEmpty()) Text("Nothing banned.", Modifier.padding(horizontal = 16.dp))
        banned.forEach { b -> Row1("${if (b.kind == "artist") "Artist" else "Song"}: ${b.name}", "Tap to unban") { Store.unban(b.kind, b.id) } }

        Header("Last.fm")
        Row1("API key", Store.str("lastfm.key", "").ifBlank { "Not set: create one at last.fm/api/account/create" }) { editing = "lastfm.key" to "Last.fm API key" }
        Row1("Shared secret", if (Store.str("lastfm.secret", "").isBlank()) "Not set" else "Set") { editing = "lastfm.secret" to "Last.fm shared secret" }
        val connected = Store.str("lastfm.session", "").isNotBlank()
        // A file read: off the main thread, so a switch on this page doesn't wait for it.
        val waiting by produceState(0, connected, Store.version) { value = withContext(Dispatchers.IO) { if (connected) Playback.scrobbleQueue().load().size else 0 } }
        val ready = Store.str("lastfm.key", "").isNotBlank() && Store.str("lastfm.secret", "").isNotBlank()
        Row1(if (connected) "Connected: scrobbling" else "Connect", if (connected) "Tap to disconnect · $waiting waiting" else "Opens the browser to allow access", ready || connected) {
            if (connected) Store.set("lastfm.session", null)
            else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LastFm(Store.str("lastfm.key", ""), "").authUrl("roma://lastfm"))))
        }

        Text("roma ${BuildConfig.VERSION_NAME}", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
    }
    editing?.let { (key, title) -> TextInputDialog(title, Store.str(key, ""), { editing = null }) { Store.set(key, it.trim()) } }
}

/**
 * First start: which scripts to romanize. Japanese needs the dictionary app, fetched once if picked; the others need
 * nothing. Unpicked scripts go into "scripts you read" and show as written. Shown until Continue.
 */
@Composable
fun SetupScreen() {
    Store.version
    val read = Store.readScripts
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Which scripts should roma romanize?", style = MaterialTheme.typography.headlineSmall)
        Text("The others show as written. You can change this in Settings.", Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium)
        SCRIPTS.forEach { (k, label) ->
            Row(Modifier.fillMaxWidth().clickable { Store.set(Store.READ_KEY, (if (k in read) read - k else read + k).joinToString(",")) }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(k !in read, null, Modifier.padding(end = 8.dp))
                Column {
                    Text(label)
                    if (k == "ja") Text(if (Dictionary.installed) "Dictionary installed" else "Needs the Japanese dictionary: ${Dictionary.DOWNLOAD}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        androidx.compose.material3.Button(
            onClick = {
                Store.set("setup.done", true)
                if ("ja" !in read) Dictionary.install(Store.context)
            },
            Modifier.padding(top = 24.dp).align(Alignment.End),
        ) { Text("Continue") }
    }
}

private val SCRIPTS = listOf("ja" to "Japanese", "zh" to "Chinese", "ko" to "Korean", "cyrillic" to "Cyrillic", "greek" to "Greek", "other" to "Other scripts")

private val MODES = listOf("both" to "Latin, original underneath", "ruby" to "Original, reading above", "romanized" to "Latin only", "original" to "Original only")

private fun move(order: List<roma.core.LyricsProvider>, i: Int, by: Int) {
    val list = order.toMutableList()
    val p = list.removeAt(i)
    list.add(i + by, p)
    Store.set("lyrics.order", list.joinToString(",") { it.id })
}

@Composable
private fun Row1(title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.4f).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Switch1(title: String, key: String, def: Boolean, enabled: Boolean = true, subtitle: String? = null) {
    val on = Store.bool(key, def)
    Row(Modifier.fillMaxWidth().clickable(enabled) { Store.set(key, !on) }.alpha(if (enabled) 1f else 0.4f).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, { Store.set(key, it) }, enabled = enabled)
    }
}

@Composable
private fun Options(title: String, key: String, def: String, options: List<Pair<String, String>>) {
    val value = Store.str(key, def)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        options.forEach { (v, label) -> Choice(label, value == v) { Store.set(key, v) } }
    }
}

/** All pages of a library tab: the first page holds only about 25 items; each page shows as it arrives. */
private suspend fun loadLibrary(browseId: String, partial: suspend (List<Item>) -> Unit): List<Item> {
    val first = YouTube.library(browseId).getOrThrow()
    val all = first.items.mapNotNull { Store.romanizer.item(it) }.toMutableList()
    var cont = first.continuation
    // ponytail: stops at 50 pages (about 1,250 items); raise it if a library is bigger.
    repeat(50) {
        val c = cont ?: return@repeat
        partial(all.toList())
        val more = YouTube.libraryContinuation(c).getOrNull() ?: return@repeat
        all.addAll(more.items.mapNotNull { Store.romanizer.item(it) })
        cont = more.continuation
    }
    return all
}

/** A failure in plain words, with what to do about it. */
fun plainError(t: Throwable): String = when (generateSequence(t) { it.cause }.firstOrNull { it is java.io.IOException } ?: t) {
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException -> "No connection. Check Wi-Fi or mobile data, then Retry."
    is java.net.SocketTimeoutException -> "YouTube took too long to answer. Retry in a moment."
    else -> t.message ?: t.toString()
}

/**
 * A thumb on the right edge of a long list (over 3 screens) to drag through it. It shows while the list scrolls and
 * for a moment after, and only then takes touches, so the rows' ⋯ buttons under it work the rest of the time.
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.FastScroll(list: LazyListState) {
    val info = list.layoutInfo
    val visible = info.visibleItemsInfo.size
    val total = info.totalItemsCount
    if (total <= visible * 3 || visible == 0) return
    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(list.isScrollInProgress, dragging) {
        if (list.isScrollInProgress || dragging) shown = true else { kotlinx.coroutines.delay(1500); shown = false }
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, label = "fastScroll")
    if (alpha == 0f) return
    val scope = rememberCoroutineScope()
    var height by remember { mutableIntStateOf(1) }
    val thumb = with(LocalDensity.current) { 56.dp.toPx() }
    val fraction = list.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)
    Box(
        Modifier.align(Alignment.TopEnd).fillMaxHeight().width(32.dp).onSizeChanged { height = it.height }
            .pointerInput(total) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    val f = ((change.position.y - thumb / 2) / (height - thumb)).coerceIn(0f, 1f)
                    scope.launch { list.scrollToItem((f * (total - visible)).toInt()) }
                }
            }
            .alpha(alpha),
    ) {
        Box(
            Modifier.align(Alignment.TopEnd).padding(end = 4.dp)
                .offset { androidx.compose.ui.unit.IntOffset(0, (fraction.coerceIn(0f, 1f) * (height - thumb)).toInt()) }
                .size(width = 8.dp, height = 56.dp).clip(RoundedCornerShape(4.dp))
                .background(if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}
