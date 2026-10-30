package wb.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
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
import kotlinx.coroutines.launch
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

sealed interface Screen
data object LoginScreen : Screen
data object WeeksScreen : Screen
data class WeekScreen(val n: Int) : Screen
data class DetailScreen(val track: Track, val queue: List<Track>, val focus: Boolean) : Screen

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
        if (!Wb.loggedIn) stack += LoginScreen
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
            if (pendingComment) openComment()
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
        if (intent.getBooleanExtra("comment", false)) openComment()
    }

    override fun onDestroy() {
        MediaController.releaseFuture(future)
        super.onDestroy()
    }

    private fun pop() {
        stack.removeAt(stack.lastIndex)
    }

    private fun openComment() {
        pendingComment = false
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        if (stack.last() == LoginScreen) return
        if (stack.last() is DetailScreen) pop()
        stack += DetailScreen(item.toTrack(), queue(c), true)
    }

    private fun queue(c: Player) = List(c.mediaItemCount) { c.getMediaItemAt(it).toTrack() }

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
        BackHandler(stack.size > 1 && top != LoginScreen) { pop() }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(Modifier.weight(1f)) {
                key(top) {
                    when (top) {
                        LoginScreen -> Login()
                        WeeksScreen -> Weeks()
                        is WeekScreen -> Week(top.n)
                        is DetailScreen -> Detail(top)
                    }
                }
            }
            if (top != LoginScreen) NowPlaying()
        }
    }

    @Composable
    private fun Login() {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Bg.toArgb())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val js = "(function(){var r=document.getElementById('fld3');if(r)r.checked=true;" +
                            "return document.getElementById('navforum')!=null&&document.getElementById('navlogin')==null})()"
                        view.evaluateJavascript(js) {
                            if (it == "true" && stack.last() == LoginScreen) {
                                CookieManager.getInstance().flush()
                                Wb.loggedIn = true
                                pop()
                            }
                        }
                    }
                }
                loadUrl("$BASE/forums/login/")
            }
        })
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
                                    .clickable { stack += DetailScreen(track, list, false) }
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
    private fun Detail(screen: DetailScreen) {
        val track = screen.track
        var reload by remember { mutableIntStateOf(0) }
        val page by produceState<Result<TrackPage>?>(null, reload) {
            value = withContext(Dispatchers.IO) { runCatching { Wb.trackPage(track) } }
        }
        var draft by remember { mutableStateOf(Wb.drafts[track.id].orEmpty()) }
        var sending by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { if (screen.focus) focus.requestFocus() }
        val p = page
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(track.title, style = MaterialTheme.typography.titleLarge)
                            Text(track.artist, style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(onClick = { play(screen.queue, track) }) {
                            Text(if (current?.mediaId == track.id && playing) PAUSE else PLAY)
                        }
                    }
                }
                when {
                    p == null -> item { Text("Loading…") }
                    p.isFailure -> item { Failed(p.exceptionOrNull()) { reload++ } }
                    else -> {
                        val data = p.getOrThrow()
                        item { Html(data.description) }
                        item { HorizontalDivider() }
                        items(data.comments) {
                            Column {
                                Text("${it.author} · ${it.date}", style = MaterialTheme.typography.labelMedium)
                                Html(it.html)
                            }
                        }
                    }
                }
            }
            error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    draft,
                    {
                        draft = it
                        Wb.drafts[track.id] = it
                    },
                    Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("Comment") },
                )
                TextButton(
                    enabled = draft.isNotBlank() && !sending,
                    onClick = {
                        sending = true
                        error = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { Wb.comment(track, draft.trim()) }
                                draft = ""
                                Wb.drafts.remove(track.id)
                                reload++
                            } catch (e: LoggedOut) {
                                Wb.loggedIn = false
                                stack += LoginScreen
                            } catch (e: Exception) {
                                error = e.message ?: "Comment failed"
                            }
                            sending = false
                        }
                    },
                ) { Text(if (sending) "Sending" else "Send") }
            }
        }
    }

    @Composable
    private fun Html(html: String) {
        val color = MaterialTheme.colorScheme.primary
        val text = remember(html, color) {
            val s = AnnotatedString.fromHtml(
                html,
                TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
            )
            s.subSequence(0, s.text.trimEnd().length)
        }
        Text(text)
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
                    .clickable { stack += DetailScreen(item.toTrack(), queue(c), false) }
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
