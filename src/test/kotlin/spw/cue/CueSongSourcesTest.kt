package spw.cue

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CueSongSourcesTest {
    @Test fun refreshIncludesAllLibraryEntriesThatShareTheAffectedFile() {
        val first = source("cd1", "first", "disc1.wav")
        val alias = source("cd1-alias", "alias", "disc1.wav")
        val second = source("cd2", "second", "disc2.wav")
        val sources = CueSongSources(listOf(first, alias, second), setOf(first.id, alias.id, second.id))

        val paths = sources.pathsForRefresh(setOf(first.id, "missing"))
        assertEquals(setOf(first.audio), paths)
        assertEquals(mapOf(first.audio to listOf(first, alias)), sources.sourcesFor(paths))
        assertTrue(sources.hasUniqueIds)
    }

    @Test fun duplicateHostIdsRequireProjectingInHostOrder() {
        val first = source("duplicate", "first", "disc1.wav")
        val second = source("duplicate", "second", "disc2.wav")
        val sources = CueSongSources(listOf(first, second), setOf(first.id))

        assertEquals(setOf(first.audio, second.audio), sources.pathsForRefresh(setOf(first.id)))
        assertEquals(listOf(first, second), sources.all)
        assertFalse(sources.hasUniqueIds)
    }

    @Test fun refreshingOneDiscFindsAllSourcesThatCanUseTheSharedAlbumCue() {
        val first = source("cd1", "first", "music/Album/CD1/image.wav")
        val second = source("cd2", "second", "music/Album/Disc 2/image.wav")
        val albumRoot = source("bonus", "root", "music/Album/bonus.wav")
        val unrelatedFolder = source("other", "other", "music/Album/Extras/image.wav")
        val unrelatedAlbum = source("another", "another", "music/Another Album/CD2/image.wav")
        val nested = source("nested", "nested", "music/Album/CD1/CD2/image.wav")
        val all = listOf(first, second, albumRoot, unrelatedFolder, unrelatedAlbum, nested)
        val sources = CueSongSources(all, all.mapTo(hashSetOf(), CueSongSource::id))

        // CD1's folder can also supply a shared CUE to its own immediate disc subfolder.
        assertEquals(setOf(first.audio, second.audio, albumRoot.audio, nested.audio),
            sources.pathsForRefresh(setOf(first.id)))
        assertEquals(setOf(first.audio, second.audio, albumRoot.audio), sources.pathsForRefresh(setOf(albumRoot.id)))
        assertTrue(sources.pathsForRefresh(setOf("missing")).isEmpty())
    }

    private fun source(id: String, entity: String, path: String) = CueSongSource(entity, id, Path.of(path))
}
