package spw.cue

import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CueParserTest {
    @get:Rule val temp = TemporaryFolder()

    private val single = """
        PERFORMER "测试艺术家"
        TITLE "测试专辑"
        FILE "整轨.flac" WAVE
          TRACK 01 AUDIO
            TITLE "曲目一"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "曲目二"
            PERFORMER "客席"
            PREGAP 00:01:00
            INDEX 00 00:07:37
            INDEX 01 00:08:01
    """.trimIndent()

    @Test fun readsChineseAndPreservesFrameBoundaries() {
        val sheet = parse(single)
        val (first, second) = sheet.tracks
        assertEquals("测试专辑", sheet.title)
        assertEquals("测试艺术家", first.performer)
        assertEquals("客席", second.performer)
        assertEquals("整轨.flac", first.audio.fileName.toString())
        assertEquals(8_013L, first.endMs)
        assertEquals(first.endMs, second.startMs)
        assertEquals(562L, second.index00Frame)
        assertEquals(75L, second.pregapFrame)
        assertNull(second.endMs)
    }

    @Test fun treatsAnInstrumentalRoleAsMissingPerformer() {
        val roleOnly = single.replace("PERFORMER \"客席\"", "PERFORMER \"演奏：\"")
        assertEquals("测试艺术家", parse(roleOnly).tracks[1].performer)
        val noArtist = roleOnly.replace("PERFORMER \"测试艺术家\"", "PERFORMER \"演奏\"")
        assertNull(parse(noArtist).tracks[1].performer)
        assertEquals("客席", parse(single).tracks[1].performer)
    }

    @Test fun playbackTrackChangesAtCueBoundaryAndWhenSeekingBackward() {
        val tracks = parse(single).tracks
        assertEquals(tracks[0], cueTrackAt(tracks, 8_012, 24_000))
        assertEquals(tracks[1], cueTrackAt(tracks, 8_013, 24_000))
        assertEquals(tracks[1], cueTrackAt(tracks, 24_000, 24_000))
        assertEquals(tracks[0], cueTrackAt(tracks, 0, 24_000))
        assertNull(cueTrackAt(tracks, 0, 0))
        assertNull(cueTrackAt(tracks, 24_001, 24_000))
    }

    @Test fun playbackTrackExcludesAudioOutsideCueIndexes() {
        val tracks = parse("""
            FILE "album.wav" WAVE
            TRACK 01 AUDIO
              INDEX 01 00:01:00
            TRACK 02 MODE1/2352
              INDEX 01 00:02:00
            TRACK 03 AUDIO
              INDEX 01 00:03:00
        """.trimIndent()).tracks
        assertNull(cueTrackAt(tracks, 999, 4_000))
        assertEquals(1, cueTrackAt(tracks, 1_000, 4_000)?.number)
        assertNull(cueTrackAt(tracks, 2_000, 4_000))
        assertEquals(3, cueTrackAt(tracks, 3_000, 4_000)?.number)
    }

    @Test fun playbackNavigationUsesAdjacentAudioTracks() {
        val tracks = parse("""
            FILE "album.wav" WAVE
            TRACK 01 AUDIO
              INDEX 01 00:00:00
            TRACK 02 AUDIO
              INDEX 01 00:01:00
            TRACK 03 AUDIO
              INDEX 01 00:02:00
            TRACK 04 AUDIO
              TITLE "市集"
              INDEX 01 00:03:00
        """.trimIndent()).tracks
        val (first, second, third, fourth) = tracks
        assertEquals(fourth, adjacentCueTrack(tracks, third, 4_000, 1))
        assertEquals(second, adjacentCueTrack(tracks, third, 4_000, -1))
        assertEquals(second, adjacentCueTrack(tracks, first, 4_000, 1))
        assertNull(adjacentCueTrack(tracks, first, 4_000, -1))
        assertNull(adjacentCueTrack(tracks, fourth, 4_000, 1))
        assertNull(adjacentCueTrack(tracks, third, 3_000, 1))
        assertNull(adjacentCueTrack(tracks, third.copy(number = 8), 4_000, 1))
    }

    @Test fun detectsSupportedEncodings() {
        val cases = listOf(
            Triple("UTF-8", "UTF-8", byteArrayOf()),
            Triple("UTF-8 BOM", "UTF-8", byteArrayOf(-17, -69, -65)),
            Triple("GBK", "GBK", byteArrayOf()),
            Triple("UTF-16LE", "UTF-16LE", byteArrayOf(-1, -2)),
            Triple("UTF-16BE", "UTF-16BE", byteArrayOf(-2, -1))
        )
        for ((label, charset, bom) in cases) {
            val sheet = parse(bom + single.toByteArray(Charset.forName(charset)))
            assertEquals(label, sheet.encoding)
            assertEquals("曲目二", sheet.tracks.last().title)
        }
    }

    @Test fun restartsTimeAtEachFileAndSupportsRelativeSubdirectories() {
        val sheet = parse("""
            FILE "disc one.flac" WAVE
            TRACK 01 AUDIO
              INDEX 01 00:01:00
            FILE Disc 2\disc two.flac WAVE
            TRACK 02 AUDIO
              INDEX 01 00:00:00
        """.trimIndent())
        assertNull(sheet.tracks[0].endMs)
        assertEquals(0L, sheet.tracks[1].startMs)
        assertEquals(temp.root.toPath().resolve("Disc 2").resolve("disc two.flac"), sheet.tracks[1].audio)
    }

    @Test fun usesNextDataTrackAsBoundaryButDoesNotListIt() {
        val sheet = parse(single + "\nTRACK 03 MODE1/2352\nINDEX 01 00:30:00")
        assertEquals(listOf(1, 2), sheet.tracks.map { it.number })
        assertEquals(30_000L, sheet.tracks.last().endMs)
    }

    @Test fun rejectsMissingDuplicateAndOutOfRangeIndexes() {
        for (broken in listOf(
            single.replace("INDEX 01 00:08:01", ""),
            single.replace("INDEX 01 00:08:01", "INDEX 01 00:08:75"),
            single.replace("INDEX 01 00:08:01", "INDEX 01 00:60:00"),
            single + "\nINDEX 01 00:08:02"
        )) assertThrows(IllegalArgumentException::class.java) { parse(broken) }
    }

    @Test fun rejectsOverlapAndPathEscape() {
        val overlap = single.replace("INDEX 01 00:00:00", "INDEX 01 00:07:38")
        assertThrows(IllegalArgumentException::class.java) { parse(overlap) }
        for (name in listOf("../逃逸.flac", "/tmp/audio.flac", "C:\\audio.flac", "\\\\host\\audio.flac")) {
            val invalid = assertThrows(IllegalArgumentException::class.java) {
                parse(single.replace("整轨.flac", name))
            }
            assertTrue(invalid.message.orEmpty().contains("FILE"))
        }
    }

    @Test fun rejectsOversizedAndMalformedBomInput() {
        assertThrows(IllegalArgumentException::class.java) { parse(ByteArray(1024 * 1024 + 1)) }
        assertThrows(IllegalArgumentException::class.java) { parse(byteArrayOf(-17, -69, -65, -1)) }
    }

    private fun parse(source: String): CueSheet = parse(source.toByteArray())

    private fun parse(bytes: ByteArray): CueSheet {
        val path = temp.root.toPath().resolve("fixture.cue")
        Files.write(path, bytes)
        return CueParser.read(path)
    }
}
