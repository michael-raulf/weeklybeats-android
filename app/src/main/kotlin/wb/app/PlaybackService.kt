package wb.app

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var last = -1

    override fun onCreate() {
        super.onCreate()
        val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                item ?: return
                val i = player.currentMediaItemIndex
                val auto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                val forward = auto || (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && i == (last + 1) % player.mediaItemCount)
                last = i
                if (forward && item.mediaId in Wb.played.value) {
                    val next = nextUnplayed(player)
                    if (next >= 0) {
                        player.seekTo(next, 0)
                        return
                    }
                    if (auto) {
                        player.pause()
                        return
                    }
                }
                Wb.markPlayed(item.mediaId)
            }

            override fun onPlayerError(error: PlaybackException) {
                val next = nextUnplayed(player)
                if (next < 0) return
                player.seekTo(next, 0)
                player.prepare()
                player.play()
            }
        })
        val open = Intent(this, MainActivity::class.java).putExtra("comment", true)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { it.buildUpon().setUri(it.requestMetadata.mediaUri).build() }.toMutableList()
                )
            })
            .build()
    }

    private fun nextUnplayed(p: Player): Int {
        val n = p.mediaItemCount
        return (1 until n).map { (p.currentMediaItemIndex + it) % n }
            .firstOrNull { p.getMediaItemAt(it).mediaId !in Wb.played.value } ?: -1
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
