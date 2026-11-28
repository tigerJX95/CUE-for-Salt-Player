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

    @Test fun windowsTaskbarUsesTrackElapsedAndLengthAtBoundaries() {
        val tracks = listOf(track(0, 300), track(300, 420), track(420, null))
        assertEquals(299_999L to 300_000L, taskbarCueTimes(tracks, 299_999, 900_000))
        assertEquals(0L to 120_000L, taskbarCueTimes(tracks, 300_000, 900_000))
        assertEquals(60_000L to 120_000L, taskbarCueTimes(tracks, 360_000, 900_000))
        assertEquals(0L to 480_000L, taskbarCueTimes(tracks, 420_000, 900_000))
        assertEquals(480_000L to 480_000L, taskbarCueTimes(tracks, 900_000, 900_000))
        assertNull(taskbarCueTimes(emptyList(), 300_000, 900_000))
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

    @Test fun highlightsTheTrackAtThePlaybackPositionAndMovesAtTheCueBoundary() {
        val first = track(0, 185)
        val second = track(185, null).copy(number = 2)
        val album = AlbumCue(listOf(first, second), 400_000L)

        val before = cueTrackAt(album.tracks, 184_999L, album.durationMs)
        val after = cueTrackAt(album.tracks, 185_000L, album.durationMs)
        assertEquals(0, album.indexOfPlayingTrack(before))
        assertEquals(1, album.indexOfPlayingTrack(after?.copy(performer = "整轨艺术家")))
    }

    @Test fun neverHighlightsAnotherDiscOrAnAbsentTrack() {
        val first = track(0, 185)
        val second = track(185, null).copy(number = 2)
        val album = AlbumCue(listOf(second), 400_000L)

        assertEquals(-1, album.indexOfPlayingTrack(first))
        assertEquals(-1, album.indexOfPlayingTrack(second.copy(audio = Path.of("another-disc.flac"))))
        assertEquals(-1, album.indexOfPlayingTrack(second.copy(startFrame = second.startFrame + 1)))
        assertEquals(-1, album.indexOfPlayingTrack(null))
    }

    @Test fun navigationSkipsRemovedOrUnplayableSegmentsInEitherDirection() {
        val first = track(0, 100)
        val removed = track(100, 200).copy(number = 2)
        val invalid = track(200, 200).copy(number = 3)
        val last = track(300, null).copy(number = 4)
        val tracks = listOf(first, removed, invalid, last)
        val excluded = setOf(SongVisibility.key(removed))

        assertEquals(last, adjacentCueTrack(tracks, first, 400_000, 1, excluded))
        assertEquals(first, adjacentCueTrack(tracks, last, 400_000, -1, excluded))
        assertEquals(removed, adjacentCueTrack(tracks, first, 400_000, 1))
        assertNull(adjacentCueTrack(tracks, first, 300_000, 1, excluded))
    }

    private fun window(startSeconds: Long, endSeconds: Long?, fileSeconds: Long): CuePlaybackWindow =
        checkNotNull(CuePlaybackWindow.from(track(startSeconds, endSeconds), fileSeconds * 1_000))

    private fun track(startSeconds: Long, endSeconds: Long?) =
        CueTrack(1, "Track", null, audio, startSeconds * 75, endSeconds?.times(75), null, null)
}
