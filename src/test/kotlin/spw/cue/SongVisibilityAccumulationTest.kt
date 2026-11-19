package spw.cue

import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SongVisibilityAccumulationTest {
    @get:Rule val temp = TemporaryFolder()
    private val tracks = (1..3).map { n -> CueTrack(n, "Song $n", "Artist", Path.of("CD1/album.wav"), (n - 1) * 750L, n * 750L, null, null) }
    private val keys = tracks.map(SongVisibility::key)

    @Test fun independentlyLoadedStoresMustAccumulateEarlierHiddenTracks() {
        val path = temp.root.toPath().resolve("song-visibility.txt")
        val first = SongVisibility(path)
        val second = SongVisibility(path)
        first.hide(keys[0])
        second.hide(keys[1])
        first.hide(keys[2])
        val reopened = SongVisibility(path)
        assertEquals(keys.toSet(), reopened.recovery.value.hiddenInSongs)
        assertEquals(keys.toSet(), first.recovery.value.hiddenInSongs)
        assertEquals(keys.toSet(), second.recovery.value.hiddenInSongs)
        assertEquals(3, filterManagedCueTracks(tracks, keys, "", CueTrackStatusFilter.Hidden, second.recovery.value).size)
    }

    @Test fun removingFromAnotherStoreMustRefreshTheWholeRecoveryList() {
        val path = temp.root.toPath().resolve("song-visibility.txt")
        val first = SongVisibility(path)
        val second = SongVisibility(path)
        first.remove(keys[0])
        second.remove(keys[1])
        first.remove(keys[2])
        assertEquals(keys.toSet(), second.recovery.value.removedFromLibrary)
        assertEquals(3, filterManagedCueTracks(tracks, keys, "", CueTrackStatusFilter.Removed, second.recovery.value).size)
    }

    @Test fun restoringOneTrackMustNotResurrectOrOverwriteOtherRecords() {
        val path = temp.root.toPath().resolve("song-visibility.txt")
        val first = SongVisibility(path)
        val second = SongVisibility(path)
        first.hide(keys.toSet())
        second.remove(keys.toSet())
        second.restore(setOf(keys[0]))
        first.hide(keys[1])
        assertEquals(keys.drop(1).toSet(), first.recovery.value.hiddenInSongs)
        assertEquals(keys.drop(1).toSet(), first.recovery.value.removedFromLibrary)
        assertEquals(first.recovery.value, second.recovery.value)
    }

    @Test fun oneStoreSequentialActionsKeepEveryTrackVisibleInTheRecoveryFilters() {
        val path = temp.root.toPath().resolve("song-visibility.txt")
        val store = SongVisibility(path)
        keys.forEach(store::hide)
        keys.forEach(store::remove)
        assertEquals(3, filterManagedCueTracks(tracks, keys, "", CueTrackStatusFilter.Hidden, store.recovery.value).size)
        assertEquals(3, filterManagedCueTracks(tracks, keys, "", CueTrackStatusFilter.Removed, store.recovery.value).size)
    }

    @Test fun reloadingExternalRecordsUpdatesAllOpenViewsBeforeTheNextWrite() {
        val path = temp.root.toPath().resolve("song-visibility.txt")
        val first = SongVisibility(path)
        val second = SongVisibility(path)
        java.nio.file.Files.write(path, keys.take(2))
        first.refresh()
        assertEquals(keys.take(2).toSet(), second.recovery.value.hiddenInSongs)
        second.hide(keys[2])
        assertEquals(keys.toSet(), first.recovery.value.hiddenInSongs)
    }

    @Test fun unreadableRecordsAbortWritesWithoutOverwritingKnownState() {
        val path = temp.root.toPath().resolve("song-visibility.txt")
        val store = SongVisibility(path)
        store.hide(keys[0])
        val removed = java.nio.file.Files.createDirectory(path.resolveSibling("removed-cue-tracks.txt"))
        assertThrows(java.io.IOException::class.java) { store.hide(keys[1]) }
        assertThrows(java.io.IOException::class.java) { store.remove(keys[1]) }
        assertEquals(setOf(keys[0]), store.recovery.value.hiddenInSongs)
        java.nio.file.Files.delete(removed)
        assertEquals(setOf(keys[0]), SongVisibility(path).recovery.value.hiddenInSongs)
    }
}
