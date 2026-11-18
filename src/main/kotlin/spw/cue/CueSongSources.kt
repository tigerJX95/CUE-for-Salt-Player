package spw.cue

import java.nio.file.Path

internal data class CueSongSource(val entity: Any, val id: String, val audio: Path)

internal class CueSongSources(
    val all: List<CueSongSource>,
    val originalIds: Set<String>
) {
    val hasUniqueIds = all.mapTo(HashSet(), CueSongSource::id).size == all.size

    fun pathsFor(ids: Set<String>): Set<Path> =
        if (ids.isEmpty()) emptySet()
        else all.asSequence().filter { it.id in ids }.map(CueSongSource::audio).toSet()

    fun sourcesFor(paths: Set<Path>): Map<Path, List<CueSongSource>> =
        all.asSequence().filter { it.audio in paths }.groupBy(CueSongSource::audio)
}
