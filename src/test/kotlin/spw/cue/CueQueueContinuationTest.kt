package spw.cue

import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

class CueQueueContinuationTest {
    private val cueAudio = Path.of("album.wav")
    private val regularAudio = Path.of("next.flac")
    private val first = CueTrack(1, "First", null, cueAudio, 0, 750, null, null)
    private val last = CueTrack(2, "Last", null, cueAudio, 750, null, null, null)
    private val original = CuePlaybackSource("library", cueAudio, Any())
    private val dragged = CuePlaybackSource("dragged", cueAudio, Any())
    private val regular = CuePlaybackSource("next", regularAudio, Any())
    private fun segment(track: CueTrack, source: Int) = CueShuffleEntry(source, track.audio, "source$source", track, 20_000)
    private val entries = listOf(segment(first, 0), segment(last, 0), CueShuffleEntry(1, regularAudio, "next", null, 50_000))

    @Test fun sourceAfterLastCueIsTheNextOrdinarySongNotTheFirstCue() {
        assertEquals(entries[2], nextCueQueueSource(entries, 0, 1, "CircleEnd", emptySet()))
        assertEquals(entries[2], nextCueQueueSource(entries, 0, 1, "Circle", emptySet()))
    }

    @Test fun sourceAfterLastCueCanBeTheNextDiscsFirstSegment() {
        val nextDisc = first.copy(audio = Path.of("CD2.wav"))
        val next = segment(nextDisc, 1)
        assertEquals(next, nextCueQueueSource(entries.take(2) + next, 0, 1, "CircleEnd", emptySet()))
        assertEquals(entries[1], nextCueQueueSource(entries.take(2) + next, 1, -1, "CircleEnd", emptySet()))
    }

    @Test fun sequentialQueueEndStopsAndListLoopWrapsOnlyAtTheWholeQueueEnd() {
        assertNull(nextCueQueueSource(entries, 1, 1, "CircleEnd", emptySet()))
        assertEquals(entries.first(), nextCueQueueSource(entries, 1, 1, "Circle", emptySet()))
        assertNull(nextCueQueueSource(emptyList(), 0, 1, "CircleEnd", emptySet()))
        assertNull(nextCueQueueSource(entries, 0, 1, "RepeatOne", emptySet()))
        assertNull(nextCueQueueSource(entries, 0, 1, "Random", emptySet()))
    }

    @Test fun removedSegmentsAreSkippedAtTheNextSource() {
        val nextFirst = first.copy(audio = Path.of("CD2.wav"))
        val nextLast = last.copy(audio = nextFirst.audio)
        val ordinary = entries.last().copy(sourceIndex = 2)
        val scoped = entries.take(2) + listOf(segment(nextFirst, 1), segment(nextLast, 1), ordinary)
        assertEquals(segment(nextLast, 1), nextCueQueueSource(scoped, 0, 1, "CircleEnd", setOf(SongVisibility.key(nextFirst))))
        assertEquals(ordinary, nextCueQueueSource(scoped, 0, 1, "CircleEnd", setOf(SongVisibility.key(nextFirst), SongVisibility.key(nextLast))))
    }

    @Test fun firstAndLastBoundariesIgnoreArtistFallbacksAndRejectNonEdges() {
        assertTrue(cueSourceEdge(listOf(first, last), last.copy(performer = "Fallback"), 20_000, 1))
        assertTrue(cueSourceEdge(listOf(first, last), first, 20_000, -1))
        assertFalse(cueSourceEdge(listOf(first, last), first, 20_000, 1))
        assertFalse(cueSourceEdge(listOf(first, last), last, 20_000, -1))
    }

    @Test fun fullQueueMatchesTheTargetByAudioEvenWhenItsIdCameFromDragging() {
        val existing = listOf(dragged, regular)
        assertEquals(CuePlaybackScope(existing, 0), cuePlaybackScope(existing, emptyList(), original))
    }

    @Test fun repairingAnOldSingleFileQueueRestoresTheVisibleSongsAndTheirOrder() {
        val context = listOf(regular, original)
        assertEquals(CuePlaybackScope(context, 1), cuePlaybackScope(listOf(dragged), context, original))
    }

    @Test fun anExistingCompleteQueueTakesPrecedenceOverASearchResultSubset() {
        val existing = listOf(original, regular)
        assertEquals(CuePlaybackScope(existing, 0), cuePlaybackScope(existing, listOf(original), original))
    }

    @Test fun contextProjectionDoesNotEnqueueOneCopyOfTheAudioForEveryCueTrack() {
        val context = listOf(original, original.copy(media = Any()), regular)
        val scope = cuePlaybackScope(emptyList(), context, original)
        assertEquals(listOf(original, regular), scope.sources)
        assertEquals(0, scope.index)
    }

    @Test fun aSourceOutsideBothScopesDoesNotInventUnrelatedLibraryEntries() {
        assertEquals(CuePlaybackScope(listOf(original), 0), cuePlaybackScope(listOf(regular), listOf(regular), original))
    }
}
