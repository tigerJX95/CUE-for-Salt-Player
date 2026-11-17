package spw.cue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CueRefreshRecoveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun refreshReplacesOnlyTheRequestedCueAndRemovesMissingMatches() {
        val first = temp.newFile("disc1.wav").toPath()
        val second = temp.newFile("disc2.wav").toPath()
        val firstCue = temp.newFile("disc1.cue")
        val secondCue = temp.newFile("disc2.cue")
        firstCue.writeText(sheet("disc1.wav", "Old"))
        secondCue.writeText(sheet("disc2.wav", "Second"))
        val cached = CueDiscovery.findAll(listOf(first, second)) { true }

        firstCue.writeText(sheet("disc1.wav", "Updated"))
        val updated = CueDiscovery.refreshMatches(cached, setOf(first)) { true }
        assertEquals("Updated", updated[first]?.sheet?.tracks?.single()?.title)
        assertEquals(cached[second], updated[second])

        firstCue.delete()
        val removed = CueDiscovery.refreshMatches(updated, setOf(first)) { true }
        assertFalse(first in removed)
        assertEquals(cached[second], removed[second])
    }

    @Test fun restoringASelectedTrackPersistsAndPreservesTheOtherRecords() {
        val path = temp.root.toPath().resolve("hidden-cue-tracks.txt")
        val selected = "a".repeat(64)
        val hidden = "b".repeat(64)
        val removed = "c".repeat(64)
        val visibility = SongVisibility(path)
        visibility.hide(setOf(selected, hidden))
        visibility.remove(setOf(selected, removed))
        assertEquals("已隐藏 · 已移除", visibility.recovery.value.label(selected))

        visibility.restore(setOf(selected))

        val reopened = SongVisibility(path)
        assertEquals(SongVisibilityRecords(setOf(hidden), setOf(removed)), reopened.recovery.value)
        reopened.restoreAll()
        assertEquals(SongVisibilityRecords(), SongVisibility(path).recovery.value)
    }

    @Test fun refreshRevisionIsSharedByAllDiscsInTheRequest() {
        val refresh = AlbumCueRefresh()
        refresh.request(listOf("disc1", "disc2"))
        assertEquals(1L, refresh.revision(listOf("disc1")))
        assertEquals(1L, refresh.revision(listOf("disc2")))
        refresh.request(listOf("disc2"))
        assertEquals(2L, refresh.revision(listOf("disc1", "disc2")))
        assertEquals(0L, refresh.revision(listOf("another-album")))
    }

    private fun sheet(audio: String, title: String) = """
        FILE "$audio" WAVE
        TRACK 01 AUDIO
        TITLE "$title"
        INDEX 01 00:00:00
    """.trimIndent()
}
