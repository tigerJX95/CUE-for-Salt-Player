package spw.cue

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal data class LocatedCue(val path: Path, val sheet: CueSheet)

private val discDirectory = Regex("(?:cd|disc|disk|光盘|碟片)[\\s._-]*[0-9]{1,3}", RegexOption.IGNORE_CASE)

internal fun discAlbumDirectory(directory: Path): Path? = directory.parent?.takeIf {
    it.parent != null && discDirectory.matches(directory.fileName?.toString().orEmpty())
}

internal object CueDiscovery {
    private const val MAX_DIRECTORY_ENTRIES = 4096
    private const val MAX_CUE_FILES = 16

    internal class ParsedCueCache {
        private data class Stamp(val size: Long, val modified: FileTime, val fileKey: Any?)
        private data class Entry(val stamp: Stamp, val cue: LocatedCue)

        private val entries = mutableMapOf<Path, Entry>()
        private var trackCount = 0

        fun load(path: Path): LocatedCue? {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val cached = entries[path]
            if (cached == null && (entries.size >= MAX_CACHED_FILES || trackCount >= MAX_CACHED_TRACKS)) {
                return read(path)
            }
            val stamp = try {
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                Stamp(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey())
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
            if (cached != null && cached.stamp == stamp) return cached.cue
            remove(path)
            val cue = read(path) ?: return null
            val tracks = cue.sheet.tracks.size
            // Keep cached entries at capacity so repeated full-library scans can still reuse them.
            if (stamp != null && tracks <= MAX_CACHED_TRACKS - trackCount) {
                entries[path] = Entry(stamp, cue)
                trackCount += tracks
            }
            return cue
        }

        fun invalidateFolders(folders: Set<Path>) {
            if (folders.isEmpty()) return
            val affected = folders + folders.mapNotNull(::discAlbumDirectory)
            entries.keys.filter { it.parent in affected }.forEach(::remove)
        }

        fun retainScanned(paths: Set<Path>) = entries.keys.filter { it !in paths }.forEach(::remove)

        private fun remove(path: Path) {
            val entry = entries.remove(path) ?: return
            trackCount -= entry.cue.sheet.tracks.size
        }

        private companion object {
            const val MAX_CACHED_FILES = 4_096
            const val MAX_CACHED_TRACKS = 65_536
        }
    }

    fun find(audio: Path): LocatedCue? {
        val folder = audio.parent ?: return null
        findIn(candidates(folder), audio)?.let { return it }
        val shared = discAlbumDirectory(folder)
        if (shared != null) findIn(candidatesOrNull(shared).orEmpty(), audio)?.let { return it }
        return EmbeddedFlacCue.find(audio)
    }

    private fun findIn(candidates: List<Path>, audio: Path): LocatedCue? {
        val sameName = candidates.filter { stem(it) == stem(audio) }
        val preferred = matches(sameName, audio)
        if (preferred.isNotEmpty()) return unique(preferred)

        return matches(candidates - sameName.toSet(), audio).takeIf { it.isNotEmpty() }?.let(::unique)
    }

    // Group direct and shared-directory matches so discs can reuse the same parsed CUE.
    fun refreshMatches(
        cached: Map<Path, LocatedCue>, sources: Set<Path>, active: () -> Boolean
    ): Map<Path, LocatedCue> = (cached - sources) + findAll(sources, active = active)

    fun findAll(
        audioFiles: Collection<Path>, cache: ParsedCueCache? = null, active: () -> Boolean
    ): Map<Path, LocatedCue> = buildMap {
        val scanned = cache?.let { mutableSetOf<Path>() }
        val remaining = mutableListOf<Path>()

        fun resolve(candidates: List<Path>, available: Set<Path>) {
            val byAudio = mutableMapOf<Path, MutableList<LocatedCue>>()
            for (path in candidates) {
                checkActive(active)
                scanned?.add(path)
                val cue = (if (cache == null) read(path) else cache.load(path)) ?: continue
                for (audio in cue.sheet.tracks.map { it.audio }.distinct()) {
                    if (audio in available) byAudio.getOrPut(audio, ::mutableListOf).add(cue)
                }
            }
            for ((audio, matches) in byAudio) {
                val preferred = matches.filter { stem(it.path) == stem(audio) }.ifEmpty { matches }
                preferred.singleOrNull()?.let { put(audio, it) }
            }
            // A conflict at this level needs manual selection rather than a different source.
            remaining.addAll(available - byAudio.keys)
        }

        for ((folder, files) in audioFiles.groupBy { it.parent }) {
            checkActive(active)
            if (folder == null) continue
            val candidates = candidatesOrNull(folder) ?: continue
            resolve(candidates, files.toHashSet())
        }

        val parentGroups = remaining.groupBy { discAlbumDirectory(checkNotNull(it.parent)) }
        remaining.clear()
        for ((folder, files) in parentGroups) {
            checkActive(active)
            if (folder == null) remaining.addAll(files)
            else resolve(candidatesOrNull(folder).orEmpty(), files.toHashSet())
        }
        for (audio in remaining) {
            checkActive(active)
            EmbeddedFlacCue.find(audio)?.let { put(audio, it) }
        }
        if (scanned != null) cache.retainScanned(scanned)
    }

    private fun checkActive(active: () -> Boolean) {
        if (!active() || Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private fun candidatesOrNull(folder: Path): List<Path>? = try {
        candidates(folder)
    } catch (_: IOException) {
        null
    } catch (_: DirectoryIteratorException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun candidates(folder: Path): List<Path> {
        val candidates = mutableListOf<Path>()
        Files.newDirectoryStream(folder).use { entries ->
            for ((index, path) in entries.withIndex()) {
                check(index < MAX_DIRECTORY_ENTRIES) { "目录项目超过 4096 个，请手动打开 CUE" }
                if (path.fileName.toString().endsWith(".cue", ignoreCase = true) &&
                    Files.isRegularFile(path, NOFOLLOW_LINKS)
                ) {
                    candidates.add(path)
                    check(candidates.size <= MAX_CUE_FILES) { "目录内有超过 16 个 CUE，请手动打开" }
                }
            }
        }
        return candidates
    }

    private fun matches(candidates: List<Path>, audio: Path): List<LocatedCue> =
        candidates.mapNotNull { file -> read(file)?.takeIf { it.sheet.tracks.any { track -> track.audio == audio } } }

    private fun read(file: Path): LocatedCue? {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        return try {
            LocatedCue(file, CueParser.read(file))
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun unique(matches: List<LocatedCue>): LocatedCue {
        check(matches.size == 1) { "有多个 CUE 指向当前音频，请手动选择" }
        return matches.single()
    }

    private fun stem(path: Path): Path =
        path.resolveSibling(path.fileName.toString().substringBeforeLast('.'))
}
