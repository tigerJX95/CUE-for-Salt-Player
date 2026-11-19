package spw.cue

import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CueRecoveryPlanTest {
    @get:Rule val temp = TemporaryFolder()
    private val tracks = listOf(
        CueTrack(1, "Hidden one", "Artist", Path.of("CD1/album.wav"), 0, 750, null, null),
        CueTrack(2, "Removed two", "Artist", Path.of("CD1/album.wav"), 750, 1500, null, null),
        CueTrack(3, "Ordinary", "Artist", Path.of("CD1/album.wav"), 1500, null, null, null),
        CueTrack(1, "Other disc", "Artist", Path.of("CD2/album.wav"), 0, null, null, null)
    )
    private val keys = tracks.map(SongVisibility::key)
    private val records = SongVisibilityRecords(setOf(keys[0], keys[3]), setOf(keys[1]))
    private val visible = tracks.withIndex().take(3)

    @Test fun noCheckboxSelectionNeverBecomesOneHighlightedTrack() {
        val plan = cueRecoveryPlan(emptySet(), visible, keys, records)
        assertTrue(plan.checked.isEmpty())
        assertEquals(setOf(keys[0], keys[1]), plan.visible)
    }

    @Test fun checkedHiddenAndRemovedTracksHaveTheSameCountAndOperationScope() {
        val plan = cueRecoveryPlan(keys.toSet(), visible, keys, records)
        assertEquals(setOf(keys[0], keys[1]), plan.checked)
        assertEquals(2, plan.checked.size)
        assertEquals(plan.checked, cueRecoveryKeys(keys.toSet(), visible, keys, records))
    }

    @Test fun unaffectedOrOffscreenChecksNeverFallBackToAnotherTrack() {
        val plan = cueRecoveryPlan(setOf(keys[2], keys[3]), visible, keys, records)
        assertTrue(plan.checked.isEmpty())
        assertEquals(2, plan.visible.size)
    }

    @Test fun searchAndStateFilterRestrictBothRecoveryActions() {
        val filtered = filterManagedCueTracks(tracks, keys, "Removed", CueTrackStatusFilter.Removed, records)
        val plan = cueRecoveryPlan(keys.toSet(), filtered, keys, records)
        assertEquals(setOf(keys[1]), plan.checked)
        assertEquals(setOf(keys[1]), plan.visible)
        assertTrue(cueRecoveryPlan(keys.toSet(), emptyList(), keys, records).visible.isEmpty())
    }

    @Test fun batchRestorePersistsAllCheckedRecordsAndPreservesTheOtherDisc() {
        val path = temp.root.toPath().resolve("hidden-cue-tracks.txt")
        val store = SongVisibility(path)
        store.hide(setOf(keys[0], keys[3]))
        store.remove(setOf(keys[0], keys[1]))
        val plan = cueRecoveryPlan(setOf(keys[0], keys[1]), visible, keys, store.recovery.value)
        store.restore(plan.checked)
        val saved = SongVisibility(path).recovery.value
        assertEquals(setOf(keys[3]), saved.hiddenInSongs)
        assertTrue(saved.removedFromLibrary.isEmpty())
        assertEquals(0, cueRecoveryPlan(keys.toSet(), visible, keys, saved).checked.size)
    }

    @Test fun restoreCurrentResultsNeverRestoresHiddenTracksFromOtherSources() {
        val path = temp.root.toPath().resolve("hidden-cue-tracks.txt")
        val store = SongVisibility(path)
        store.hide(setOf(keys[0], keys[3]))
        store.remove(setOf(keys[1], keys[3]))
        val plan = cueRecoveryPlan(emptySet(), visible, keys, store.recovery.value)
        assertEquals(2, plan.visible.size)
        store.restore(plan.visible)
        val saved = SongVisibility(path).recovery.value
        assertEquals(setOf(keys[3]), saved.hiddenInSongs)
        assertEquals(setOf(keys[3]), saved.removedFromLibrary)
    }
}
