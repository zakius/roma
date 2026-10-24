package com.zakius.roma

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import roma.core.Line
import roma.core.Override

/**
 * The player. Only the area above the bottom block changes (song, lyrics, queue). The bottom block has fixed
 * heights and fixed slots, so no control ever moves; an unavailable control is dimmed in place.
 */
@Composable
fun PlayerScreen() {
    Playback.tick
    var view by rememberSaveable { mutableStateOf("song") }
    var menuOpen by remember { mutableStateOf(false) }
    val entry = Playback.current()
    val p = Playback.player
    val lyrics = Playback.lyrics?.takeIf { it.songId == entry?.song?.id }?.lyrics
    val hasLyrics = lyrics != null && lyrics.lines.isNotEmpty()
    if (view == "lyrics" && !hasLyrics && lyrics != null) view = "song"

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (view) {
                "lyrics" -> if (lyrics != null) LyricsView(lyrics.lines, lyrics.synced, entry?.song?.id) else Centered("Looking for lyrics…")
                "queue" -> QueueView()
                else -> SongView()
            }
            Playback.lastBan?.let { (song, _) ->
                Surface(
                    Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                ) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Banned · ${song.title.latin}", Modifier.weight(1f), color = MaterialTheme.colorScheme.inverseOnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { Playback.undoBan() }) { Text("Undo", color = MaterialTheme.colorScheme.inversePrimary) }
                    }
                }
            }
        }

        // ---- Bottom block ----
        SeekBar()
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Slot(painterResource(androidx.media3.ui.R.drawable.exo_icon_shuffle_on), "Shuffle", entry != null, Playback.shuffled) { Playback.toggleShuffle() }
            Slot(painterResource(androidx.media3.ui.R.drawable.exo_icon_previous), "Previous", entry != null) { p.seekToPrevious() }
            Slot(painterResource(if (p.isPlaying) androidx.media3.ui.R.drawable.exo_icon_pause else androidx.media3.ui.R.drawable.exo_icon_play), if (p.isPlaying) "Pause" else "Play", entry != null, iconSize = 48) { Playback.togglePlay() }
            Slot(painterResource(androidx.media3.ui.R.drawable.exo_icon_next), "Next", p.hasNextMediaItem()) { p.seekToNextMediaItem() }
            val repeatIcon = when (p.repeatMode) {
                Player.REPEAT_MODE_ONE -> androidx.media3.ui.R.drawable.exo_icon_repeat_one
                Player.REPEAT_MODE_ALL -> androidx.media3.ui.R.drawable.exo_icon_repeat_all
                else -> androidx.media3.ui.R.drawable.exo_icon_repeat_all // off is shown by colour: dimming means unavailable
            }
            Slot(painterResource(repeatIcon), "Repeat", entry != null, p.repeatMode != Player.REPEAT_MODE_OFF) { Playback.cycleRepeat() }
        }
        Spacer(Modifier.height(28.dp))
        // More (song menu, where Ban lives) sits at the outer edge with two empty slots after it; Like at the other
        // edge, under the thumb. Left-handed mode mirrors the row. Slots never move otherwise.
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            val liked = entry?.song?.id in Playback.liked
            val slots: List<@Composable RowScope.() -> Unit> = listOf(
                { Slot(rememberVectorPainter(Icons.Default.MoreVert), "More", entry != null, label = "More") { menuOpen = true } },
                { Spacer(Modifier.weight(1f)) }, // deliberately empty: no icon, no label, not tappable
                { Spacer(Modifier.weight(1f)) },
                { Slot(rememberVectorPainter(LyricsIcon), "Lyrics", hasLyrics || view == "lyrics", view == "lyrics", label = "Lyrics") { view = if (view == "lyrics") "song" else "lyrics" } },
                { Slot(rememberVectorPainter(Icons.Default.List), "Queue", entry != null, view == "queue", label = "Queue") { view = if (view == "queue") "song" else "queue" } },
                { Slot(rememberVectorPainter(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp), if (liked) "Unlike" else "Like", entry != null && Store.signedIn, liked, label = "Like") { Playback.toggleLike() } },
            )
            (if (Store.bool("ui.leftHanded", false)) slots.reversed() else slots).forEach { it() }
        }
    }
    if (menuOpen && entry != null) SongMenu(entry.song, queueIndex = null) { menuOpen = false }
}

/** One equal-width control slot. Disabled slots keep their place and are dimmed. */
@Composable
private fun RowScope.Slot(icon: Painter, description: String, enabled: Boolean, selected: Boolean = false, label: String? = null, iconSize: Int = 28, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.weight(1f).heightIn(min = 48.dp).fillMaxSize()
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected && label != null) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick)
            .alpha(if (enabled) 1f else 0.3f),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, description, Modifier.size(iconSize.dp), tint = color)
            if (label != null) Text(label, fontSize = 11.sp, color = color, maxLines = 1)
        }
    }
}

@Composable
private fun SeekBar() {
    Playback.tick
    val p = Playback.player
    val duration = p.duration.takeIf { it > 0 } ?: 0L
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val pos = if (dragging) dragValue else p.currentPosition.toFloat()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Slider(
            value = pos.coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { p.seekTo(dragValue.toLong()); dragging = false },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            enabled = duration > 0,
            modifier = Modifier.height(32.dp),
        )
        Row(Modifier.fillMaxWidth().height(18.dp)) {
            Text(time(pos.toLong()), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) time(duration) else "", fontSize = 12.sp)
        }
    }
}

private fun time(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun SongView() {
    Playback.tick
    val e = Playback.current() ?: return Centered("Nothing playing")
    val s = e.song
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AsyncImage(s.thumb?.let(::bigThumb), null, Modifier.weight(1f, fill = false).aspectRatio(1f).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.height(16.dp))
        Name(s.title, Store.playerMode, style = MaterialTheme.typography.headlineSmall, center = true)
        Spacer(Modifier.height(4.dp))
        Name(roma.core.Text(s.artistOriginal, s.artistLatin), Store.playerMode, style = MaterialTheme.typography.titleMedium, center = true)
        Playback.error?.let {
            Spacer(Modifier.height(8.dp))
            Text("Can't play this song: $it", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            TextButton(onClick = { Playback.player.prepare(); Playback.player.play() }) { Text("Retry") }
        }
    }
}

// YouTube thumbnails carry their size in the URL; ask for a larger one for the player.

@Composable
private fun QueueView() {
    Playback.tick
    val all = Playback.entries()
    val cur = Playback.player.currentMediaItemIndex
    var menuFor by remember { mutableStateOf<Int?>(null) }
    val rows = ArrayList<Pair<String?, Int>>() // header or entry index
    if (cur in all.indices) { rows.add("Now" to -1); rows.add(null to cur) }
    val queued = (cur + 1 until all.size).filter { all[it].section == "queued" }
    val radio = (cur + 1 until all.size).filter { all[it].section == "radio" }
    if (queued.isNotEmpty()) { rows.add("You queued" to -1); queued.forEach { rows.add(null to it) } }
    if (radio.isNotEmpty()) { rows.add("Radio" to -1); radio.forEach { rows.add(null to it) } }
    if (queued.isEmpty() && radio.isEmpty()) rows.add((if (Store.stopAtEnd) "Nothing next: autoplay is off" else "Nothing next yet") to -1)
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(rows) { _, (header, i) ->
            if (header != null) {
                Text(header, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            } else {
                val e = all[i]
                SongRow(e.song, highlight = i == cur, extra = e.song.reason, onMenu = { menuFor = i }) { Playback.player.seekTo(i, 0) }
            }
        }
    }
    menuFor?.let { i -> if (i in all.indices) SongMenu(all[i].song, queueIndex = i) { menuFor = null } }
}

@Composable
private fun LyricsView(lines: List<Line>, synced: Boolean, songId: String?) {
    // Playback.tick fires every 500 ms, too coarse for word timing: poll the position every 50 ms while lyrics show.
    var pos by remember { mutableLongStateOf(Playback.player.currentPosition) }
    LaunchedEffect(Unit) { while (true) { pos = Playback.player.currentPosition; delay(50) } }
    val current = if (synced) lines.indexOfLast { it.startMs <= pos } else -1
    val state = rememberLazyListState()
    val showOriginal = Store.bool("lyrics.layer.original", true)
    val showLatin = Store.bool("lyrics.layer.latin", true)
    // KuGou and NetEase translate into Chinese: show a translation only in the chosen language.
    val translationLang = remember(lines) { roma.core.translationLanguage(roma.core.Lyrics("", lines, synced)) }
    val showTranslation = Store.bool("lyrics.layer.translation", true) && Store.translationWanted(translationLang)
    var fixWord by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current) { if (current > 2) state.animateScrollToItem(current - 2) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), state = state) {
        itemsIndexed(lines) { i, line ->
            val active = i == current
            val alpha = if (!synced || active) 1f else 0.45f
            Column(
                Modifier.fillMaxWidth().alpha(alpha)
                    .pointerInput(line) { detectTapGestures(onTap = { if (synced) Playback.player.seekTo(line.startMs) }) }
                    .padding(vertical = 8.dp),
            ) {
                val wordIndex = if (active) line.words.indexOfLast { it.startMs <= pos } else -1
                // Same weight on every line: bolding the active line widened it and re-wrapped long lines mid-song.
                val big = MaterialTheme.typography.titleLarge.copy(lineBreak = LYRIC_BREAK)
                if (showOriginal && line.text.isNotBlank()) {
                    WordsText(line.words.map { it.text }, line.text, wordIndex, big, onTap = { if (synced) Playback.player.seekTo(line.startMs) }) { w -> fixWord = w }
                }
                if (showLatin && line.latin.isNotBlank() && line.latin != line.text) {
                    WordsText(line.wordLatin, line.latin, wordIndex, MaterialTheme.typography.bodyLarge, onLongPressWord = null)
                }
                val translation = line.translation
                if (showTranslation && !translation.isNullOrBlank()) {
                    Text(translation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    fixWord?.let { w -> if (songId != null) FixReadingDialog(w, songId) { fixWord = null } }
}

/**
 * A lyric layer. In karaoke mode (current >= 0) sung words are full colour, the current word is accent-coloured on a
 * tinted background, and words still to come are dimmed. Long-pressing a word offers to fix its reading.
 */
@Composable
private fun WordsText(words: List<String>, whole: String, current: Int, style: androidx.compose.ui.text.TextStyle, onTap: () -> Unit = {}, onLongPressWord: ((String) -> Unit)?) {
    val hi = MaterialTheme.colorScheme.primary
    val sung = SpanStyle(color = MaterialTheme.colorScheme.onSurface)
    val now = SpanStyle(color = hi, background = hi.copy(alpha = 0.18f))
    val coming = SpanStyle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
    val ranges = ArrayList<IntRange>()
    val text: AnnotatedString = if (words.isEmpty()) AnnotatedString(whole) else buildAnnotatedString {
        words.forEachIndexed { i, w ->
            val start = length
            val st = when {
                current < 0 -> null
                i < current -> sung
                i == current -> now
                else -> coming
            }
            if (st == null) append(w) else withStyle(st) { append(w) }
            ranges.add(start until length)
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        style = style.copy(lineBreak = LYRIC_BREAK),
        onTextLayout = { layout = it },
        modifier = if (onLongPressWord == null) Modifier else Modifier.pointerInput(text) {
            detectTapGestures(
                onTap = { onTap() },
                onLongPress = { o ->
                    val offset = layout?.getOffsetForPosition(o) ?: return@detectTapGestures
                    val word = ranges.indexOfFirst { offset in it }.takeIf { it >= 0 }?.let { words[it].trim() }
                        ?: wordAt(whole, offset)
                    if (word.isNotBlank()) onLongPressWord(word)
                },
            )
        },
    )
}

private fun wordAt(s: String, offset: Int): String {
    if (s.isEmpty()) return ""
    val i = offset.coerceIn(0, s.length - 1)
    var a = i
    var b = i
    while (a > 0 && !s[a - 1].isWhitespace()) a--
    while (b < s.length && !s[b].isWhitespace()) b++
    return s.substring(a, b)
}

@Composable
private fun FixReadingDialog(word: String, songId: String, onDone: () -> Unit) {
    val existing = Store.romanizer.overrides.firstOrNull { it.scope == "song" && it.id == songId && it.original == word }?.latin
    TextInputDialog("Reading of “$word” in this song", existing ?: Store.romanizer.machine(word, sentence = true), onDone) { latin ->
        Store.saveOverride(Override("song", songId, word, latin.trim()))
    }
}

@Composable
fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text, textAlign = TextAlign.Center) }
}

/** A speech-bubble-like lyrics icon; material-icons-core has none. */
val LyricsIcon: ImageVector by lazy {
    ImageVector.Builder("lyrics", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M4,4h16a2,2 0,0 1,2 2v10a2,2 0,0 1,-2 2H8l-4,4V6a2,2 0,0 1,2,-2zM7,8v2h10V8zM7,12v2h7v-2z",
            ).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
        )
    }.build()
}

// Balanced wrapping evens out the line lengths instead of leaving one word on the last line; Phrase keeps Japanese
// phrases (bunsetsu) together instead of breaking between any two characters (Android 13+, ignored before).
private val LYRIC_BREAK = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Phrase)
