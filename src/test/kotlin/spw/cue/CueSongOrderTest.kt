package spw.cue

import java.nio.file.Path
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CueSongOrderTest {
    private val audio = Path.of("/music/CD1.wav")
    private val first = CueTrack(1, "A", null, audio, 75, 450, null, null)
    private val last = CueTrack(2, "C", null, audio, 450, null, null, null)
    private val disc = AlbumHookTarget.Track("CD1", Any())
    private val plain = AlbumHookTarget.Track("B", Any())
    private val mixed = listOf(OrderedSong(disc, first), OrderedSong(plain, null), OrderedSong(disc, last))

    @After fun clear() = CueSongOrder.clear()

    @Test fun titleSortedPlaybackFollowsVisibleRowsEvenWhenTwoRowsShareAnAudioFile() {
        val played = mutableListOf<String>()
        CueSongOrder.start(mixed, 0, { true }) { row, _, _ -> played += row.cue?.title ?: row.source.id }
        fun move(id: String, path: Path?, direction: Int, segmentEnded: Boolean = false) {
            val current = checkNotNull(CueSongOrder.current("Circle", id, path))
            assertTrue(CueSongOrder.advance(current, "Circle", direction, emptySet(), { fail() }, segmentEnded))
        }
        move("CD1", audio, 1, segmentEnded = true)
        move("B", null, 1)
        move("CD1", audio, 1)
        move("CD1", audio, -1)
        assertEquals(listOf("A", "B", "C", "A", "C"), played)
    }

    @Test fun sequentialEndStopsAndRandomOrRepeatOneUsesItsOwnMode() {
        var stopped = 0
        CueSongOrder.start(mixed, 2, { true }) { _, _, _ -> }
        assertNull(CueSongOrder.current("RepeatOne", disc.id, audio))
        assertNull(CueSongOrder.current("Random", disc.id, audio))
        val cursor = checkNotNull(CueSongOrder.current("CircleEnd", disc.id, audio))
        assertTrue(CueSongOrder.advance(cursor, "CircleEnd", 1, emptySet(), { stopped++ }, atFileEnd = true))
        assertEquals(1, stopped)
        assertNull(CueSongOrder.current("Circle", "some-other-queue", null))
        assertNull(CueSongOrder.current("Circle", disc.id, audio))
    }

    @Test fun removedTracksDoNotReenterTheOrderAndContiguousCueTracksKeepPlaying() {
        assertEquals(1, nextOrderedSong(mixed, 2, 1, true, setOf(SongVisibility.key(first))))
        var requests = 0
        CueSongOrder.start(listOf(mixed.first(), mixed.last()), 0, { true }) { _, _, _ -> requests++ }
        val cursor = checkNotNull(CueSongOrder.current("Circle", disc.id, audio))
        assertTrue(CueSongOrder.advance(cursor, "Circle", 1, emptySet(), { fail() }, segmentEnded = true))
        assertEquals(1, requests)
    }

    @Test fun wholeRowAndCueRowFromTheSameFileHaveDistinctPlaybackIntent() {
        val songs = listOf(OrderedSong(disc, null), mixed[0], mixed[1])
        CueSongOrder.start(songs, 0, { true }) { _, _, _ -> }
        val whole = checkNotNull(CueSongOrder.current("Circle", disc.id, audio))
        assertNull(whole.row.cue)
        assertTrue(CueSongOrder.isWholeFile(disc.id))
        assertFalse(CueSongOrder.isWholeFile(plain.id))
        assertTrue(CueSongOrder.advance(whole, "Circle", 1, emptySet(), { fail() }, atFileEnd = true))
        assertFalse(CueSongOrder.isWholeFile(disc.id))
        assertEquals(first, CueSongOrder.current("Circle", disc.id, audio)?.row?.cue)
    }

    @Test fun nextAndPreviousKeepTheVisibleOrderAcrossTransientHostMediaChanges() {
        val played = mutableListOf<String>()
        val rows = listOf(OrderedSong(disc, null), mixed[0], mixed[1], mixed[2])
        CueSongOrder.start(rows, 0, { true }) { row, _, _ -> played += row.cue?.title ?: row.source.id }
        fun step(id: String?, path: Path?, direction: Int) =
            CueSongOrder.step("CircleEnd", id, path, direction, emptySet()) { fail() }
        CueSongOrder.sourceChanged(disc.id, audio)
        CueSongOrder.sourceChanged(null, null)
        assertTrue(step(null, null, 1))
        assertTrue(step(disc.id, audio, -1))
        assertTrue(step(disc.id, audio, 1))
        assertTrue(step(disc.id, audio, 1))
        assertTrue(step(disc.id, audio, 1))
        assertTrue(step(plain.id, null, 1))
        assertTrue(step(disc.id, audio, -1))
        assertEquals(listOf("CD1", "A", "B", "C", "B"), played)
    }

    @Test fun changingFromRandomToSequentialReusesTheRowThatWasSelectedInSongs() {
        val played = mutableListOf<String>()
        CueSongOrder.remember(mixed, 0, { true }) { row, _, _ -> played += row.source.id }
        assertFalse(CueSongOrder.step("Random", disc.id, audio, 1, emptySet()) { fail() })
        assertTrue(CueSongOrder.step("CircleEnd", disc.id, audio, 1, emptySet()) { fail() })
        assertEquals(listOf("B"), played)
    }

    @Test fun directWholePlaybackInNativeModesExpiresWhenTheSourceChanges() {
        CueSongOrder.startWholeFile(disc) { true }
        CueSongOrder.sourceChanged(plain.id, null)
        assertTrue(CueSongOrder.isWholeFile(disc.id))
        assertNull(CueSongOrder.current("Random", disc.id, audio))
        assertNull(CueSongOrder.current("Circle", disc.id, audio))
        CueSongOrder.sourceChanged(plain.id, null)
        assertFalse(CueSongOrder.isWholeFile(disc.id))
    }

    @Test fun theWholeFileCreditIsReadyBeforePlaybackAndDoesNotFollowAnotherRow() {
        val rows = listOf(OrderedSong(disc, null, "Album Artist"), mixed[0], mixed[1])
        CueSongOrder.start(rows, 0, { true }) { row, _, _ ->
            assertEquals(if (row.cue == null) "Album Artist" else null, CueSongOrder.wholeFileArtist(disc.id))
            assertNull(CueSongOrder.wholeFileArtist(plain.id))
        }
        val whole = checkNotNull(CueSongOrder.current("Circle", disc.id, audio))
        assertTrue(CueSongOrder.advance(whole, "Circle", 1, emptySet(), { fail() }))
        assertNull(CueSongOrder.wholeFileArtist(disc.id))
    }
}
