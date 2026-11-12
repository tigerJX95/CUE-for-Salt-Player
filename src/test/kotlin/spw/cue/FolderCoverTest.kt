package spw.cue

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderCoverTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun usesOnlyTheAudioFolderAndFallsBackPastCorruptArtwork() {
        val audio = temp.newFolder("醉拳").resolve("整轨.wav").toPath()
        writeImage(temp.root.resolve("cover.png"), "png", Color.RED)
        assertNull(FolderCover.load(audio))
        val dir = audio.parent.toFile()
        dir.resolve("cover.png").writeText("broken png")
        writeImage(dir.resolve("folder.jpg"), "jpg", Color.BLUE)
        assertNotNull(FolderCover.load(audio))
        writeImage(dir.resolve("cover.jpg"), "jpg", Color.RED)
        writeImage(dir.resolve("folder.png"), "png", Color.GREEN)
        assertTrue(Color(checkNotNull(FolderCover.load(audio)).getRGB(0, 0)).red >= 253)
    }

    @Test fun downsamplesLargeCoversBeforeDisplayingThem() {
        val cover = temp.newFile("cover.png")
        ImageIO.write(BufferedImage(2048, 1024, BufferedImage.TYPE_INT_RGB), "png", cover)
        val image = checkNotNull(FolderCover.load(temp.root.resolve("audio.wav").toPath()))
        assertEquals(512, image.width)
        assertEquals(256, image.height)
    }

    @Test fun formatsTheDisplayInWholeSeconds() {
        assertEquals("00:00", formatCueTime(999))
        assertEquals("53:58", formatCueTime(3_238_160))
        assertEquals("01:00:00", formatCueTime(3_600_000))
    }

    private fun writeImage(file: File, format: String, color: Color) {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = color
            graphics.fillRect(0, 0, image.width, image.height)
        } finally {
            graphics.dispose()
        }
        ImageIO.write(image, format, file)
    }
}
