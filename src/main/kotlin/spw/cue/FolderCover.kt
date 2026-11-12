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

    fun load(audio: Path, maxSize: Int = 512): BufferedImage? {
        val directory = audio.parent ?: return null
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
