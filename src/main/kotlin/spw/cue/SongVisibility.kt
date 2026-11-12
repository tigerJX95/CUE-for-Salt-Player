package spw.cue

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat

internal class SongVisibility(private val path: Path) {
    private val keys = MutableStateFlow(read())
    val hidden = keys.asStateFlow()

    @Synchronized
    fun hide(key: String) = save(keys.value + key)

    @Synchronized
    fun restoreAll() = save(emptySet())

    private fun save(updated: Set<String>) {
        if (updated == keys.value) return
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, ".song-visibility-", ".tmp")
        try {
            Files.write(temp, updated.sorted())
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
        keys.value = updated
    }

    private fun read(): Set<String> = try {
        Files.newBufferedReader(path).useLines { lines ->
            lines.filter { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }.toSet()
        }
    } catch (_: NoSuchFileException) {
        emptySet()
    }

    companion object {
        fun key(track: CueTrack): String {
            val path = track.audio.toAbsolutePath().normalize().toUri().toASCIIString()
            val identity = "$path\n${track.number}\n${track.startFrame}"
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()))
        }
    }
}
