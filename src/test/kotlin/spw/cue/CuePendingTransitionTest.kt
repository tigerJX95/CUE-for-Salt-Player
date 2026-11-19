package spw.cue

import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

class CuePendingTransitionTest {
    private val cd1 = Path.of("CD1.wav")
    private val cd2 = Path.of("CD2.wav")
    private val first = CueTrack(1, "First", null, cd2, 0, 750, null, null)
    private val second = CueTrack(2, "Second", null, cd2, 750, null, null, null)

    @Test fun sequentialSourceArrivalAllowsAnotherSkipBeforeTheTimeout() {
        val pending = CuePendingTransition(cd2, first, 5_000)
        assertTrue(pending.blocks(cd1, first.copy(audio = cd1), 100))
        assertFalse(pending.blocks(cd2, first, 200))
        // Re-checking the same completed transition must not block the next request.
        assertFalse(pending.blocks(cd2, first, 201))
    }

    @Test fun matchingTheAudioFileAloneDoesNotConfirmARandomSegmentSeek() {
        val pending = CuePendingTransition(cd2, second, 5_000)
        assertTrue(pending.blocks(cd2, first, 100))
        assertFalse(pending.blocks(cd2, second, 200))
    }

    @Test fun ordinarySongArrivalAlsoReleasesSequentialTransitions() {
        val pending = CuePendingTransition(cd2, null, 5_000)
        assertTrue(pending.blocks(cd1, null, 100))
        assertFalse(pending.blocks(cd2, null, 200))
    }

    @Test fun metadataRefreshAndArtistFallbackCannotKeepAnArrivedTrackWaiting() {
        val pending = CuePendingTransition(cd2, first, 5_000)
        assertFalse(pending.blocks(cd2, first.copy(title = "Updated", performer = "Fallback"), 200))
        assertTrue(pending.blocks(cd2, first.copy(startFrame = 1), 200))
    }

    @Test fun anUnconfirmedTransitionExpiresAtItsDeadline() {
        val pending = CuePendingTransition(cd2, first, 5_000)
        assertTrue(pending.blocks(cd1, null, 4_999))
        assertFalse(pending.blocks(cd1, null, 5_000))
        assertFalse(pending.blocks(null, null, 6_000))
    }
}
