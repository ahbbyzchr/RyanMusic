package cn.ryanmusic.app

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject

/** Mirrors WebView playback; no audio is loaded or decoded by Media3. */
@OptIn(UnstableApi::class)
class PlaybackSessionService : MediaSessionService() {
    inner class LocalBinder : Binder() { val service: PlaybackSessionService get() = this@PlaybackSessionService }
    private val localBinder = LocalBinder()
    private lateinit var mirror: WebMirrorPlayer
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private var hasFocus = false
    var commandSink: ((String, Long?) -> Unit)? = null

    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    hasFocus = false
                    commandSink?.invoke("pause", null)
                }
            }.build()
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        mirror = WebMirrorPlayer { action, position -> commandSink?.invoke(action, position) }
        val activity = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, mirror).setSessionActivity(activity).build()
        addSession(session)
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ACTION_LOCAL_BIND) localBinder else super.onBind(intent)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    fun updatePlayback(json: String) {
        val payload = try { JSONObject(json) } catch (_: Exception) { return }
        val state = payload.optString("state")
        if (state !in setOf("playing", "paused", "buffering", "stopped")) return
        mirror.update(payload)
        if (state == "playing" || state == "buffering") {
            if (!hasFocus) {
                hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                if (!hasFocus) commandSink?.invoke("pause", null)
            }
        } else if (hasFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            hasFocus = false
        }
        if (state == "stopped") stopSelf()
    }

    override fun onDestroy() {
        if (hasFocus) audioManager.abandonAudioFocusRequest(focusRequest)
        commandSink = null
        session.release()
        mirror.release()
        super.onDestroy()
    }

    companion object { const val ACTION_LOCAL_BIND = "cn.ryanmusic.app.BIND_PLAYBACK" }
}

@OptIn(UnstableApi::class)
internal class WebMirrorPlayer(private val command: (String, Long?) -> Unit) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var payload = JSONObject().put("state", "stopped")

    fun update(value: JSONObject) {
        payload = value
        invalidateState()
    }

    override fun getState(): State {
        val state = payload.optString("state", "stopped")
        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_RELEASE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
        if (state != "stopped") {
            commands.add(Player.COMMAND_PLAY_PAUSE).add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            if (payload.optBoolean("canSkipNext", true)) commands.add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            if (payload.optBoolean("canSkipPrev", true)) commands.add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        val builder = State.Builder().setAvailableCommands(commands.build())
            .setPlayWhenReady(state == "playing" || state == "buffering", Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(when (state) {
                "playing", "paused" -> Player.STATE_READY
                "buffering" -> Player.STATE_BUFFERING
                else -> Player.STATE_IDLE
            })
        if (state != "stopped") {
            val metadata = MediaMetadata.Builder().setTitle(payload.optString("title", ""))
                .setArtist(payload.optString("artist", ""))
            val artwork = payload.optString("artworkUrl", "")
            if (artwork.startsWith("https://") || artwork.startsWith("http://")) metadata.setArtworkUri(Uri.parse(artwork))
            val item = MediaItem.Builder().setMediaId("web-playback").setMediaMetadata(metadata.build()).build()
            val durationMs = payload.optLong("durationMs", 0).coerceAtLeast(0)
            val itemData = MediaItemData.Builder("web-playback").setMediaItem(item).setIsSeekable(true)
                .setDurationUs(if (durationMs > 0) durationMs.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 else C.TIME_UNSET)
                .build()
            builder.setPlaylist(listOf(itemData)).setCurrentMediaItemIndex(0)
                .setContentPositionMs(payload.optLong("positionMs", 0).coerceAtLeast(0))
        }
        return builder.build()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        command(if (playWhenReady) "play" else "pause", null)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> command("next", null)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> command("previous", null)
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> command("seek", positionMs.coerceAtLeast(0))
        }
        return Futures.immediateVoidFuture()
    }
}
