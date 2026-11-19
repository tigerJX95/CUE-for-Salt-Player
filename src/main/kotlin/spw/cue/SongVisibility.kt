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
import java.lang.ref.WeakReference

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

internal class SongVisibility(path: Path) {
    private val path = path.toAbsolutePath().normalize()
    private val removedPath = this.path.resolveSibling("removed-cue-tracks.txt")
    private var hiddenKeys = read(this.path)
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

    init {
        synchronized(storageLock) {
            observers.removeAll { it.get() == null }
            val previous = observers.mapNotNull { it.get() }.firstOrNull { it.path == this.path }
            observers.add(WeakReference(this))
            // Another instance may have written while this instance was being constructed.
            val latestRemoved = try { read(removedPath) }
                catch (_: java.io.IOException) { previous?.records?.value?.removedFromLibrary ?: removedKeys }
                catch (_: SecurityException) { previous?.records?.value?.removedFromLibrary ?: removedKeys }
            publishSnapshot(SongVisibilityRecords(read(this.path), latestRemoved))
        }
    }

    fun hide(key: String) = hide(setOf(key))

    fun hide(entries: Set<String>) = update { it.copy(hiddenInSongs = it.hiddenInSongs + entries) }

    fun remove(key: String) = remove(setOf(key))

    fun remove(entries: Set<String>) = update { it.copy(removedFromLibrary = it.removedFromLibrary + entries) }

    fun restoreAll() = restoreMatching(null)

    fun restore(entries: Set<String>) {
        if (entries.isNotEmpty()) restoreMatching(entries)
    }

    private fun restoreMatching(entries: Set<String>?) = update { current ->
        if (entries == null) SongVisibilityRecords() else SongVisibilityRecords(
            current.hiddenInSongs - entries, current.removedFromLibrary - entries
        )
    }

    fun refresh() {
        synchronized(storageLock) { publishSnapshot(readSnapshot()) }
    }

    private fun readSnapshot() = SongVisibilityRecords(read(path), read(removedPath))

    private fun update(change: (SongVisibilityRecords) -> SongVisibilityRecords) {
        synchronized(storageLock) {
            // Always merge against disk, never a previously loaded instance's cached sets.
            val before = readSnapshot()
            val after = change(before)
            var persisted = before
            if (after.hiddenInSongs != before.hiddenInSongs) {
                write(path, after.hiddenInSongs)
                persisted = persisted.copy(hiddenInSongs = after.hiddenInSongs)
                publishSnapshot(persisted)
            }
            if (after.removedFromLibrary != before.removedFromLibrary) {
                write(removedPath, after.removedFromLibrary)
                persisted = persisted.copy(removedFromLibrary = after.removedFromLibrary)
            }
            publishSnapshot(persisted)
        }
    }

    private fun publishSnapshot(snapshot: SongVisibilityRecords) {
        observers.removeAll { it.get() == null }
        val targets = observers.mapNotNull { it.get() }.filter { it.path == path }
        targets.forEach { it.acceptSnapshot(snapshot) }
    }

    private fun acceptSnapshot(snapshot: SongVisibilityRecords) {
        hiddenKeys = snapshot.hiddenInSongs
        if (removedKeys != snapshot.removedFromLibrary) CueRemoval.publishAlbum(albumRemoved, snapshot.removedFromLibrary)
        removedKeys = snapshot.removedFromLibrary
        records.value = snapshot
        keys.value = snapshot.hiddenInSongs + snapshot.removedFromLibrary
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
        private val storageLock = Any()
        private val observers = mutableListOf<WeakReference<SongVisibility>>()

        fun key(track: CueTrack): String {
            val path = track.audio.toAbsolutePath().normalize().toUri().toASCIIString()
            val identity = "$path\n${track.number}\n${track.startFrame}"
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()))
        }
    }
}
