package spw.cue

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal data class LocatedCue(val path: Path, val sheet: CueSheet)

internal object CueDiscovery {
    private const val MAX_DIRECTORY_ENTRIES = 4096
    private const val MAX_CUE_FILES = 16

    fun find(audio: Path): LocatedCue? {
        val folder = audio.parent ?: return null
        val candidates = candidates(folder)
        val sameName = candidates.filter { stem(it) == stem(audio) }
        val preferred = matches(sameName, audio)
        if (preferred.isNotEmpty()) return unique(preferred)

        return matches(candidates - sameName.toSet(), audio).takeIf { it.isNotEmpty() }?.let(::unique)
            ?: EmbeddedFlacCue.find(audio)
    }

    // The Songs page receives whole-library snapshots. Read each directory and CUE once per snapshot.
    fun findAll(audioFiles: Collection<Path>, active: () -> Boolean): Map<Path, LocatedCue> = buildMap {
        for ((folder, files) in audioFiles.groupBy { it.parent }) {
            if (!active() || Thread.currentThread().isInterrupted) throw InterruptedException()
            if (folder == null) continue
            val available = files.toHashSet()
            val candidates = try {
                candidates(folder)
            } catch (_: IOException) {
                continue
            } catch (_: IllegalStateException) {
                continue
            } catch (_: SecurityException) {
                continue
            }
            val byAudio = mutableMapOf<Path, MutableList<LocatedCue>>()
            for (path in candidates) {
                val cue = read(path) ?: continue
                for (audio in cue.sheet.tracks.map { it.audio }.distinct()) {
                    if (audio in available) byAudio.getOrPut(audio, ::mutableListOf).add(cue)
                }
            }
            for ((audio, matches) in byAudio) {
                val preferred = matches.filter { stem(it.path) == stem(audio) }.ifEmpty { matches }
                preferred.singleOrNull()?.let { put(audio, it) }
            }
            for (audio in available - byAudio.keys) {
                if (!active() || Thread.currentThread().isInterrupted) throw InterruptedException()
                EmbeddedFlacCue.find(audio)?.let { put(audio, it) }
            }
        }
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
