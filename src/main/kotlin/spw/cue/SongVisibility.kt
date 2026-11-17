package spw.cue

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat

internal data class SongVisibilityRecords(
    val hiddenInSongs: Set<String> = emptySet(),
    val removedFromLibrary: Set<String> = emptySet()
) {
    fun label(key: String): String? = when {
        key in hiddenInSongs && key in removedFromLibrary -> "已隐藏 · 已移除"
        key in removedFromLibrary -> "已从曲库移除"
        key in hiddenInSongs -> "已从歌曲页隐藏"
        else -> null
    }
}

internal class SongVisibility(private val path: Path) {
    private val removedPath = path.resolveSibling("removed-cue-tracks.txt")
    private var hiddenKeys = read(path)
    private var removedKeys = try {
        read(removedPath)
    } catch (_: java.io.IOException) {
        emptySet()
    } catch (_: SecurityException) {
        emptySet()
    }
    val albumRemoved = CueRemoval.albumState(removedKeys)
    private val records = MutableStateFlow(SongVisibilityRecords(hiddenKeys, removedKeys))
    val recovery = records.asStateFlow()
    private val keys = MutableStateFlow(hiddenKeys + removedKeys)
    val hidden = keys.asStateFlow()

    fun hide(key: String) = hide(setOf(key))

    @Synchronized
    fun hide(entries: Set<String>) {
        val updated = hiddenKeys + entries
        if (updated == hiddenKeys) return
        write(path, updated)
        hiddenKeys = updated
        publishVisibility()
    }

    fun remove(key: String) = remove(setOf(key))

    @Synchronized
    fun remove(entries: Set<String>) {
        // Re-read on each write so a temporary failure never overwrites earlier removals.
        val existing = read(removedPath)
        val updated = existing + entries
        if (updated != existing) write(removedPath, updated)
        publishRemoved(updated)
    }

    @Synchronized
    fun restoreAll() = restoreMatching(null)

    @Synchronized
    fun restore(entries: Set<String>) {
        if (entries.isNotEmpty()) restoreMatching(entries)
    }

    private fun restoreMatching(entries: Set<String>?) {
        val existingRemoved = read(removedPath)
        val updatedRemoved = if (entries == null) emptySet() else existingRemoved - entries
        if (updatedRemoved != existingRemoved) write(removedPath, updatedRemoved)
        publishRemoved(updatedRemoved)
        val updatedHidden = if (entries == null) emptySet() else hiddenKeys - entries
        if (updatedHidden != hiddenKeys) {
            write(path, updatedHidden)
            hiddenKeys = updatedHidden
            publishVisibility()
        }
    }

    private fun publishRemoved(updated: Set<String>) {
        if (removedKeys != updated) CueRemoval.publishAlbum(albumRemoved, updated)
        removedKeys = updated
        publishVisibility()
    }

    private fun publishVisibility() {
        records.value = SongVisibilityRecords(hiddenKeys, removedKeys)
        keys.value = hiddenKeys + removedKeys
    }

    private fun write(path: Path, updated: Set<String>) {
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, ".song-visibility-", ".tmp")
        try {
            Files.write(temp, updated.sorted())
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun read(path: Path): Set<String> = try {
        val reader = Files.newBufferedReader(path, ISO_8859_1)
        try {
            val result = linkedSetOf<String>()
            while (true) {
                val key = reader.readLine() ?: break
                if (key.matches(keyPattern)) result.add(key)
            }
            result
        } finally {
            reader.close()
        }
    } catch (_: NoSuchFileException) {
        emptySet()
    }

    companion object {
        private val keyPattern = Regex("[0-9a-f]{64}")

        fun key(track: CueTrack): String {
            val path = track.audio.toAbsolutePath().normalize().toUri().toASCIIString()
            val identity = "$path\n${track.number}\n${track.startFrame}"
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()))
        }
    }
}
