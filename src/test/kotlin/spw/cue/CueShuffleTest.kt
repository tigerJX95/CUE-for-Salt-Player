package spw.cue

import java.nio.file.Path
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class CueShuffleTest {
    private val audio = Path.of("CD1.wav")
    private val first = CueTrack(1, "First", null, audio, 0, 750, null, null)
    private val second = CueTrack(2, "Second", null, audio, 750, null, null, null)
    private val tracks = listOf(first, second)
    private fun entry(track: CueTrack?, source: Int = 0, path: Path? = audio) =
        CueShuffleEntry(source, path, "source$source", track, 20_000)
    private class Pick(private val index: Int) : Random() {
        override fun nextBits(bitCount: Int): Int = error("Unexpected bits")
        override fun nextInt(until: Int): Int = index.also { require(it in 0 until until) }
    }

    @Test fun randomPoolIncludesOtherSegmentsAndOrdinarySongsWithoutRepeatingTheCurrentTrack() {
        val ordinary = entry(null, 1, Path.of("ordinary.flac"))
        val entries = listOf(entry(first), entry(second), ordinary)
        assertEquals(entry(second), chooseCueShuffleEntry(entries, audio, first, emptySet(), Pick(0)))
        assertEquals(ordinary, chooseCueShuffleEntry(entries, audio, first, emptySet(), Pick(1)))
    }

    @Test fun eachIndividualTrackHasOneReachableSlotAndADiscHasNoSpecialPreference() {
        val cue = (1..13).map { number -> first.copy(number = number, startFrame = number * 750L) }.map { entry(it) }
        val ordinary = (1..26).map { entry(null, it, Path.of("ordinary$it.flac")) }
        val chosen = (0 until 39).map {
            chooseCueShuffleEntry(cue + ordinary, Path.of("outside.wav"), null, emptySet(), Pick(it))!!
        }
        assertEquals(39, chosen.map { it.key }.toSet().size)
        assertEquals(13, chosen.count { it.track != null })
        assertEquals(26, chosen.count { it.track == null })
    }

    @Test fun duplicateRowsAndRemovedTracksCannotIncreaseProbability() {
        val entries = listOf(entry(first), entry(second), entry(second))
        assertEquals(entry(second), chooseCueShuffleEntry(entries, audio, first, emptySet(), Pick(0)))
        assertNull(chooseCueShuffleEntry(entries, audio, first, setOf(SongVisibility.key(second))))
        assertNull(chooseCueShuffleEntry(listOf(entry(first)), audio, first, emptySet()))
    }

    @Test fun anArtistFallbackDoesNotAllowAnImmediateRepeat() {
        assertNull(chooseCueShuffleEntry(listOf(entry(first)), audio, first.copy(performer = "Fallback"), emptySet()))
    }

    @Test fun naturalBoundaryTriggersAtTheCueEndAndOnlyOnce() {
        val gate = CueShuffleBoundary()
        val source = Any()
        assertNull(gate.observe(source, tracks, 9_800, 20_000, null, true, 0L))
        assertEquals(first, gate.observe(source, tracks, 10_050, 20_000, null, true, 250_000_000L))
        assertNull(gate.observe(source, tracks, 10_300, 20_000, null, true, 500_000_000L))
    }

    @Test fun pausesSeeksLargeJumpsAndSourceChangesCannotTriggerAShuffle() {
        val source = Any()
        fun gate() = CueShuffleBoundary().apply { observe(source, tracks, 9_800, 20_000, null, true, 0L) }
        assertNull(gate().observe(source, tracks, 10_050, 20_000, 1L, true, 250_000_000L))
        assertNull(gate().observe(source, tracks, 10_050, 20_000, null, false, 250_000_000L))
        assertNull(gate().observe(Any(), tracks, 10_050, 20_000, null, true, 250_000_000L))
        assertNull(gate().observe(source, tracks, 18_000, 20_000, null, true, 250_000_000L))
        assertNull(gate().observe(source, tracks, 0, 20_000, null, true, 250_000_000L))
    }

    @Test fun resetCancelsTheFormerSegmentsBoundary() {
        val source = Any()
        val gate = CueShuffleBoundary()
        gate.observe(source, tracks, 9_800, 20_000, null, true, 0L)
        gate.reset()
        assertNull(gate.observe(source, tracks, 10_100, 20_000, null, true, 300_000_000L))
    }
}
