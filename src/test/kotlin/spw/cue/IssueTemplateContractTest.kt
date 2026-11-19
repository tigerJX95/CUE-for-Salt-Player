package spw.cue

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

class IssueTemplateContractTest {
    @Test fun latestReleaseLivesInReadmesAndHistoryContainsOnlyOlderVersions() {
        val build = Files.readString(Path.of("build.gradle"))
        val version = Regex("(?m)^version = '([^']+)'$").find(build)!!.groupValues[1]
        for (name in listOf("README.md", "README_EN.md")) {
            assertTrue("Latest version missing from $name", Files.readString(Path.of("docs", name)).contains("v$version"))
        }
        for (name in listOf("CHANGELOG.md", "CHANGELOG_EN.md")) {
            assertFalse("Latest version must not be duplicated in $name",
                Regex("(?m)^## v${Regex.escape(version)}(?:[ \\t]|$)").containsMatchIn(Files.readString(Path.of("docs", name))))
        }
    }

    @Test fun platformAndPreflightFieldsRemainCheckboxes() {
        val file = Files.readString(Path.of(".github/ISSUE_TEMPLATE/问题反馈-bug-report.yml"))
        val fields = Regex("(?m)^  - type: ").split(file).drop(1)
        for (id in listOf("affected-platform", "checklist")) {
            val field = fields.single { Regex("(?m)^    id: $id$").containsMatchIn(it) }
            assertEquals("$id must support checkboxes", "checkboxes", field.lineSequence().first().trim())
        }
        val platform = fields.single { it.contains("id: affected-platform") }
        assertTrue(platform.contains("Windows · 微软商店 / Windows · Microsoft Store"))
        assertTrue(platform.contains("Windows · Steam"))
        assertTrue(platform.contains("Linux · Steam"))
    }
}
