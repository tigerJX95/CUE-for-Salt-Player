package spw.cue

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

class IssueTemplateContractTest {
    @Test fun latestReleaseLivesInReadmesAndHistoryContainsOnlyOlderVersions() {
        val version = checkNotNull(System.getProperty("cue.releasedVersion"))
        val versionNumber = """(v\d+\.\d+\.\d+)"""
        val packagePath = Regex("""`build/libs/CUE_for_Salt_Player_${versionNumber}\.spmod`""")
        val readmes = mapOf(
            "README.md" to listOf(
                Regex("""(?m)^\*\*当前版本 $versionNumber · .+\*\*$"""),
                Regex("""(?m)^\| 平台 \| 渠道 \| 椒盐版本 \| $versionNumber 验证结果 \|$""")
            ),
            "README_EN.md" to listOf(
                Regex("""(?m)^\*\*Current version $versionNumber · .+\*\*$"""),
                Regex("""(?m)^\| Platform \| Distribution channel \| Salt Player version \| $versionNumber verification \|$""")
            )
        )
        fun checkVersion(path: Path, patterns: List<Regex>) {
            val contents = Files.readString(path)
            for (pattern in patterns) {
                val versions = pattern.findAll(contents).map { it.groupValues[1] }.toList()
                assertEquals("$path version mismatch for $pattern", listOf("v$version"), versions)
            }
        }
        for ((name, patterns) in readmes) {
            checkVersion(Path.of("docs", name), patterns + packagePath)
        }
        checkVersion(Path.of(".github/ISSUE_TEMPLATE/问题反馈-bug-report.yml"),
            listOf(Regex("""(?m)^\s+placeholder: 例如 / e\.g\. $versionNumber$""")))
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
