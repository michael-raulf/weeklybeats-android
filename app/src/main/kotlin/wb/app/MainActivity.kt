package wb.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF010103)
private val Panel = Color(0xFF202022)
private val Ink = Color(0xFFEEEEEE)
private val Colors = darkColorScheme(
    primary = Color(0xFF00F3FF),
    onPrimary = Color.Black,
    background = Bg,
    onBackground = Ink,
    surface = Bg,
    onSurface = Ink,
    surfaceVariant = Panel,
    onSurfaceVariant = Ink,
    surfaceContainerHighest = Panel,
)

const val PLAY = "▶︎"
const val PAUSE = "❚❚"
const val NEXT = "⏭︎"
const val PREV = "⏮︎"

private const val FOCUS_COMMENT = "document.querySelectorAll('.embed_video').forEach(function(e){e.remove()});" +
    "var c=document.getElementById('comment')||document.getElementById('item_send_comment');" +
    "if(c){c.scrollIntoView({block:'center'});c.focus()}"

sealed interface Screen
data object WeeksScreen : Screen
data class WeekScreen(val n: Int) : Screen
data class TrackScreen(val track: Track) : Screen

fun Track.toItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(mp3)).build())
    .setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist(artist)
            .setExtras(Bundle().apply { putString("link", link) }).build()
    )
    .build()

fun MediaItem.toTrack() = Track(
    mediaId,
    mediaMetadata.title?.toString().orEmpty(),
    mediaMetadata.artist?.toString().orEmpty(),
    mediaMetadata.extras?.getString("link").orEmpty(),
    requestMetadata.mediaUri?.toString().orEmpty(),
)

class MainActivity : ComponentActivity() {
    private val stack = mutableStateListOf<Screen>(WeeksScreen)
    private var controller by mutableStateOf<MediaController?>(null)
    private var current by mutableStateOf<MediaItem?>(null)
    private var playing by mutableStateOf(false)
    private var pendingComment = false
    private lateinit var future: ListenableFuture<MediaController>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        pendingComment = intent.getBooleanExtra("comment", false)
        future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    current = player.currentMediaItem
                    playing = player.isPlaying
                }
            })
            current = c.currentMediaItem
            playing = c.isPlaying
            controller = c
            if (pendingComment) openCurrent()
        }, mainExecutor)
        setContent {
            MaterialTheme(Colors) {
                Surface(Modifier.fillMaxSize()) {
                    Root()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("comment", false)) openCurrent()
    }

    override fun onDestroy() {
        MediaController.releaseFuture(future)
        super.onDestroy()
    }

    private fun pop() {
        stack.removeAt(stack.lastIndex)
    }

    private fun openCurrent() {
        pendingComment = false
        val item = controller?.currentMediaItem ?: return
        if (stack.last() is TrackScreen) pop()
        stack += TrackScreen(item.toTrack())
    }

    private fun play(queue: List<Track>, track: Track) {
        val c = controller ?: return
        if (c.currentMediaItem?.mediaId == track.id) {
            if (c.isPlaying) c.pause() else c.play()
            return
        }
        c.setMediaItems(queue.map { it.toItem() }, queue.indexOf(track).coerceAtLeast(0), 0)
        c.prepare()
        c.play()
    }

    @Composable
    private fun Root() {
        val top = stack.last()
        BackHandler(stack.size > 1) { pop() }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(Modifier.weight(1f)) {
                key(top) {
                    when (top) {
                        WeeksScreen -> Weeks()
                        is WeekScreen -> Week(top.n)
                        is TrackScreen -> TrackPage(top.track)
                    }
                }
            }
            NowPlaying()
        }
    }

    @Composable
    private fun Weeks() {
        val n = Wb.latestWeek()
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Weeklybeats $YEAR", style = MaterialTheme.typography.titleLarge)
            Card(
                Modifier.fillMaxWidth().clickable { stack += WeekScreen(n) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text("Current week", style = MaterialTheme.typography.labelLarge)
                    Text("Week $n", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                }
            }
            for (w in n - 1 downTo maxOf(1, n - 4)) {
                Card(Modifier.fillMaxWidth().clickable { stack += WeekScreen(w) }) {
                    Text("Week $w", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    @Composable
    private fun Week(n: Int) {
        var retry by remember { mutableIntStateOf(0) }
        val tracks by produceState<Result<List<Track>>?>(null, retry) {
            value = withContext(Dispatchers.IO) { runCatching { Wb.week(n) } }
        }
        val t = tracks
        val played by Wb.played
        Column(Modifier.fillMaxSize()) {
            Text("Week $n", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
            when {
                t == null -> Text("Loading…", Modifier.padding(16.dp))
                t.isFailure -> Failed(t.exceptionOrNull()) { retry++ }
                else -> {
                    val list = t.getOrThrow()
                    val next = list.firstOrNull { it.id !in played }
                    val left = list.count { it.id !in played }
                    Button(
                        onClick = { next?.let { play(list, it) } },
                        enabled = next != null,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Text(if (next != null) "$PLAY  Play next unplayed ($left left)" else "All played")
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(list, key = { it.id }) { track ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { stack += TrackScreen(track) }
                                    .alpha(if (track.id in played && current?.mediaId != track.id) 0.5f else 1f)
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val isCurrent = current?.mediaId == track.id
                                TextButton(onClick = { play(list, track) }) {
                                    Text(if (isCurrent && playing) PAUSE else PLAY)
                                }
                                Column {
                                    Text(
                                        track.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    Text(track.artist, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TrackPage(track: Track) {
        var web by remember { mutableStateOf<WebView?>(null) }
        BackHandler {
            val w = web
            if (w != null && w.canGoBack()) w.goBack() else pop()
        }
        AndroidView(modifier = Modifier.fillMaxSize(), onRelease = { it.destroy() }, factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Bg.toArgb())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame || request.url.host?.endsWith("weeklybeats.com") == true) return false
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        CookieManager.getInstance().flush()
                        view.requestFocus()
                        view.evaluateJavascript(FOCUS_COMMENT, null)
                    }
                }
                loadUrl(track.link)
                web = this
            }
        })
    }

    @Composable
    private fun Failed(e: Throwable?, retry: () -> Unit) {
        Column(Modifier.padding(16.dp)) {
            Text(e?.message ?: "Failed", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = retry) { Text("Retry") }
        }
    }

    @Composable
    private fun NowPlaying() {
        val c = controller ?: return
        val item = current ?: return
        Surface(color = Panel) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable { stack += TrackScreen(item.toTrack()) }
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${item.mediaMetadata.artist} – ${item.mediaMetadata.title}",
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { c.seekToPrevious() }) { Text(PREV) }
                TextButton(onClick = { if (playing) c.pause() else c.play() }) { Text(if (playing) PAUSE else PLAY) }
                TextButton(onClick = { c.seekToNext() }) { Text(NEXT) }
            }
        }
    }
}
