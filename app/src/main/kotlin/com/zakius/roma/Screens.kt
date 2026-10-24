package com.zakius.roma

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import roma.core.Item
import roma.core.LastFm
import roma.core.Override
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
        t.isLatin || mode == "romanized" -> Text(t.latin, mod, style = style, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
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

@Composable
fun SongRow(s: Song, highlight: Boolean = false, extra: String? = null, onMenu: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(s.thumb, null, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
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
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(item.thumb, null, Modifier.size(56.dp).clip(if (item.kind == "artist") CircleShape else RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Name(item.title, Store.listMode)
            if (item.subtitle.latin.isNotBlank()) Text(item.subtitle.latin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun Card(item: Item, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clickable(onClick = onClick).padding(6.dp)) {
        AsyncImage(item.thumb, null, Modifier.size(128.dp).clip(if (item.kind == "artist") CircleShape else RoundedCornerShape(6.dp)))
        Spacer(Modifier.height(4.dp))
        Name(item.title, Store.listMode, MaterialTheme.typography.bodyMedium)
        if (item.subtitle.latin.isNotBlank()) Text(item.subtitle.latin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** What tapping a card or row does: a song plays (radio follows), anything else opens its page. */
/** A song plays alone and YouTube's autoplay follows; anything else opens its page. */
fun openItem(item: Item) {
    if (item.kind == "song" && item.song != null) {
        Playback.play(listOf(item.song!!))
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
    ModalBottomSheet(onDismissRequest = onClose) {
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
                Store.ban("song", s.id, s.title.latin)
                if (Playback.current()?.song?.id == s.id) Playback.never() else Toast.makeText(context, "Banned ${s.title.latin}", Toast.LENGTH_SHORT).show()
                onClose()
            }
            MenuItem("Ban artist", s.firstArtistId != null) {
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

/** Correct the Latin form of the song's title, an artist or the album, for this string, this song or this artist. */
@Composable
private fun CorrectDialog(s: Song, onDone: () -> Unit) {
    val fields = listOf(s.title) + s.artists.map { it.name } + listOfNotNull(s.album?.name)
    var field by remember { mutableIntStateOf(0) }
    var scope by remember { mutableStateOf("song") }
    var latin by remember(field) { mutableStateOf(fields[field].latin) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Correct romanization") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                fields.forEachIndexed { i, t -> Choice(t.original, field == i) { field = i } }
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

/** Loads something once per key and shows a spinner, the error, or the content. */
@Composable
private fun <T> Loader(key: Any, load: suspend () -> T, content: @Composable (T) -> Unit) {
    var result by remember(key) { mutableStateOf<Result<T>?>(null) }
    var attempt by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, attempt) {
        result = null
        result = withContext(Dispatchers.IO) { runCatching { load() } }
    }
    val r = result
    when {
        r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        r.isFailure -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Couldn't load this page.", textAlign = TextAlign.Center)
            Text(r.exceptionOrNull()?.message ?: "", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            TextButton(onClick = { attempt++ }) { Text("Retry") }
        }
        else -> content(r.getOrThrow())
    }
}

@Composable
private fun Header(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
}

// ---------------------------------------------------------------- Home

class Section(val title: roma.core.Text, val items: List<Item>)

private const val MIX_PAGES = 10

/**
 * Home: the pinned "Your mixes" row, then YouTube's shelves. The first pages load until the mixes turn up (at most
 * [MIX_PAGES]); after that a page loads when the user nears the end. New shelves only ever go at the bottom.
 */
@Composable
fun HomeScreen() {
    var sections by remember(Store.version) { mutableStateOf(listOf<Section>()) }
    var mixes by remember { mutableStateOf(Store.mixes) }
    var more by remember(Store.version) { mutableStateOf(true) }
    var failed by remember(Store.version) { mutableStateOf<String?>(null) }
    var attempt by remember(Store.version) { mutableIntStateOf(0) }
    // Where paging stands; kept across Retry so it carries on from the failed page.
    var cont by remember(Store.version) { mutableStateOf<String?>(null) }
    var page by remember(Store.version) { mutableIntStateOf(0) }
    val found = remember(Store.version) { ArrayList<Item>() }
    var looking by remember(Store.version) { mutableStateOf(Store.signedIn) }
    val list = rememberLazyListState()
    LaunchedEffect(Store.version, attempt) {
        failed = null
        while (more) {
            if (!looking) snapshotFlow { list.layoutInfo.run { (visibleItemsInfo.lastOrNull()?.index ?: 0) >= totalItemsCount - 3 } }.first { it }
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
            val (next, shelves) = result.getOrElse { failed = it.message ?: it.toString(); return@LaunchedEffect }
            page++
            cont = next
            more = next != null
            found += personalMixes(shelves.flatMap { it.items })
            // The mixes live in the pinned row only.
            sections = sections + shelves.map { Section(it.title, it.items.filterNot { i -> i.kind == "playlist" && isPersonalMix(i.id) }) }.filter { it.items.isNotEmpty() }
            if (looking && (found.isNotEmpty() || page >= MIX_PAGES || !more)) {
                looking = false
                mixes = personalMixes(found)
                Store.mixes = mixes
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), list) {
        item(key = "mixes") { MixesRow(mixes) }
        items(sections) { sec ->
            Box(Modifier.padding(start = 16.dp, top = 16.dp)) { Name(sec.title, Store.listMode, MaterialTheme.typography.titleMedium) }
            LazyRow { items(sec.items) { item -> Card(item) { openItem(item) } } }
        }
        item(key = "end") {
            val f = failed
            when {
                f != null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load Home.", textAlign = TextAlign.Center)
                    Text(f, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    TextButton(onClick = { attempt++ }) { Text("Retry") }
                }
                more -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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

@Composable
fun SearchScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<Song?>(null) }
    val focus = remember { FocusRequester() }
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!focusedOnce) { focus.requestFocus(); focusedOnce = true } }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { query = it },
            Modifier.fillMaxWidth().padding(12.dp).focusRequester(focus),
            placeholder = { Text("Songs, albums, artists") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
        )
        // Fixed order; videos are left out on purpose.
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FILTERS.forEachIndexed { i, (label, _) -> FilterChip(filter == i, { filter = i }, label = { Text(label) }) }
        }
        if (submitted.isNotEmpty()) {
            // The local index holds the Latin forms, so "kimi no" finds 君の声 even though YouTube wouldn't.
            val local = if (filter == 0) Store.searchLocal(submitted) else emptyList()
            Loader(submitted to filter to Store.version, {
                YouTube.search(submitted, FILTERS[filter].second).getOrThrow().items
                    .filter { it !is SongItem || !it.isVideoSong || filter != 0 }
                    .mapNotNull { Store.romanizer.item(it) }
                    .also { list -> Store.remember(list.mapNotNull { it.song }) }
            }) { remote ->
                LazyColumn(Modifier.fillMaxSize()) {
                    if (local.isNotEmpty()) {
                        item { Header("Seen before") }
                        items(local) { s -> SongRow(s, onMenu = { menuFor = s }) { Playback.play(listOf(s)); Nav.tab = TAB_PLAYER } }
                        item { Header("YouTube Music") }
                    }
                    if (remote.isEmpty()) item { Text("No results for “$submitted”.", Modifier.padding(16.dp)) }
                    items(remote) { item ->
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
        Loader(tabs[tab].second to Store.version, { loadLibrary(tabs[tab].second) }) { items ->
            if (items.isEmpty()) Centered("Nothing here yet.")
            LazyColumn(Modifier.fillMaxSize()) { items(items) { item -> ItemRow(item) { openItem(item) } } }
        }
    }
}

// ---------------------------------------------------------------- Album, playlist, artist pages

class Detail(val title: roma.core.Text, val subtitle: String, val thumb: String?, val songs: List<Song>, val items: List<Item>)

@Composable
fun PageScreen(p: Page) {
    if (p.kind == "login") return LoginScreen()
    var menuFor by remember { mutableStateOf<Song?>(null) }
    Loader(p.kind + p.id + Store.version, { loadDetail(p) }) { d ->
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(d.thumb, null, Modifier.size(96.dp).clip(if (p.kind == "artist") CircleShape else RoundedCornerShape(6.dp)))
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
    }
    menuFor?.let { SongMenu(it, null) { menuFor = null } }
}

/** Generated playlists (mixes, editorial RD… lists) page on as they play; anything else plays what the page holds. */
private fun playList(p: Page, songs: List<Song>) =
    if (p.kind == "playlist" && p.id.removePrefix("VL").startsWith("RD")) Playback.playMix(p.id) else Playback.play(songs)

private suspend fun loadDetail(p: Page): Detail {
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
            val all = pl.songs.toMutableList()
            var cont = pl.songsContinuation
            // ponytail: loads at most 10 continuation pages (about 1000 songs); page on scroll if libraries get bigger.
            repeat(10) {
                val c = cont ?: return@repeat
                val more = YouTube.playlistContinuation(c).getOrNull() ?: return@repeat
                all.addAll(more.songs)
                cont = more.continuation
            }
            // Generated playlists (RD…) loop: their continuations return songs already listed.
            val songs = all.distinctBy { it.id }.map { r.song(it) }
            Store.remember(songs)
            if (id == "LM") Playback.markLiked(songs.map { it.id })
            Detail(r.text(pl.playlist.title), pl.playlist.author?.name?.let { r.text(it).latin } ?: "", pl.playlist.thumbnail, songs, emptyList())
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

        Header("Romanization")
        Switch1("Japanese: long vowels with macrons (ō)", "rom.macrons", true)
        Switch1("Chinese: pinyin with tone marks", "rom.tones", true)
        Options("Cyrillic", "rom.cyrillic", "english", listOf("english" to "English-style", "polish" to "Polish-style", "iso9" to "ISO 9"))
        Options("Greek", "rom.greek", "classical", listOf("classical" to "Classical, no accents (β = b)", "elot" to "Modern, ELOT 743 (β = v)"))
        Options("Han text without kana is", "rom.han", "ja", listOf("ja" to "Japanese", "zh" to "Chinese"))
        Row1("Corrections: ${Store.romanizer.overrides.size}", "Export them to one file") { export.launch("roma-corrections.json") }
        Row1("Import corrections", "Replaces the current ones") { import.launch(arrayOf("application/json", "*/*")) }
        Store.romanizer.overrides.forEach { o ->
            val scope = when (o.scope) { "song" -> "This song"; "artist" -> "This artist"; else -> "Everywhere" }
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
        Text("Word timing wins over line timing, line timing over plain text; within each, the order above decides.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        Row1("Search lyrics again for this song", "After changing the providers") { Playback.refetchLyrics() }
        Switch1("Show original", "lyrics.layer.original", true)
        Switch1("Show romanization", "lyrics.layer.latin", true)
        // Dimmed when the current song's translation isn't in the chosen language: there is nothing to show.
        val lyrics = Playback.lyrics?.lyrics
        Switch1("Show translation", "lyrics.layer.translation", true, lyrics == null || Store.translationWanted(roma.core.translationLanguage(lyrics)))
        val device = java.util.Locale.getDefault().let { it.getDisplayLanguage(it) }
        Options("Translation language", "lyrics.translationLang", "device", listOf("device" to "Device language ($device), then English", "en" to "English", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "ru" to "Russian"))

        Header("Queue and radio")
        Options("Radio may drift", "radio.leash", "free", listOf("close" to "Close: same and related artists", "medium" to "Medium: at most half other artists", "free" to "Free: YouTube's autoplay as is"))
        Switch1("Prefer audio tracks over music videos", "radio.preferAudio", true)
        Switch1("Hide live, sped up, nightcore, slowed", "radio.hideVersions", true)
        Switch1("Skip banned songs and artists", "radio.skipBanned", true)
        Switch1("Stop when the queue ends", "queue.stopAtEnd", false)
        Switch1("Even out loudness", "play.normalize", true)

        Header("Banned")
        val banned = Store.banned
        if (banned.isEmpty()) Text("Nothing banned.", Modifier.padding(horizontal = 16.dp))
        banned.forEach { b -> Row1("${if (b.kind == "artist") "Artist" else "Song"}: ${b.name}", "Tap to unban") { Store.unban(b.kind, b.id) } }

        Header("Last.fm")
        Row1("API key", Store.str("lastfm.key", "").ifBlank { "Not set: create one at last.fm/api/account/create" }) { editing = "lastfm.key" to "Last.fm API key" }
        Row1("Shared secret", if (Store.str("lastfm.secret", "").isBlank()) "Not set" else "Set") { editing = "lastfm.secret" to "Last.fm shared secret" }
        val connected = Store.str("lastfm.session", "").isNotBlank()
        val ready = Store.str("lastfm.key", "").isNotBlank() && Store.str("lastfm.secret", "").isNotBlank()
        Row1(if (connected) "Connected: scrobbling" else "Connect", if (connected) "Tap to disconnect · ${Playback.scrobbleQueue().load().size} waiting" else "Opens the browser to allow access", ready || connected) {
            if (connected) Store.set("lastfm.session", null)
            else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LastFm(Store.str("lastfm.key", ""), "").authUrl("roma://lastfm"))))
        }

        Text("roma ${BuildConfig.VERSION_NAME}", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
    }
    editing?.let { (key, title) -> TextInputDialog(title, Store.str(key, ""), { editing = null }) { Store.set(key, it.trim()) } }
}

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
private fun Switch1(title: String, key: String, def: Boolean, enabled: Boolean = true) {
    val on = Store.bool(key, def)
    Row(Modifier.fillMaxWidth().clickable(enabled) { Store.set(key, !on) }.alpha(if (enabled) 1f else 0.4f).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
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

/** All pages of a library tab: the first page holds only about 25 items. */
private suspend fun loadLibrary(browseId: String): List<Item> {
    val first = YouTube.library(browseId).getOrThrow()
    val all = first.items.toMutableList()
    var cont = first.continuation
    // ponytail: stops at 50 pages (about 1,250 items); raise it if a library is bigger.
    repeat(50) {
        val c = cont ?: return@repeat
        val more = YouTube.libraryContinuation(c).getOrNull() ?: return@repeat
        all.addAll(more.items)
        cont = more.continuation
    }
    return all.mapNotNull { Store.romanizer.item(it) }
}
