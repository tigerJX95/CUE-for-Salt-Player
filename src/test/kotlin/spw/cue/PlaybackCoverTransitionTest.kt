package spw.cue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoverTransitionTest {
    @Test fun defersTheNativeClearAndPublishesTheNewCoverDirectly() {
        val transition = PlaybackCoverTransition<String>()
        val item = Any()
        val updates = mutableListOf<String?>()

        assertTrue(transition.interceptMissing(item, true, updates::add))
        assertTrue(updates.isEmpty())
        assertTrue(transition.complete(item, item, "folder cover", false, publish = updates::add))
        assertEquals(listOf("folder cover"), updates)
    }

    @Test fun rapidTrackChangesRejectStaleArtworkAndKeepNativeArtwork() {
        val transition = PlaybackCoverTransition<String>()
        val previous = Any()
        val current = Any()
        val updates = mutableListOf<String?>()

        assertTrue(transition.interceptMissing(current, true, updates::add))
        assertFalse(transition.complete(previous, current, "old cover", false, publish = updates::add))
        transition.nativePublished()
        assertTrue(transition.complete(current, current, "fallback cover", true, publish = updates::add))
        assertTrue(updates.isEmpty())
        assertFalse(transition.restoreAfterNativeClear(previous, updates::add))
        assertTrue(transition.restoreAfterNativeClear(current, updates::add))
        assertEquals(listOf("fallback cover"), updates)
    }

    @Test fun aTrackWithoutArtworkClearsThePlaceholderOnce() {
        val transition = PlaybackCoverTransition<String>()
        val item = Any()
        val updates = mutableListOf<String?>()

        assertTrue(transition.interceptMissing(item, true, updates::add))
        assertTrue(transition.complete(item, item, null, false, publish = updates::add))
        assertEquals(listOf(null), updates)
        assertFalse(transition.interceptMissing(item, true, updates::add))
        assertFalse(transition.restoreAfterNativeClear(item, updates::add))
        assertFalse(transition.reset())
    }

    @Test fun refreshingTheCurrentItemReplacesAndClearsItsFallback() {
        val transition = PlaybackCoverTransition<String>()
        val item = Any()
        val alternate = Any()
        val updates = mutableListOf<String?>()
        assertTrue(transition.complete(item, item, "old", false, publish = updates::add))

        assertFalse(transition.complete(item, alternate, "late", false,
            replaceFallback = true, publish = updates::add))
        assertTrue(transition.complete(item, item, "updated", false,
            replaceFallback = true, publish = updates::add))
        assertTrue(transition.complete(item, item, null, false,
            replaceFallback = true, publish = updates::add))
        assertFalse(transition.restoreAfterNativeClear(item, updates::add))
        assertEquals(listOf("old", "updated", null), updates)
    }

    @Test fun refreshingDoesNotOverrideNativeArtwork() {
        val transition = PlaybackCoverTransition<String>()
        val item = Any()
        val updates = mutableListOf<String?>()
        assertTrue(transition.complete(item, item, "new fallback", true, publish = updates::add))
        assertTrue(updates.isEmpty())
        assertTrue(transition.restoreAfterNativeClear(item, updates::add))
        assertEquals(listOf("new fallback"), updates)
    }

}
