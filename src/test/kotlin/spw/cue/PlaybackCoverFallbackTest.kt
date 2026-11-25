package spw.cue

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackCoverFallbackTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun playbackUsesTheDiscAndSharedAlbumArtworkBeforeTheLibraryCache() {
        val album = temp.newFolder("Album").toPath()
        val first = disc(album, "CD1")
        val second = disc(album, "Disc 2")
        album.resolve("folder.png").toFile().writeBytes(png(Color.RED))
        second.parent.resolve("cover.png").toFile().writeBytes(png(Color.BLUE))

        assertEquals(Color.RED.rgb, readPlaybackCover(first) { error("cache should not be read") }?.getRGB(0, 0))
        assertEquals(Color.BLUE.rgb, readPlaybackCover(second) { error("cache should not be read") }?.getRGB(0, 0))
    }

    @Test fun onlyACueMatchCanUseTheLibraryCache() {
        val album = temp.newFolder("Album").toPath()
        val matched = disc(album, "CD1")
        val unmatched = matched.resolveSibling("ordinary.wav").toFile().apply { createNewFile() }.toPath()

        assertNull(readPlaybackCover(unmatched) { error("non-CUE file should not look up artwork") })
        assertEquals(Color.GREEN.rgb, readPlaybackCover(matched) { png(Color.GREEN) }?.getRGB(0, 0))
        assertNull(readPlaybackCover(matched) { byteArrayOf(1, 2, 3) })
    }

    @Test fun sharesDecodedArtworkAcrossDiscsAndDetectsReplacementOrRemoval() {
        val album = temp.newFolder("Album").toPath()
        val first = disc(album, "CD1")
        val second = disc(album, "CD2")
        val cover = album.resolve("folder.png")
        Files.write(cover, png(Color.RED))
        val cache = FolderCover.DecodedCache()

        val initial = checkNotNull(readPlaybackCover(first, cache))
        assertSame(initial, readPlaybackCover(second, cache))
        val updatedTime = Files.getLastModifiedTime(cover).toInstant().plusSeconds(10)
        Files.write(cover, png(Color.BLUE))
        Files.setLastModifiedTime(cover, FileTime.from(updatedTime))
        val replaced = checkNotNull(readPlaybackCover(second, cache))
        assertNotSame(initial, replaced)
        assertEquals(Color.BLUE.rgb, replaced.getRGB(0, 0))

        Files.write(first.parent.resolve("cover.png"), png(Color.GREEN))
        assertEquals(Color.GREEN.rgb, readPlaybackCover(first, cache)?.getRGB(0, 0))
        Files.delete(cover)
        assertNull(readPlaybackCover(second, cache))
    }

    @Test fun evictsLeastRecentlyUsedDecodedArtwork() {
        val cache = FolderCover.DecodedCache()
        val sources = (1..9).map { index ->
            val directory = temp.newFolder("Album $index").toPath()
            disc(directory, "CD1").also { Files.write(directory.resolve("cover.png"), png(Color.RED)) }
        }
        val artwork = sources.take(8).map { checkNotNull(readPlaybackCover(it, cache)) }
        assertSame(artwork[0], readPlaybackCover(sources[0], cache))
        checkNotNull(readPlaybackCover(sources[8], cache))

        assertSame(artwork[0], readPlaybackCover(sources[0], cache))
        assertNotSame(artwork[1], checkNotNull(readPlaybackCover(sources[1], cache)))
    }

    @Test fun refreshingADiscRereadsItsSharedCoverAndKeepsOtherAlbumsCached() {
        val album = temp.newFolder("Album").toPath()
        val firstDisc = disc(album, "CD1")
        val secondDisc = disc(album, "CD2")
        val other = temp.newFolder("Other").toPath()
        val otherDisc = disc(other, "CD1")
        Files.write(album.resolve("folder.png"), png(Color.RED))
        Files.write(other.resolve("folder.png"), png(Color.BLUE))
        val cache = FolderCover.DecodedCache()
        val sharedCover = checkNotNull(readPlaybackCover(firstDisc, cache))
        val otherCover = checkNotNull(readPlaybackCover(otherDisc, cache))

        cache.invalidateSong(secondDisc)

        val refreshed = checkNotNull(readPlaybackCover(secondDisc, cache))
        assertNotSame(sharedCover, refreshed)
        assertSame(refreshed, readPlaybackCover(firstDisc, cache))
        assertSame(otherCover, readPlaybackCover(otherDisc, cache))
    }

    private fun disc(album: Path, name: String): Path {
        val folder = album.resolve(name).toFile().apply { mkdir() }
        folder.resolve("album.cue").writeText("FILE album.wav WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\n")
        return folder.resolve("album.wav").apply { createNewFile() }.toPath()
    }

    private fun png(color: Color): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, color.rgb) }
        return ByteArrayOutputStream().use { bytes ->
            ImageIO.write(image, "png", bytes)
            bytes.toByteArray()
        }
    }
}
