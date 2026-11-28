package spw.cue

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CueDiscoveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun prefersTheMatchingSameStemAndDecodesGbk() {
        val audio = folder().resolve("整轨.WAV")
        cue("other.cue", "整轨.WAV")
        val preferred = cue("整轨.CUE", "整轨.WAV", Charset.forName("GBK"))
        val result = CueDiscovery.find(audio)
        assertEquals(preferred, result?.path)
        assertEquals("GBK", result?.sheet?.encoding)
    }

    @Test fun usesFileReferenceWhenTheCueNameDiffers() {
        val audio = folder().resolve("整轨.WAV")
        cue("整轨.cue", "另一张.WAV")
        val indexed = cue("唱片目录.cue", "整轨.WAV")
        assertEquals(indexed, CueDiscovery.find(audio)?.path)
    }

    @Test fun asksForManualSelectionWhenTwoDifferentNamesMatch() {
        cue("A.cue", "整轨.WAV")
        cue("B.cue", "整轨.WAV")
        val error = assertThrows(IllegalStateException::class.java) {
            CueDiscovery.find(folder().resolve("整轨.WAV"))
        }
        assertTrue(error.message.orEmpty().contains("多个 CUE"))
    }

    @Test fun ignoresMalformedAndUnrelatedFilesAndDoesNotSearchSubdirectories() {
        Files.writeString(folder().resolve("整轨.cue"), "invalid cue")
        cue("unrelated.cue", "其他.wav")
        val nested = Files.createDirectory(folder().resolve("nested"))
        Files.writeString(nested.resolve("album.cue"), "FILE \"../整轨.WAV\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00")
        assertNull(CueDiscovery.find(folder().resolve("整轨.WAV")))
    }

    @Test fun limitsTheNumberOfCandidateFiles() {
        repeat(17) { cue("alt-$it.cue", "整轨.WAV") }
        val error = assertThrows(IllegalStateException::class.java) {
            CueDiscovery.find(folder().resolve("整轨.WAV"))
        }
        assertTrue(error.message.orEmpty().contains("16"))
    }

    @Test fun reusesUnchangedExternalCueAcrossLibrarySnapshots() {
        val audio = folder().resolve("album.wav")
        cue("album.cue", "album.wav")
        val cache = CueDiscovery.ParsedCueCache()

        val first = CueDiscovery.findAll(listOf(audio), cache) { true }.getValue(audio)
        val second = CueDiscovery.findAll(listOf(folder().resolve("other.wav"), audio), cache) { true }.getValue(audio)

        assertSame(first, second)
    }

    @Test fun nextSnapshotReadsChangedCuesAndDropsDeletedOnes() {
        val audio = folder().resolve("album.wav")
        val path = cue("album.cue", "album.wav")
        val cache = CueDiscovery.ParsedCueCache()
        CueDiscovery.findAll(listOf(audio), cache) { true }

        Files.writeString(path, Files.readString(path) + "\nTITLE Changed")
        val updated = CueDiscovery.findAll(listOf(audio), cache) { true }
        assertEquals("Changed", updated.getValue(audio).sheet.tracks.single().title)

        Files.delete(path)
        assertTrue(CueDiscovery.findAll(listOf(audio), cache) { true }.isEmpty())
    }

    @Test fun manualInvalidationRereadsSameSizeCuesWithPreservedTimestamps() {
        val audio = folder().resolve("album.wav")
        val path = cue("album.cue", "album.wav")
        Files.writeString(path, Files.readString(path) + "\nTITLE First")
        Files.setLastModifiedTime(path, FileTime.fromMillis(1_700_000_000_000L))
        val stamp = Files.getLastModifiedTime(path)
        val cache = CueDiscovery.ParsedCueCache()
        CueDiscovery.findAll(listOf(audio), cache) { true }

        Files.writeString(path, Files.readString(path).replace("First", "Other"))
        Files.setLastModifiedTime(path, stamp)
        cache.invalidateFolders(setOf(folder()))
        val updated = CueDiscovery.findAll(listOf(audio), cache) { true }

        assertEquals("Other", updated.getValue(audio).sheet.tracks.single().title)
    }

    @Test fun aSharedAlbumCueMatchesBothDiscFolders() {
        val first = Files.createDirectory(folder().resolve("CD1")).resolve("first.wav")
        val second = Files.createDirectory(folder().resolve("Disc 2")).resolve("second.wav")
        val shared = Files.writeString(folder().resolve("album.cue"), """
            FILE "CD1/first.wav" WAVE
            TRACK 01 AUDIO
              TITLE First
              INDEX 01 00:00:00
            FILE "Disc 2\second.wav" WAVE
            TRACK 02 AUDIO
              TITLE Second
              INDEX 01 00:00:00
        """.trimIndent())

        assertEquals(shared, CueDiscovery.find(first)?.path)
        assertEquals(shared, CueDiscovery.find(second)?.path)
        val matches = CueDiscovery.findAll(listOf(first, second)) { true }
        assertSame(matches.getValue(first), matches.getValue(second))
        assertEquals(listOf(first, second), matches.getValue(first).sheet.tracks.map { it.audio })
    }

    @Test fun aLocalMatchOrConflictTakesPrecedenceOverTheSharedSheet() {
        val disc = Files.createDirectory(folder().resolve("CD1"))
        val audio = disc.resolve("album.wav")
        val shared = cue("shared.cue", "CD1/album.wav")
        val local = Files.copy(shared, disc.resolve("A.cue"))
        Files.writeString(local, Files.readString(local).replace("CD1/album.wav", "album.wav"))

        assertEquals(local, CueDiscovery.find(audio)?.path)
        assertEquals(local, CueDiscovery.findAll(listOf(audio)) { true }.getValue(audio).path)

        Files.copy(local, disc.resolve("B.cue"))
        assertThrows(IllegalStateException::class.java) { CueDiscovery.find(audio) }
        assertTrue(CueDiscovery.findAll(listOf(audio)) { true }.isEmpty())
    }

    @Test fun sharedMatchingIgnoresOtherAncestorsAndRequiresAnExplicitDiscFolder() {
        val album = Files.createDirectory(folder().resolve("Album"))
        val disc = Files.createDirectory(album.resolve("CD1")).resolve("album.wav")
        val other = Files.createDirectory(album.resolve("Other")).resolve("album.wav")
        cue("grandparent.cue", "Album/CD1/album.wav")
        Files.writeString(album.resolve("unrelated.cue"), """
            FILE "Other/album.wav" WAVE
            TRACK 01 AUDIO
              INDEX 01 00:00:00
        """.trimIndent())

        assertNull(CueDiscovery.find(disc))
        assertNull(CueDiscovery.find(other))
        assertTrue(CueDiscovery.findAll(listOf(disc, other)) { true }.isEmpty())
    }

    @Test fun invalidatingADiscRereadsTheSharedCueWithPreservedTimestamps() {
        val disc = Files.createDirectory(folder().resolve("CD1"))
        val audio = disc.resolve("album.wav")
        val shared = cue("album.cue", "CD1/album.wav")
        Files.writeString(shared, Files.readString(shared) + "\nTITLE First")
        Files.setLastModifiedTime(shared, FileTime.fromMillis(1_700_000_000_000L))
        val stamp = Files.getLastModifiedTime(shared)
        val cache = CueDiscovery.ParsedCueCache()
        val initial = CueDiscovery.findAll(listOf(audio), cache) { true }

        Files.writeString(shared, Files.readString(shared).replace("First", "Other"))
        Files.setLastModifiedTime(shared, stamp)
        cache.invalidateFolders(setOf(disc))
        val updated = CueDiscovery.findAll(listOf(audio), cache) { true }
        assertEquals("Other", updated.getValue(audio).sheet.tracks.single().title)
        assertEquals("Other", CueDiscovery.refreshMatches(initial, setOf(audio)) { true }
            .getValue(audio).sheet.tracks.single().title)

        Files.delete(shared)
        assertTrue(CueDiscovery.refreshMatches(updated, setOf(audio)) { true }.isEmpty())
    }

    private fun folder(): Path = temp.root.toPath()

    private fun cue(name: String, audioName: String, charset: Charset = Charsets.UTF_8): Path =
        Files.writeString(folder().resolve(name), """
            FILE "$audioName" WAVE
            TRACK 01 AUDIO
              INDEX 01 00:00:00
        """.trimIndent(), charset)
}
