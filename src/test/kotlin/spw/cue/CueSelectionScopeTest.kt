package spw.cue

import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CueSelectionScopeTest {
    @get:Rule val temp = TemporaryFolder()
    private val tracks = (1..3).map { n -> CueTrack(n, "Song $n", "Artist", Path.of("album.wav"), (n - 1) * 750L, n * 750L, null, null) }
    private val keys = tracks.map(SongVisibility::key)
    private val entries = keys.mapIndexed { index, key -> "cue:$index" to key }.toMap()

    @Test fun batchHideFromTheVisibleListDoesNotRequireTheMainSongsPage() {
        val store = SongVisibility(temp.root.toPath().resolve("song-visibility.txt"))
        val selected = CueRemoval.selectedEntries(entries, setOf("cue:0", "cue:1", "ordinary"), store)
        assertEquals(keys.take(2).toSet(), selected)
        store.hide(selected)
        assertEquals(listOf(0, 1), filterManagedCueTracks(tracks, keys, "", CueTrackStatusFilter.Hidden, store.recovery.value).map { it.index })
    }

    @Test fun batchRemoveFromTheVisibleListKeepsAllSelectedRecoveryEntries() {
        val store = SongVisibility(temp.root.toPath().resolve("song-visibility.txt"))
        store.remove(CueRemoval.selectedEntries(entries, setOf("cue:0", "cue:1", "cue:2"), store))
        assertEquals(3, filterManagedCueTracks(tracks, keys, "", CueTrackStatusFilter.Removed, store.recovery.value).size)
        assertTrue(CueRemoval.selectedEntries(entries, setOf("cue:0", "cue:1"), store).isEmpty())
    }

    @Test fun selectionFromAnotherPageOrFilteredOutRowsCannotBeRemoved() {
        val store = SongVisibility(temp.root.toPath().resolve("song-visibility.txt"))
        assertTrue(CueRemoval.selectedEntries(entries, setOf("other-page:1", "ordinary"), store).isEmpty())
        val filtered = mapOf("cue:1" to keys[1])
        assertEquals(setOf(keys[1]), CueRemoval.selectedEntries(filtered, setOf("cue:0", "cue:1"), store))
    }
}
