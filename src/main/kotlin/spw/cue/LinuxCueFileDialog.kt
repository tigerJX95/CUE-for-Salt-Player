package spw.cue

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal object LinuxCueFileDialog {
    fun choose(folder: Path?): Result<Path?> = try {
        Result.success(open(folder))
    } catch (error: IOException) {
        Result.failure(error)
    } catch (error: InvalidPathException) {
        Result.failure(error)
    }

    private fun open(folder: Path?): Path? {
        val command = mutableListOf(
            "zenity", "--file-selection", "--title=打开 CUE 文件",
            "--file-filter=CUE 文件 (*.cue) | *.cue *.cuE *.cUe *.cUE *.Cue *.CuE *.CUe *.CUE"
        )
        folder?.toAbsolutePath()?.let { command.add("--filename=$it/") }
        val output = Files.createTempFile("cue-file-dialog-", ".txt")
        try {
            val process = ProcessBuilder(command).redirectOutput(output.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val exitCode = try {
                process.waitFor()
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            return when (exitCode) {
                0 -> {
                    if (Files.size(output) > 8192) throw IOException("文件选择器返回的路径过长")
                    val selected = Files.readString(output).removeSuffix("\n")
                    selected.takeIf { it.isNotEmpty() }?.let(Path::of)
                }
                1 -> null
                else -> throw IOException("桌面文件选择器退出（$exitCode）")
            }
        } finally {
            Files.deleteIfExists(output)
        }
    }
}
