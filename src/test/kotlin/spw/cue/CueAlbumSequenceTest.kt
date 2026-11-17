package spw.cue

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.State
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.HookRegistrar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.awt.EventQueue
import java.nio.file.Path

@OptIn(UnstableSpwWorkshopApi::class)
class CueAlbumSequenceTest {
    @get:Rule val temp = TemporaryFolder()
    private val playback = FakePlayback()
    private val requests = mutableListOf<Triple<String, Long, Boolean>>()
    private val first = disc("cd1", 0, 6_000)
    private val second = disc("cd2", 1_000, 7_000)
    private val third = disc("cd3", 0, 8_000)
    private val probe = PlaybackProbe()
    private var active = true

    @Before fun setUp() {
        WorkshopApi.instance = object : WorkshopApi {
            override val playback get() = this@CueAlbumSequenceTest.playback
            override val library get() = error("Unexpected library access")
            override val ui get() = error("Unexpected UI access")
            override val hookRegistrar: HookRegistrar get() = error("Unexpected hook access")
            override val manager get() = error("Unexpected shortcut access")
        }
    }

    @After fun tearDown() {
        CueAlbumSequence.clear()
        CueRemoval.bind(null)
        EventQueue.invokeAndWait {}
    }

    @Test fun movesAcrossBothBoundariesAndUsesTargetStart() {
        start()
        assertNull(CueAlbumSequence.step(first.cue.tracks.first(), 1))
        assertEquals(second.cue.tracks.first(), CueAlbumSequence.step(first.cue.tracks.last(), 1))
        assertEquals(first.cue.tracks.last(), CueAlbumSequence.step(second.cue.tracks.first(), -1))
        assertNull(CueAlbumSequence.step(first.cue.tracks.first(), -1))
        assertEquals(
            listOf(Triple("cd1", 0L, true), Triple("cd2", 1_000L, true), Triple("cd1", 6_000L, true)),
            requests
        )
    }

    @Test fun handlesThePublicEndEventOnceAndReturnsAtAlbumEnd() {
        start()
        EventQueue.invokeAndWait {
            probe.onPositionUpdated(9_850)
            probe.onStateChanged(State.Ended)
            probe.onStateChanged(State.Ended)
        }
        EventQueue.invokeAndWait {}
        assertEquals(listOf("cd1", "cd2"), requests.map { it.first })

        EventQueue.invokeAndWait {
            probe.onPositionUpdated(9_900)
            probe.onStateChanged(State.Ended)
        }
        EventQueue.invokeAndWait {}
        assertEquals(listOf("cd1", "cd2"), requests.map { it.first })
    }

    @Test fun skipsNoMissingDiscsAndDoesNotFollowAnUnrelatedSource() {
        CueAlbumSequence(listOf(first, null, third)) { active }.select(first.id, 0, false)
        assertNull(CueAlbumSequence.step(first.cue.tracks.last(), 1))
        start()
        CueAlbumSequence.sourceChanged(third.audio)
        assertNull(CueAlbumSequence.step(first.cue.tracks.last(), 1))
        start()
        active = false
        assertNull(CueAlbumSequence.step(first.cue.tracks.last(), 1))
        assertEquals(listOf("cd1", "cd1", "cd1"), requests.map { it.first })
    }

    @Test fun anOldOrPrematureEndDoesNotSwitchDiscs() {
        probe.onPositionUpdated(9_999)
        start()
        EventQueue.invokeAndWait {
            probe.onStateChanged(State.Ended)
            probe.onPositionUpdated(3_000)
            probe.onStateChanged(State.Ended)
        }
        EventQueue.invokeAndWait {}
        assertEquals(listOf("cd1"), requests.map { it.first })
    }

    @Test fun choosesTheFirstTrackStillInTheLibrary() {
        val visibility = SongVisibility(temp.root.toPath().resolve("visibility.json"))
        CueRemoval.bind(visibility)
        visibility.remove(SongVisibility.key(second.cue.tracks.first()))
        EventQueue.invokeAndWait {}
        start()
        assertEquals(second.cue.tracks.last(), CueAlbumSequence.step(first.cue.tracks.last(), 1))
        assertEquals(Triple("cd2", 7_000L, true), requests.last())
    }

    private fun start() = CueAlbumSequence(listOf(first, second)) { active }.select(first.id, 0, false)

    private fun disc(id: String, start: Long, secondStart: Long): CueAlbumSequence.Disc {
        val audio = Path.of("music", id, "album.wav").toAbsolutePath().normalize()
        val tracks = listOf(
            CueTrack(1, "First", null, audio, 0, null, null, null, start, secondStart),
            CueTrack(2, "Second", null, audio, 0, null, null, null, secondStart, null)
        )
        return CueAlbumSequence.Disc(id, audio, AlbumCue(tracks, 10_000)) { at, switch ->
            requests += Triple(id, at, switch)
            playback.item = MediaItem("Album", "", "", "", audio.toString(), id, duration = 10_000)
        }
    }

    private class FakePlayback : WorkshopApi.Playback {
        var item: MediaItem? = null
        override fun getCurrentMediaItem() = item
        override fun changeExclusive(exclusive: Boolean) = Unit
        override fun pause() = Unit
        override fun play() = Unit
        override fun previous() = Unit
        override fun next() = Unit
        override fun seekTo(position: Long) = Unit
    }
}
