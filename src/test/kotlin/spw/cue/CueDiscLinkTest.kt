package spw.cue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class CueDiscLinkTest {
    @Test
    fun confirmedSegmentsPublishStableFramesAndReplacementOwnsCleanup() {
        val audio = Path.of("专辑", "第1碟.wav")
        val rawPath = audio.toString()
        val tracks = listOf(
            CueTrack(1, "First", null, audio, 0, 750, null, null),
            CueTrack(2, "Second", null, audio, 750, 1500, null, null),
            CueTrack(3, "Third", null, audio, 1500, null, null, null)
        )
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawPath.toByteArray()))
        fun value() = System.getProperty(CueDiscLink.KEY)
        CueDiscLink().use { first ->
            fun at(position: Long) = first.publish(rawPath, cueTrackAt(tracks, position, 30000)?.startFrame)
            at(0)
            val initial = value()
            assertEquals("$digest:0", initial.substringBeforeLast(':'))
            assertFalse(initial.contains(rawPath))
            at(3000)
            assertEquals(initial, value()) // Seek inside the same track.
            at(10000)
            assertEquals("$digest:750", value().substringBeforeLast(':')) // Natural boundary.
            at(20000)
            assertEquals("$digest:1500", value().substringBeforeLast(':')) // Direct selection.
            at(10000)
            assertEquals("$digest:750", value().substringBeforeLast(':')) // Previous.
            at(20000)
            val third = value()
            assertEquals("$digest:1500", third.substringBeforeLast(':')) // Next.
            CueDiscLink().use { replacement ->
                replacement.publish(Path.of("专辑", "第2碟.wav").toString(), 0)
                val nextDisc = value()
                assertNotEquals(third, nextDisc)
                first.close()
                assertEquals(nextDisc, value()) // Old plugin instance cannot clear the new one.
            }
            assertNull(value())
            first.publish(rawPath, 0)
            assertNull(value())
        }
    }
}
