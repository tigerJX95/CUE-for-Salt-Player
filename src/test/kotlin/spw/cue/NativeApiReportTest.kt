package spw.cue

import org.junit.Assert.assertTrue
import org.junit.Test

class NativeApiReportTest {
    @Test fun listsTheLoadedWorkshopContractsAndPermissionMarker() {
        val report = NativeApiReport.describe("test / MS")
        assertTrue(report.contains("test / MS"))
        assertTrue(report.contains("LIBRARY_WRITE 权限标识  存在"))
        assertTrue(report.contains("getLibrary() → Library"))
        assertTrue(report.contains("getTrackById(String) → CompletionStage"))
        assertTrue(report.contains("seekTo(long) → void"))
    }
}
