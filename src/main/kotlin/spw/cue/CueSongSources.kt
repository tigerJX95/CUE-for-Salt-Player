package spw.cue

import java.nio.file.Path

internal data class CueSongSource(val entity: Any, val id: String, val audio: Path)

internal class CueSongSources(
    val all: List<CueSongSource>,
    val originalIds: Set<String>
) {
    val hasUniqueIds = all.mapTo(HashSet(), CueSongSource::id).size == all.size

    fun pathsForRefresh(ids: Set<String>): Set<Path> {
        if (ids.isEmpty()) return emptySet()
        val requested = all.asSequence().filter { it.id in ids }.map(CueSongSource::audio).toSet()
        val folders = requested.mapNotNullTo(hashSetOf()) { it.parent }
        if (folders.isEmpty()) return requested
        val sharedFolders = folders.mapNotNullTo(hashSetOf(), ::discAlbumDirectory)
        val affectedFolders = folders + sharedFolders

        // Include known sources that can gain or lose a match when a shared CUE changes.
        return all.asSequence().map(CueSongSource::audio).filter { audio ->
            val folder = audio.parent
            audio in requested || (folder != null &&
                (folder in sharedFolders || discAlbumDirectory(folder) in affectedFolders))
        }.toSet()
    }

    fun sourcesFor(paths: Set<Path>): Map<Path, List<CueSongSource>> =
        all.asSequence().filter { it.audio in paths }.groupBy(CueSongSource::audio)
}
