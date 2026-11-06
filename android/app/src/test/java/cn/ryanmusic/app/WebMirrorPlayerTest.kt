package cn.ryanmusic.app

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(UnstableApi::class)
class WebMirrorPlayerTest {
    @Test fun mirrorsMetadataAndRoutesControls() {
        val commands = mutableListOf<Pair<String, Long?>>()
        val player = WebMirrorPlayer { action, position -> commands.add(action to position) }
        player.update(JSONObject("""{"state":"playing","title":"Song","artist":"Artist","positionMs":1200,"durationMs":5000,"canSkipNext":true,"canSkipPrev":true}"""))
        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(player.playWhenReady)
        assertEquals("Song", player.mediaMetadata.title.toString())
        assertEquals("Artist", player.mediaMetadata.artist.toString())
        assertEquals(1200L, player.currentPosition)
        assertEquals(5000L, player.duration)
        player.pause()
        player.play()
        player.seekToNext()
        player.seekToPrevious()
        player.seekTo(2300)
        assertEquals(listOf("pause" to null, "play" to null, "next" to null, "previous" to null, "seek" to 2300L), commands)
    }

    @Test fun stoppedClearsTimelineAndDisabledSkipCommands() {
        val player = WebMirrorPlayer { _, _ -> }
        player.update(JSONObject("""{"state":"paused","canSkipNext":false,"canSkipPrev":false}"""))
        assertEquals(Player.STATE_READY, player.playbackState)
        assertFalse(player.playWhenReady)
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        player.update(JSONObject("""{"state":"stopped"}"""))
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertTrue(player.currentTimeline.isEmpty)
        assertFalse(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
    }
    @Test fun serviceStartsWithAnIdleSessionAndAcceptsStoppedState() {
        val controller = Robolectric.buildService(PlaybackSessionService::class.java).create()
        val service = controller.get()
        service.commandSink = { _, _ -> }
        service.updatePlayback("""{"state":"playing","title":"Test track","artist":"Test artist"}""")
        service.updatePlayback("""{"state":"paused","title":"Test track"}""")
        service.updatePlayback("""{"state":"stopped"}""")
        controller.destroy()
    }

}
