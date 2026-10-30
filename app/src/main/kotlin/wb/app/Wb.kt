package wb.app

import android.app.Application
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.compose.runtime.mutableStateOf
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

const val BASE = "https://weeklybeats.com"

private fun start(year: Int) = LocalDate.of(year, 1, 4)
    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    .atStartOfDay().toInstant(ZoneOffset.UTC)

val YEAR = generateSequence(LocalDate.now(ZoneOffset.UTC).year + 1) { it - 1 }
    .first { it % 2 == 0 && start(it).plus(Duration.ofDays(7)) <= Instant.now() }

data class Track(val id: String, val title: String, val artist: String, val link: String, val mp3: String)
data class Comment(val author: String, val date: String, val html: String)
data class TrackPage(val description: String, val comments: List<Comment>)

class LoggedOut : Exception()

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Wb.app = this
    }
}

object Wb {
    lateinit var app: Context
    private val http = OkHttpClient()
    private val prefs by lazy { app.getSharedPreferences("wb", Context.MODE_PRIVATE) }
    private val ua by lazy { WebSettings.getDefaultUserAgent(app) }

    val played by lazy { mutableStateOf(prefs.getStringSet("played", emptySet())!!.toSet()) }
    val drafts = mutableMapOf<String, String>()

    var loggedIn: Boolean
        get() = prefs.getBoolean("logged_in", false)
        set(v) = prefs.edit().putBoolean("logged_in", v).apply()

    fun markPlayed(id: String) {
        played.value = played.value + id
        prefs.edit().putStringSet("played", played.value).apply()
    }

    private fun Request.Builder.auth() = apply {
        header("User-Agent", ua)
        CookieManager.getInstance().getCookie(BASE)?.let { header("Cookie", it) }
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).auth().build()).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            it.body!!.string()
        }

    fun latestWeek(): Int = (Duration.between(start(YEAR), Instant.now()).toDays() / 7).toInt().coerceIn(1, 52)

    fun week(n: Int): List<Track> {
        val cache = File(app.filesDir, "week-$YEAR-$n.json")
        if (cache.exists()) return fromJson(cache.readText())
        val tracks = mutableListOf<Track>()
        var page = 1
        while (true) {
            val found = parseWeek(get("$BASE/music?p=$page&r=1&s=tag:week+$n+$YEAR&f=8&y=$YEAR&_content_only=1"), n) ?: break
            tracks += found
            page++
        }
        val unique = tracks.distinctBy { it.id }
        if (unique.isEmpty()) throw IOException("No tracks found")
        cache.writeText(toJson(unique))
        return unique
    }

    private fun parseWeek(html: String, week: Int): List<Track>? {
        val items = Jsoup.parse(html).select("div.main-item[id^=listitem]")
        if (items.isEmpty()) return null
        return items.mapNotNull { e ->
            if (e.selectFirst(".info-views strong")?.text() != week.toString()) return@mapNotNull null
            val a = e.selectFirst("h3 a") ?: return@mapNotNull null
            val onclick = e.selectFirst(".player-play")?.attr("onclick") ?: return@mapNotNull null
            val mp3 = Regex("setPlaylistItem\\('(.+?)'\\)").find(onclick)?.groupValues?.get(1) ?: return@mapNotNull null
            Track(
                e.id().removePrefix("listitem"),
                a.text(),
                e.selectFirst("cite")?.text().orEmpty(),
                a.attr("href"),
                mp3.replace("\\'", "'"),
            )
        }
    }

    private fun toJson(tracks: List<Track>) = JSONArray(tracks.map {
        JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
            .put("link", it.link).put("mp3", it.mp3)
    }).toString()

    private fun fromJson(s: String): List<Track> {
        val a = JSONArray(s)
        return List(a.length()) {
            val o = a.getJSONObject(it)
            Track(o.getString("id"), o.getString("title"), o.getString("artist"), o.getString("link"), o.getString("mp3"))
        }
    }

    fun trackPage(track: Track): TrackPage {
        val html = get(track.link)
        val description = html.substringAfter("<p id=\"item_description\">", "").substringBefore("<div id=\"item_license\">")
        val comments = Jsoup.parse(html).select("#item_comments .post[id^=comment]").mapNotNull { e ->
            val author = e.selectFirst(".username")?.text() ?: return@mapNotNull null
            val body = e.selectFirst(".post-entry") ?: return@mapNotNull null
            body.select(".q_comment").remove()
            Comment(author, e.selectFirst(".post-link")?.text().orEmpty(), clean(body.html()))
        }
        return TrackPage(clean(description), comments)
    }

    private fun clean(html: String): String {
        val body = Jsoup.parseBodyFragment(html).body()
        body.select("iframe,embed,object,video,audio,script,style,.embed_video").remove()
        body.select("img").forEach { img ->
            if ("/smilies/" in img.attr("src")) img.replaceWith(TextNode(":${img.attr("alt")}:"))
            else img.remove()
        }
        body.select("p").filter { !it.hasText() }.forEach { it.remove() }
        return body.html()
    }

    fun comment(track: Track, text: String) {
        val form = FormBody.Builder().add("comment", "comment=" + URLEncoder.encode(text, "UTF-8")).build()
        val req = Request.Builder().url("$BASE/music/comment/add/${track.id}").post(form).auth()
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", track.link)
        val response = http.newCall(req.build()).execute().use { it.body!!.string() }
        if (response == "Comment Added.") return
        if ("navlogin" in response || "logged in" in response.lowercase()) throw LoggedOut()
        throw IOException(Jsoup.parse(response).text().take(200).ifBlank { "Comment failed" })
    }
}
