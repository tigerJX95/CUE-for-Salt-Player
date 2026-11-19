package spw.cue

import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

class CueTrackManagementTest {
    private val tracks = listOf(
        CueTrack(1, "First", "Artist", Path.of("CD1/album.wav"), 0, 750, null, null),
        CueTrack(2, "Hidden song", "Artist", Path.of("CD1/album.wav"), 750, null, null, null),
        CueTrack(1, "Hidden song", "Other", Path.of("CD2/album.wav"), 0, null, null, null)
    )
    private val keys = tracks.map(SongVisibility::key)
    private val records = SongVisibilityRecords(setOf(keys[1]), setOf(keys[1], keys[2]))

    @Test fun combinesSearchWithTheExactVisibilityState() {
        assertEquals(listOf(1), filterManagedCueTracks(tracks, keys, "hidden Artist", CueTrackStatusFilter.Hidden, records).map { it.index })
        assertEquals(listOf(1, 2), filterManagedCueTracks(tracks, keys, "hidden", CueTrackStatusFilter.Removed, records).map { it.index })
        assertTrue(filterManagedCueTracks(tracks, keys, "First", CueTrackStatusFilter.Removed, records).isEmpty())
    }

    @Test fun filteringKeepsDiscNumbersAndFullSheetIndicesWithIdenticalFilenamesAndTrackNumbers() {
        val visible = filterManagedCueTracks(tracks, keys, "Other", CueTrackStatusFilter.All, records)
        val rows = cueWindowRows(tracks, keys, visible)
        assertEquals(CueWindowRow.Disc(tracks[2].audio, 2, 1, 1), rows[0])
        assertEquals(CueWindowRow.Track(2, tracks[2], keys[2]), rows[1])
    }

    @Test fun onlyCheckedVisibleAffectedEntriesCanBeRecovered() {
        val visible = tracks.withIndex().filter { it.index < 2 }
        assertEquals(setOf(keys[1]), cueRecoveryKeys(keys.toSet(), visible, keys, records))
        assertEquals(emptySet<String>(), cueRecoveryKeys(setOf(keys[2]), visible, keys, records))
        assertEquals(emptySet<String>(), cueRecoveryKeys(keys.toSet(), visible, keys, SongVisibilityRecords()))
    }

    @Test fun matchingCountsReferToFullDiscAndNotTheFilteredDisc() {
        val visible = tracks.withIndex().filter { it.index == 1 }
        assertEquals(CueWindowRow.Disc(tracks[0].audio, 1, 1, 2), cueWindowRows(tracks, keys, visible)[0])
    }

    @Test fun aSingleAudioFileDoesNotGainAnUnnecessaryDiscHeading() {
        val single = tracks.take(2)
        assertTrue(cueWindowRows(single, keys.take(2), single.withIndex().toList()).all { it is CueWindowRow.Track })
    }

    @Test fun currentPlaybackIsLocatedBySegmentNotTitleOrTrackNumber() {
        assertEquals(2, cueWindowPlayingIndex(tracks, tracks[2].copy(title = "Updated", performer = "Fallback")))
        assertEquals(-1, cueWindowPlayingIndex(tracks, tracks[2].copy(audio = Path.of("CD3/album.wav"))))
        assertEquals(-1, cueWindowPlayingIndex(tracks, null))
    }
}
