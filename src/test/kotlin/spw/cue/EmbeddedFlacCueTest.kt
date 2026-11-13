package spw.cue

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmbeddedFlacCueTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun readsUtf8TextFromTheContainingFileAndPrefersItToBinary() {
        val audio = folder.root.toPath().resolve("重命名.FLAC")
        Files.write(audio, flac(5 to binaryCue(), 4 to comments(
            "cuesheet=\uFEFFPERFORMER \"合唱团\"\r\n" + textCue("旧文件.flac")
        )))
        val sheet = CueDiscovery.find(audio)!!.sheet
        assertEquals(listOf("序曲", "第二轨"), sheet.tracks.map { it.title })
        assertEquals(listOf("合唱团", "独奏者"), sheet.tracks.map { it.performer })
        assertEquals(listOf(0L, 1000L), sheet.tracks.map { it.startMs })
        assertEquals(listOf(audio, audio), sheet.tracks.map { it.audio })
        assertEquals(sheet, CueDiscovery.findAll(listOf(audio)) { true }.getValue(audio).sheet)
    }

    @Test fun invalidMultifileTextFallsBackToBinaryWithSampleTiming() {
        val audio = folder.root.toPath().resolve("album.flac")
        Files.write(audio, flac(
            4 to comments("CUESHEET=${textCue("first.flac")}\nFILE \"second.flac\" WAVE"),
            5 to binaryCue()
        ))
        val tracks = CueDiscovery.find(audio)!!.sheet.tracks
        assertEquals(listOf("TRACK 01", "TRACK 02"), tracks.map { it.title })
        assertEquals(listOf(123L, 1123L), tracks.map { it.startMs })
        assertEquals(listOf(1123L, 3000L), tracks.map { it.endMs })
        assertEquals(0L, tracks.first().index00Frame)
    }

    @Test fun externalCueWinsAndMetadataOnlyTextCanOmitFile() {
        val audio = folder.root.toPath().resolve("album.flac")
        Files.write(audio, flac(4 to comments("CUESHEET=${textCue().substringAfter('\n')}")))
        assertEquals("序曲", CueDiscovery.find(audio)!!.sheet.tracks.first().title)
        val external = folder.root.toPath().resolve("album.cue")
        Files.writeString(external, textCue().replace("序曲", "外置标题"))
        assertEquals(external, CueDiscovery.find(audio)!!.path)
        assertEquals("外置标题", CueDiscovery.findAll(listOf(audio)) { true }.getValue(audio).sheet.tracks.first().title)
    }

    @Test fun truncatedBlocksAreIgnoredAndMalformedBinaryDoesNotHideText() {
        val audio = folder.root.toPath().resolve("album.flac")
        val data = flac(4 to comments("CUESHEET=${textCue()}"))
        Files.write(audio, data.copyOf(data.size - 1))
        assertNull(CueDiscovery.find(audio))
        Files.write(audio, flac(5 to binaryCue().copyOf(410), 4 to comments("CUESHEET=${textCue()}")))
        assertEquals("序曲", CueDiscovery.find(audio)!!.sheet.tracks.first().title)
    }

    private fun textCue(file: String = "album.flac") = """
        FILE "$file" WAVE
          TRACK 01 AUDIO
            TITLE "序曲"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "第二轨"
            PERFORMER "独奏者"
            INDEX 01 00:01:00
    """.trimIndent()

    private fun flac(vararg metadata: Pair<Int, ByteArray>): ByteArray = bytes {
        writeBytes("fLaC")
        writeInt(34)
        write(ByteBuffer.allocate(34).putLong(10, (48_000L shl 44) or 144_000L).array())
        metadata.forEachIndexed { index, (type, data) ->
            val flag = if (index == metadata.lastIndex) Int.MIN_VALUE else 0
            writeInt(flag or (type shl 24) or data.size)
            write(data)
        }
    }

    private fun comments(vararg entries: String): ByteArray = bytes {
        writeInt(0)
        writeInt(Integer.reverseBytes(entries.size))
        for (entry in entries) {
            val data = entry.toByteArray(Charsets.UTF_8)
            write(ByteBuffer.allocate(4).order(LITTLE_ENDIAN).putInt(data.size).array())
            write(data)
        }
    }

    private fun binaryCue(): ByteArray = bytes {
        write(ByteArray(395))
        writeByte(3)
        track(1, 0, 0, 5904)
        track(2, 53904, 0)
        track(255, 144000)
    }

    private fun DataOutputStream.track(number: Int, offset: Long, vararg indexes: Long) {
        writeLong(offset)
        writeByte(number)
        write(ByteArray(26))
        writeByte(indexes.size)
        for ((index, relative) in indexes.withIndex()) {
            writeLong(relative)
            writeByte(index + if (indexes.size == 1) 1 else 0)
            write(ByteArray(3))
        }
    }

    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { output -> DataOutputStream(output).use(write) }.toByteArray()
}
