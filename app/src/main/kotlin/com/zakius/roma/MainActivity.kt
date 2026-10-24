package com.zakius.roma

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import roma.core.LastFm

/** A page opened from a list: an album, playlist or artist, or the sign-in page. */
class Page(val kind: String, val id: String)

const val TAB_HOME = 0
const val TAB_SEARCH = 1
const val TAB_LIBRARY = 2
const val TAB_PLAYER = 3
const val TAB_SETTINGS = 4

/** Navigation state, shared so any screen can open a page or switch tabs. */
object Nav {
    var tab by mutableStateOf(TAB_HOME)
    val stacks = List(5) { mutableStateListOf<Page>() }

    fun open(p: Page) {
        if (tab == TAB_PLAYER) tab = TAB_HOME
        stacks[tab].add(p)
    }
}

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        handleLastFm(intent)
        setContent { Theme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLastFm(intent)
    }

    // Binding a controller starts the service, which then shows the media notification while playing.
    override fun onStart() {
        super.onStart()
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
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
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize(), content = content) }
}

@Composable
fun Root() {
    val tab = Nav.tab
    val stack = Nav.stacks[tab]
    BackHandler(enabled = stack.isNotEmpty() || tab != TAB_HOME) {
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else Nav.tab = TAB_HOME
    }
    Playback.tick // redraw when the player changes, for the Player tab's dimming
    val loaded = Playback.player.mediaItemCount > 0 || Playback.pending != null
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.weight(1f)) {
            val page = stack.lastOrNull()
            when {
                page != null -> PageScreen(page)
                tab == TAB_HOME -> HomeScreen()
                tab == TAB_SEARCH -> SearchScreen()
                tab == TAB_LIBRARY -> LibraryScreen()
                tab == TAB_PLAYER -> PlayerScreen()
                else -> SettingsScreen()
            }
        }
        // Fixed order; Player is dimmed until something is loaded.
        NavigationBar {
            NavItem("Home", Icons.Default.Home, tab == TAB_HOME, true) { Nav.tab = TAB_HOME; Nav.stacks[TAB_HOME].clear() }
            NavItem("Search", Icons.Default.Search, tab == TAB_SEARCH, true) { Nav.tab = TAB_SEARCH; Nav.stacks[TAB_SEARCH].clear() }
            NavItem("Library", Icons.Default.List, tab == TAB_LIBRARY, true) { Nav.tab = TAB_LIBRARY; Nav.stacks[TAB_LIBRARY].clear() }
            NavItem("Player", Icons.Default.PlayArrow, tab == TAB_PLAYER, loaded) { Nav.tab = TAB_PLAYER }
            NavItem("Settings", Icons.Default.Settings, tab == TAB_SETTINGS, true) { Nav.tab = TAB_SETTINGS; Nav.stacks[TAB_SETTINGS].clear() }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    NavigationBarItem(selected = selected, onClick = onClick, enabled = enabled, icon = { Icon(icon, null) }, label = { Text(label) })
}
