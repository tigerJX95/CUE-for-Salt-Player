package spw.cue

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageInputStream
import javax.imageio.stream.ImageInputStream
import javax.imageio.stream.MemoryCacheImageInputStream

internal object FolderCover {
    private val names = listOf("cover.png", "cover.jpg", "folder.png", "folder.jpg")
    private val discDirectory = Regex("(?:cd|disc|disk|光盘|碟片)[\\s._-]*[0-9]{1,3}", RegexOption.IGNORE_CASE)

    fun load(audio: Path, maxSize: Int = 512): BufferedImage? {
        val directory = audio.parent ?: return null
        return loadDirectory(directory, maxSize)
    }

    fun loadSong(audio: Path, maxSize: Int = 512): BufferedImage? {
        val directory = audio.parent ?: return null
        return loadDirectory(directory, maxSize) ?: albumDirectory(directory)?.let { loadDirectory(it, maxSize) }
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

    private fun loadDirectory(directory: Path, maxSize: Int): BufferedImage? {
        for (name in names) {
            val path = directory.resolve(name)
            try {
                if (Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) in 1..16_777_216) {
                    FileImageInputStream(path.toFile()).use { decode(it, maxSize, folderImage = true) }
                        ?.let { return it }
                }
            } catch (_: IOException) {
                continue
            } catch (_: SecurityException) {
                continue
            }
        }
        return null
    }

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
