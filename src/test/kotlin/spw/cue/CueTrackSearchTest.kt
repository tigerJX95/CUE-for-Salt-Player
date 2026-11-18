package spw.cue

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CueTrackSearchTest {
    private fun track(number: Int, title: String, artist: String?, disc: String = "CD1.wav") =
        CueTrack(number, title, artist, Path.of(disc), (number - 1) * 75_000L, null, null, null)
    private val tracks = listOf(
        track(1, "想你的三百六十五天", "李玟"),
        track(2, "Where Did U Go", "邓紫棋"),
        track(1, "Where Did U Go (Live)", null, "CD2.wav")
    )

    @Test fun emptySearchKeepsFullCueOrderAndOriginalIndices() {
        assertEquals(tracks.withIndex().toList(), filterCueTracks(tracks, " \t "))
    }

    @Test fun searchesTitleAndArtistWithCaseInsensitiveWords() {
        assertEquals(listOf(1), filterCueTracks(tracks, "  where  邓紫棋 ").map { it.index })
        assertEquals(listOf(0), filterCueTracks(tracks, "李玟").map { it.index })
        assertEquals(listOf(0), filterCueTracks(tracks, "三百六十五").map { it.index })
    }

    @Test fun filteredPlaybackIndicesRemainIndicesInTheFullSheetAcrossDiscs() {
        val results = filterCueTracks(tracks, "WHERE go")
        assertEquals(listOf(1, 2), results.map { it.index })
        assertEquals(tracks[2], tracks[results.last().index])
        assertEquals(Path.of("CD2.wav"), results.last().value.audio)
    }

    @Test fun clearingSearchRestoresAllTracksWithoutChangingThem() {
        assertTrue(filterCueTracks(tracks, "no-such-song").isEmpty())
        assertEquals(tracks, filterCueTracks(tracks, "").map { it.value })
    }

    @Test fun playbackIdentityIgnoresEditedMetadataAndFallbackArtists() {
        val original = tracks[1]
        assertTrue(original.matchesPlaybackTrack(original.copy(title = "新曲名", performer = "整轨艺术家")))
    }

    @Test fun playbackIdentityDistinguishesSameNumberOnDifferentDiscsAndStartChanges() {
        val original = tracks[0]
        assertFalse(original.matchesPlaybackTrack(original.copy(audio = Path.of("CD2.wav"))))
        assertFalse(original.matchesPlaybackTrack(original.copy(startFrame = original.startFrame + 1)))
        assertFalse(original.matchesPlaybackTrack(original.copy(number = 2)))
        assertFalse(original.matchesPlaybackTrack(null))
    }
}
