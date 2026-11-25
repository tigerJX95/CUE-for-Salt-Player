package spw.cue

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageInputStream
import javax.imageio.stream.ImageInputStream
import javax.imageio.stream.MemoryCacheImageInputStream

internal object FolderCover {
    private val names = listOf("cover.png", "cover.jpg", "folder.png", "folder.jpg")
    private val discDirectory = Regex("(?:cd|disc|disk|光盘|碟片)[\\s._-]*[0-9]{1,3}", RegexOption.IGNORE_CASE)

    // Used by the playback collector. Cache decoded files, not missing covers or library lookups.
    class DecodedCache {
        private data class Stamp(val size: Long, val modified: FileTime, val created: FileTime, val fileKey: Any?)
        private data class Entry(val stamp: Stamp, val image: BufferedImage)
        private val images = LinkedHashMap<Pair<Path, Int>, Entry>(8, 0.75f, true)

        fun invalidateSong(audio: Path) {
            val directory = audio.parent ?: return
            val shared = albumDirectory(directory)
            images.keys.removeAll { (path, _) -> path.parent == directory || path.parent == shared }
        }

        fun read(path: Path, maxSize: Int, attrs: BasicFileAttributes): BufferedImage? {
            val key = path to maxSize
            val stamp = Stamp(attrs.size(), attrs.lastModifiedTime(), attrs.creationTime(), attrs.fileKey())
            images[key]?.takeIf { it.stamp == stamp }?.let { return it.image }
            images.remove(key)
            val image = readFile(path, maxSize) ?: return null
            if (images.size == 8) images.remove(images.keys.first())
            images[key] = Entry(stamp, image)
            return image
        }
    }

    fun load(audio: Path, maxSize: Int = 512): BufferedImage? {
        val directory = audio.parent ?: return null
        return loadDirectory(directory, maxSize)
    }

    fun loadSong(audio: Path, maxSize: Int = 512, cache: DecodedCache? = null): BufferedImage? {
        val directory = audio.parent ?: return null
        return loadDirectory(directory, maxSize, cache) ?: albumDirectory(directory)?.let { loadDirectory(it, maxSize, cache) }
    }

    // A catalog thumbnail exposes only its representative audio, not the album's track list.
    // Only explicit disc folder names permit the one-level album-directory fallback.
    // Callers must first verify that the representative audio has a matching CUE.
    fun loadCatalog(audio: Path, maxSize: Int = 512): BufferedImage? {
        val directory = audio.parent ?: return null
        return albumDirectory(directory)?.let { loadDirectory(it, maxSize) } ?: loadDirectory(directory, maxSize)
    }

    private fun albumDirectory(directory: Path): Path? = directory.parent?.takeIf {
        it.parent != null && discDirectory.matches(directory.fileName?.toString().orEmpty())
    }

    fun loadMultiDisc(audioFiles: List<Path>, maxSize: Int = 512): BufferedImage? {
        val directories = audioFiles.mapNotNull { it.parent }.distinct()
        val shared = directories.firstOrNull()?.parent?.takeIf { candidate ->
            directories.size > 1 && candidate.parent != null && directories.all { it.parent == candidate }
        }
        return (listOfNotNull(shared) + directories).firstNotNullOfOrNull { loadDirectory(it, maxSize) }
    }

    private fun loadDirectory(directory: Path, maxSize: Int, cache: DecodedCache? = null): BufferedImage? {
        for (name in names) {
            val path = directory.resolve(name)
            try {
                val image = if (cache == null) {
                    if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) !in 1..16_777_216) continue
                    readFile(path, maxSize)
                } else {
                    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    if (!attrs.isRegularFile || attrs.size() !in 1..16_777_216) continue
                    cache.read(path, maxSize, attrs)
                }
                if (image != null) return image
            } catch (_: IOException) {
                continue
            } catch (_: SecurityException) {
                continue
            }
        }
        return null
    }

    private fun readFile(path: Path, maxSize: Int): BufferedImage? =
        FileImageInputStream(path.toFile()).use { decode(it, maxSize, folderImage = true) }

    fun decodeEmbedded(bytes: ByteArray, maxSize: Int): BufferedImage? {
        if (bytes.size !in 1..16_777_216) return null
        return MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { decode(it, maxSize, folderImage = false) }
    }

    private fun decode(input: ImageInputStream, maxSize: Int, folderImage: Boolean): BufferedImage? {
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
        return try {
            if (folderImage && reader.formatName.lowercase() !in setOf("png", "jpeg")) return null
            reader.setInput(input, true, true)
            val size = maxOf(reader.getWidth(0), reader.getHeight(0))
            if (size !in 1..8_192) return null
            val sample = (size + maxSize - 1) / maxSize
            reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) })
        } finally {
            reader.dispose()
        }
    }
}
