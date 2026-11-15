package spw.cue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumHeaderStatisticsTest {
    @Test fun countsDiscsAndReplacesEachWholeTrackWithItsVisibleCueTracks() {
        val groups = listOf(listOf("cd1"), listOf("cd2"))
        assertEquals(AlbumHeaderCount(2, 26), albumHeaderCount(groups, mapOf("cd1" to 13, "cd2" to 13)))
        assertEquals(AlbumHeaderCount(2, 25), albumHeaderCount(groups, mapOf("cd1" to 12, "cd2" to 13)))
    }

    @Test fun keepsNativeSongsInMixedAlbumsAndCountsFullyRemovedCueSourcesAsZero() {
        val groups = listOf(listOf("image", "single"), listOf("bonus"))
        assertEquals(AlbumHeaderCount(2, 14), albumHeaderCount(groups, mapOf("image" to 12)))
        assertEquals(AlbumHeaderCount(2, 2), albumHeaderCount(groups, mapOf("image" to 0)))
        assertEquals(AlbumHeaderCount(1, 13), albumHeaderCount(listOf(listOf("cd1")), mapOf("cd1" to 13)))
        assertNull(albumHeaderCount(groups, emptyMap()))
    }
}
