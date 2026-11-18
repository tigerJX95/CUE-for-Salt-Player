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

        val paths = sources.pathsFor(setOf(first.id, "missing"))
        assertEquals(setOf(first.audio), paths)
        assertEquals(mapOf(first.audio to listOf(first, alias)), sources.sourcesFor(paths))
        assertTrue(sources.hasUniqueIds)
    }

    @Test fun duplicateHostIdsRequireProjectingInHostOrder() {
        val first = source("duplicate", "first", "disc1.wav")
        val second = source("duplicate", "second", "disc2.wav")
        val sources = CueSongSources(listOf(first, second), setOf(first.id))

        assertEquals(setOf(first.audio, second.audio), sources.pathsFor(setOf(first.id)))
        assertEquals(listOf(first, second), sources.all)
        assertFalse(sources.hasUniqueIds)
    }

    private fun source(id: String, entity: String, path: String) = CueSongSource(entity, id, Path.of(path))
}
