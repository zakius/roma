package com.zakius.roma

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import roma.core.LastFm
import coil3.toBitmap
import coil3.request.allowHardware

/** A page opened from a list: an album, playlist or artist, or the sign-in page. */
class Page(val kind: String, val id: String)

const val TAB_HOME = 0
const val TAB_SEARCH = 1
const val TAB_LIBRARY = 2
const val TAB_PLAYER = 3
const val TAB_SETTINGS = 4

/** Navigation state, shared so any screen can open a page or switch tabs. The tab and the player's view (song,
 *  lyrics, queue) survive a restart; Root saves them. */
object Nav {
    private var shownTab by mutableStateOf(Store.prefs.getInt("ui.tab", TAB_HOME))
    var tab: Int
        get() = shownTab
        set(v) { if (v != shownTab) { before = shownTab; shownTab = v } }
    /** The tab shown before this one: Back and a swipe down on the player return there. */
    var before = TAB_HOME
        private set
    var playerView by mutableStateOf(Store.prefs.getString("ui.playerView", null) ?: "song")
    val stacks = List(5) { mutableStateListOf<Page>() }

    fun open(p: Page) {
        if (tab == TAB_PLAYER) tab = TAB_HOME
        stacks[tab].add(p)
    }

    fun leavePlayer() {
        tab = if (before != TAB_PLAYER) before else TAB_HOME
    }
}

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Back on Home leaves roma in the background instead of closing it, as Android 12+ does: the next start then
        // finds the activity and its UI still there.
        if (Build.VERSION.SDK_INT < 31) onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        // The media notification matters once something plays, so that's when it's asked for, and only once: asking at
        // every start opened the system's permission activity over ours, even after the user refused for good.
        val notify = android.Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 33 && !Store.bool("notify.asked", false) && checkSelfPermission(notify) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch {
                snapshotFlow { Playback.tick; Playback.player.isPlaying }.first { it }
                Store.set("notify.asked", true)
                requestPermissions(arrayOf(notify), 0)
            }
        }
        handleLastFm(intent)
        Home.start() // before the first frame, so Home's request and its disk copy are on their way
        setContent { Theme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLastFm(intent)
    }

    // Binding a controller starts the service, which then shows the media notification while playing.
    override fun onStart() {
        super.onStart()
        Home.start() // back in the foreground: a Home older than 30 minutes loads again behind the shown one
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onResume() {
        super.onResume()
        Dictionary.resumed(this)
        Dictionary.ensure(this)
    }

    override fun onStop() {
        controller?.let { MediaController.releaseFuture(it) }
        super.onStop()
    }

    // Last.fm's browser auth returns to roma://lastfm?token=...
    private fun handleLastFm(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "roma" || data.host != "lastfm") return
        val token = data.getQueryParameter("token") ?: return
        val fm = LastFm(Store.str("lastfm.key", ""), Store.str("lastfm.secret", ""))
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { runCatching { fm.session(token) } }
            result.onSuccess {
                Store.set("lastfm.session", it)
                Playback.flushScrobbles()
                Toast.makeText(this@MainActivity, "Last.fm connected", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(this@MainActivity, "Last.fm: ${it.message}", Toast.LENGTH_LONG).show() }
            Nav.tab = TAB_SETTINGS
        }
    }
}

@Composable
fun Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = LocalContext.current
    var scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    // Options, both off by default. True black: OLED pixels that are off draw no power. Artwork colours: the accent
    // follows the playing song's art (one 24 px decode per song).
    if (dark && Store.bool("ui.trueBlack", false)) {
        val black = androidx.compose.ui.graphics.Color.Black
        scheme = scheme.copy(background = black, surface = black, surfaceContainer = black, surfaceContainerLowest = black)
    }
    if (Store.bool("ui.artColors", false)) {
        Playback.tick
        val thumb = Playback.shown()?.thumb
        var seed by androidx.compose.runtime.remember { mutableStateOf<Int?>(null) }
        androidx.compose.runtime.LaunchedEffect(thumb) { seed = withContext(Dispatchers.IO) { thumb?.let { artColor(c, it) } } }
        seed?.let { scheme = accentScheme(scheme, it, dark) }
    }
    MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize(), content = content) }
}

/** The art's most colourful average: pixels weighted by saturation, so a grey background doesn't wash it out. */
private suspend fun artColor(c: android.content.Context, thumb: String): Int? {
    val request = coil3.request.ImageRequest.Builder(c).data(bigThumb(thumb)).size(24).allowHardware(false).build() // pixels are read below
    val bitmap = (coil3.SingletonImageLoader.get(c).execute(request) as? coil3.request.SuccessResult)?.image?.toBitmap() ?: return null
    var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
    val hsl = FloatArray(3)
    for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) {
        val px = bitmap.getPixel(x, y)
        androidx.core.graphics.ColorUtils.colorToHSL(px, hsl)
        val weight = (hsl[1] * (1 - kotlin.math.abs(hsl[2] * 2 - 1))).toDouble() // saturated and not near black or white
        r += android.graphics.Color.red(px) * weight; g += android.graphics.Color.green(px) * weight; b += android.graphics.Color.blue(px) * weight; w += weight
    }
    if (w < bitmap.width * bitmap.height * 0.05) return null // a grey picture: keep the usual colours
    return android.graphics.Color.rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt())
}

/** The scheme's accents in the art's hue, at the lightness the theme needs for contrast. */
private fun accentScheme(s: androidx.compose.material3.ColorScheme, seed: Int, dark: Boolean): androidx.compose.material3.ColorScheme {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(seed, hsl)
    fun tone(sat: Float, light: Float) = androidx.compose.ui.graphics.Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hsl[0], sat, light)))
    val sat = hsl[1].coerceIn(0.35f, 0.8f)
    return if (dark) s.copy(primary = tone(sat, 0.75f), onPrimary = tone(sat, 0.15f), secondaryContainer = tone(sat * 0.6f, 0.25f), onSecondaryContainer = tone(sat, 0.9f), inversePrimary = tone(sat, 0.4f))
    else s.copy(primary = tone(sat, 0.38f), onPrimary = tone(sat, 0.98f), secondaryContainer = tone(sat * 0.6f, 0.88f), onSecondaryContainer = tone(sat, 0.15f), inversePrimary = tone(sat, 0.8f))
}

@Composable
fun Root() {
    val tab = Nav.tab
    val stack = Nav.stacks[tab]
    BackHandler(enabled = stack.isNotEmpty() || tab != TAB_HOME) {
        // Picking a song jumps to the player, so leaving it returns to the list it was picked from.
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else if (tab == TAB_PLAYER) Nav.leavePlayer() else Nav.tab = TAB_HOME
    }
    if (!Store.bool("setup.done", false)) return Box(Modifier.fillMaxSize().safeDrawingPadding()) { SetupScreen() }
    DictionaryPrompt()
    Playback.tick // redraw when the player changes, for the Player tab's dimming
    val loaded = Playback.player.mediaItemCount > 0 || Playback.pending != null || Playback.restoring
    androidx.compose.runtime.LaunchedEffect(tab, Nav.playerView) {
        Store.prefs.edit().putInt("ui.tab", tab).putString("ui.playerView", Nav.playerView).apply()
    }
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.weight(1f)) {
            val page = stack.lastOrNull()
            // Each tab and page keeps its saved state (scroll position, search text) while another one is shown, so
            // going back lands where it was. ponytail: states of closed pages stay until the app ends; small.
            saved.SaveableStateProvider(if (page != null) "$tab/${stack.size}/${page.kind}/${page.id}" else "$tab") {
                when {
                    page != null -> PageScreen(page)
                    tab == TAB_HOME -> HomeScreen()
                    tab == TAB_SEARCH -> SearchScreen()
                    tab == TAB_LIBRARY -> LibraryScreen()
                    tab == TAB_PLAYER -> PlayerScreen()
                    else -> SettingsScreen()
                }
            }
        }
        // Fixed order, Player in the middle; Player is dimmed until something is loaded. Compact (48 dp): icon and a small
        // label, no pill, so the player's controls get the height.
        FixedRowsFont { NavBar(tab, loaded) }
    }
}

@Composable
private fun NavBar(tab: Int, loaded: Boolean) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surfaceContainer)) {
            NavItem("Home", Icons.Default.Home, tab == TAB_HOME, true) { Nav.tab = TAB_HOME; Nav.stacks[TAB_HOME].clear() }
            NavItem("Search", Icons.Default.Search, tab == TAB_SEARCH, true) { Nav.tab = TAB_SEARCH; Nav.stacks[TAB_SEARCH].clear() }
            NavItem("Player", Icons.Default.PlayArrow, tab == TAB_PLAYER, loaded) { Nav.tab = TAB_PLAYER }
            NavItem("Library", Icons.Default.List, tab == TAB_LIBRARY, true) { Nav.tab = TAB_LIBRARY; Nav.stacks[TAB_LIBRARY].clear() }
            NavItem("Settings", Icons.Default.Settings, tab == TAB_SETTINGS, true) { Nav.tab = TAB_SETTINGS; Nav.stacks[TAB_SETTINGS].clear() }
        }
}

/**
 * The fixed-height rows (player controls, nav bar) follow the system font size only up to 1.3×: larger, their labels
 * and times would be cut off, and the rows can't grow without moving the controls. Everything else scales fully.
 */
@Composable
fun FixedRowsFont(content: @Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    if (d.fontScale <= 1.3f) return content()
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 1.3f), content = content)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.layout.Column(
        Modifier.weight(1f).fillMaxHeight()
            .selectable(selected = selected, enabled = enabled, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = color)
        Text(label, fontSize = 10.sp, lineHeight = 12.sp, color = color, maxLines = 1)
    }
}
