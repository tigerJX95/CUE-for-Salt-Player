package spw.cue

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun folder(): Path = temp.root.toPath()

    private fun cue(name: String, audioName: String, charset: Charset = Charsets.UTF_8): Path =
        Files.writeString(folder().resolve(name), """
            FILE "$audioName" WAVE
            TRACK 01 AUDIO
              INDEX 01 00:00:00
        """.trimIndent(), charset)
}
