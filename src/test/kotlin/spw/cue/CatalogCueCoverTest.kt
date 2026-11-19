package spw.cue

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogCueCoverTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun findsSingleDiscCoverWithoutANativeCoverPath() {
        val album = temp.newFolder("Single")
        val audio = cue(album)
        image(album.resolve("folder.png"), Color.BLUE)
        val lookup = lookup(listOf(item(audio, "1")))
        assertEquals(Color.BLUE.rgb, lookup.load(CatalogCoverQuery("Artist", false), null) { true }?.getRGB(0, 0))
    }

    @Test fun findsMultiDiscCoverInSecondDiscAndThenPrefersSharedArtwork() {
        val album = temp.newFolder("Double")
        val cd1 = album.resolve("CD1").apply { mkdir() }
        val cd2 = album.resolve("CD2").apply { mkdir() }
        val first = cue(cd1)
        val second = cue(cd2)
        image(cd2.resolve("folder.png"), Color.BLUE)
        val lookup = lookup(listOf(item(first, "1"), item(second, "2")))
        assertEquals(Color.BLUE.rgb, lookup.load(CatalogCoverQuery("Artist", false), first) { true }?.getRGB(0, 0))
        image(album.resolve("cover.png"), Color.RED)
        assertEquals(Color.RED.rgb, lookup.load(CatalogCoverQuery("Artist", false), first) { true }?.getRGB(0, 0))
    }

    @Test fun separatesTrackArtistsFromAlbumArtistsAndMatchesCollaborators() {
        val album = temp.newFolder("Album")
        val audio = cue(album)
        image(album.resolve("folder.png"), Color.GREEN)
        val lookup = lookup(listOf(item(audio, "1").copy(artist = "Singer / Guest", albumArtist = "Various")))
        assertNotNull(lookup.load(CatalogCoverQuery("Guest", false), null) { true })
        assertNotNull(lookup.load(CatalogCoverQuery("Various", true), null) { true })
        assertNull(lookup.load(CatalogCoverQuery("Various", false), null) { true })
        assertNull(lookup.load(CatalogCoverQuery("Other", false), null) { true })
    }

    @Test fun skipsUnrelatedArtistsAndAudioWithoutMatchedCue() {
        val ordinary = temp.newFolder("Ordinary")
        image(ordinary.resolve("folder.png"), Color.RED)
        val other = temp.newFolder("Other")
        val otherAudio = cue(other)
        image(other.resolve("folder.png"), Color.BLUE)
        val lookup = lookup(listOf(item(ordinary.resolve("disc.wav").toPath(), "1"), item(otherAudio, "2").copy(artist = "Other")))
        assertNull(lookup.load(CatalogCoverQuery("Artist", false), null) { true })
    }

    @Test fun albumCardMatchesBothAlbumTitleAndArtistWhenNativeCoverIsBlank() {
        val album = temp.newFolder("Album")
        val audio = cue(album)
        image(album.resolve("folder.png"), Color.GREEN)
        val lookup = lookup(listOf(item(audio, "1")))
        assertNotNull(lookup.load(CatalogCoverQuery("Album Artist", true, "Album"), null) { true })
        assertNull(lookup.load(CatalogCoverQuery("Other Artist", true, "Album"), null) { true })
        assertNull(lookup.load(CatalogCoverQuery("Album Artist", true, "Other Album"), null) { true })
    }

    @Test fun paginatesSharesSnapshotAndRefreshesAfterExpiry() {
        val album = temp.newFolder("Album")
        val audio = cue(album)
        image(album.resolve("folder.png"), Color.BLUE)
        var now = 0L
        val calls = mutableListOf<String?>()
        val first = (1..256).map { item(audio.resolveSibling("other$it.wav"), it.toString()).copy(artist = "Other") }
        val lookup = CatalogCueCover(page = { after, _, _ ->
            calls.add(after)
            if (after == null) first else listOf(item(audio, "257"))
        }, clock = { now })
        assertNotNull(lookup.load(CatalogCoverQuery("Artist", false), null) { true })
        assertNotNull(lookup.load(CatalogCoverQuery("Album Artist", true), null) { true })
        assertEquals(listOf(null, "256"), calls)
        now += TimeUnit.SECONDS.toNanos(31)
        assertNotNull(lookup.load(CatalogCoverQuery("Artist", false), null) { true })
        assertEquals(listOf(null, "256", null, "256"), calls)
    }

    @Test fun rejectsRepeatedCursorsAndDoesNotReadAfterStop() {
        val rows = (1..256).map { item(temp.root.toPath().resolve("disc.wav"), it.toString()) }
        var calls = 0
        val lookup = CatalogCueCover(page = { _, _, _ -> calls++; rows })
        assertThrows(IllegalStateException::class.java) {
            lookup.load(CatalogCoverQuery("Artist", false), null) { true }
        }
        assertEquals(2, calls)
        assertThrows(InterruptedException::class.java) {
            lookup.load(CatalogCoverQuery("Artist", false), null) { false }
        }
        assertEquals(2, calls)
    }

    private fun lookup(rows: List<MediaItem>) = CatalogCueCover(page = { _, _, _ -> rows })

    private fun item(audio: Path, id: String) =
        MediaItem("Album", "Artist", "Album", "Album Artist", audio.toString(), id = id, duration = 10_000)

    private fun cue(folder: File): Path {
        folder.resolve("disc.cue").writeText("FILE disc.wav WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00")
        return folder.resolve("disc.wav").toPath()
    }

    private fun image(file: File, color: Color) {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try { g.color = color; g.fillRect(0, 0, 4, 4) } finally { g.dispose() }
        ImageIO.write(image, "png", file)
    }
}
