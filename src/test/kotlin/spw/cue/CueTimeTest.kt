package spw.cue

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CueTimeTest {
    private val audio = Path.of("disc.flac")

    @Test fun progressRestartsAtEachTrackStart() {
        val first = window(0, 300, 900)
        val second = window(300, 420, 900)
        assertEquals(300_000L, first.durationMs)
        assertEquals(150_000L, first.elapsedMs(150_000L))
        assertEquals(0L, second.elapsedMs(300_000L))
        assertEquals(60_000L, second.elapsedMs(360_000L))
        assertEquals(120_000L, second.durationMs)
    }

    @Test fun seekUsesTheCurrentTrackBounds() {
        val second = window(300, 420, 900)
        assertEquals(300_000L, second.seekPosition(0f))
        assertEquals(360_000L, second.seekPosition(0.5f))
        assertEquals(419_999L, second.seekPosition(1f))
        assertEquals(419_999L, second.seekPosition(1.2f))
        assertNull(second.seekPosition(Float.NaN))
    }

    @Test fun lastTrackAndOverlongCueEndUseTheFileDuration() {
        val last = window(420, null, 900)
        assertEquals(480_000L, last.durationMs)
        assertEquals(899_999L, last.seekPosition(1f))
        val truncated = window(420, 960, 900)
        assertEquals(last, truncated)
        val beyondFile = track(900, null)
        assertNull(CuePlaybackWindow.from(beyondFile, 900_000L))
    }

    @Test fun albumRowsShowTrackLengthsFromCueAndSourceBoundaries() {
        val tracks = listOf(track(0, 185), track(185, 315), track(315, null))
        assertEquals(listOf("03:05", "02:10", "03:05"), AlbumCue(tracks, 500_000L).trackDurationLabels)
        assertEquals(listOf("03:05", "02:10", "—"), AlbumCue(tracks, 0L).trackDurationLabels)
    }

    private fun window(startSeconds: Long, endSeconds: Long?, fileSeconds: Long): CuePlaybackWindow =
        checkNotNull(CuePlaybackWindow.from(track(startSeconds, endSeconds), fileSeconds * 1_000))

    private fun track(startSeconds: Long, endSeconds: Long?) =
        CueTrack(1, "Track", null, audio, startSeconds * 75, endSeconds?.times(75), null, null)
}
