package spw.cue

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

internal data class CueSheet(
    val encoding: String,
    val title: String?,
    val tracks: List<CueTrack>
)

internal data class CueTrack(
    val number: Int,
    val title: String,
    val performer: String?,
    val audio: Path,
    val startFrame: Long,
    val nextStartFrame: Long?,
    val index00Frame: Long?,
    val pregapFrame: Long?,
    val startMs: Long = startFrame * 1_000 / 75,
    val endMs: Long? = nextStartFrame?.let { it * 1_000 / 75 }
)

internal fun String?.artistName(): String? {
    val name = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val credit = name.trimEnd(':', '：').trimEnd()
    return name.takeUnless {
        credit == "演奏" || credit == "未知艺术家" || credit == "未知藝術家" ||
            credit.equals("Unknown Artist", ignoreCase = true)
    }
}

internal object CueParser {
    private const val MAX_BYTES = 1024 * 1024
    private val filePattern = Regex("""(?:"([^"]+)"|(.+?))\s+[A-Z0-9_]+""", RegexOption.IGNORE_CASE)
    private val trackPattern = Regex("""([0-9]{2})\s+([A-Z0-9_/]+)""", RegexOption.IGNORE_CASE)
    private val indexPattern = Regex("""([0-9]{2})\s+(.+)""")
    private val timestamp = Regex("""([0-9]{2,6}):([0-9]{2}):([0-9]{2})""")

    fun read(cue: Path): CueSheet {
        require(Files.isRegularFile(cue)) { "请选择普通 CUE 文件" }
        val bytes = Files.newInputStream(cue).use { it.readNBytes(MAX_BYTES + 1) }
        require(bytes.size <= MAX_BYTES) { "CUE 文件超过 1 MiB" }
        val (encoding, source) = decode(bytes)
        require('\u0000' !in source) { "文件包含 NUL，请检查 CUE 编码" }
        return parse(cue, encoding, source)
    }

    private fun decode(bytes: ByteArray): Pair<String, String> {
        fun starts(vararg prefix: Int) =
            bytes.size >= prefix.size && prefix.indices.all { bytes[it].toInt() and 255 == prefix[it] }
        fun readAs(charset: Charset, offset: Int) =
            charset.newDecoder().decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()

        try {
            return when {
                starts(0xEF, 0xBB, 0xBF) -> "UTF-8 BOM" to readAs(Charsets.UTF_8, 3)
                starts(0xFF, 0xFE) -> "UTF-16LE" to readAs(Charsets.UTF_16LE, 2)
                starts(0xFE, 0xFF) -> "UTF-16BE" to readAs(Charsets.UTF_16BE, 2)
                else -> try {
                    "UTF-8" to readAs(Charsets.UTF_8, 0)
                } catch (_: CharacterCodingException) {
                    "GBK" to readAs(Charset.forName("GBK"), 0)
                }
            }
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("无法解码 CUE。请使用 UTF-8、GBK 或带 BOM 的 UTF-16")
        }
    }

    fun readEmbedded(audio: Path, bytes: ByteArray): CueSheet {
        require(bytes.size <= MAX_BYTES) { "内嵌 CUE 超过 1 MiB" }
        val source = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require('\u0000' !in source) { "内嵌 CUE 包含 NUL" }
        return parse(audio, "FLAC 内嵌 CUE · UTF-8", source, audio.toAbsolutePath().normalize())
    }

    private fun parse(cue: Path, encoding: String, source: String, embeddedAudio: Path? = null): CueSheet {
        val folder = cue.toAbsolutePath().normalize().parent
        var album: String? = null
        var albumPerformer: String? = null
        var audio = embeddedAudio
        var fileDeclared = false
        var current: Draft? = null
        val drafts = mutableListOf<Draft>()
        source.lineSequence().forEachIndexed { lineNumber, raw ->
            val line = raw.trim()
            val split = line.indexOfFirst(Char::isWhitespace).let { if (it < 0) line.length else it }
            val command = line.substring(0, split).uppercase()
            val value = line.substring(split).trim()
            try {
                when (command) {
                    "FILE" -> {
                        val match = requireNotNull(filePattern.matchEntire(value)) { "FILE 格式错误" }
                        val path = match.groupValues[1].ifEmpty { match.groupValues[2] }
                        if (embeddedAudio == null) {
                            audio = resolveAudio(folder, path)
                        } else {
                            require(!fileDeclared && drafts.isEmpty()) { "内嵌 CUE 只能引用当前音频" }
                        }
                        fileDeclared = true
                        current = null
                    }
                    "TRACK" -> {
                        val match = requireNotNull(trackPattern.matchEntire(value)) { "TRACK 格式错误" }
                        val number = match.groupValues[1].toInt()
                        require(number in 1..99 && number > (drafts.lastOrNull()?.number ?: 0)) {
                            "TRACK 编号须在 01–99 内严格递增"
                        }
                        current = Draft(number, requireNotNull(audio) { "TRACK 缺少 FILE" },
                            match.groupValues[2].equals("AUDIO", ignoreCase = true)).also(drafts::add)
                    }
                    "TITLE", "PERFORMER" -> {
                        val text = value.removeSurrounding("\"")
                        val track = current
                        if (track != null) {
                            if (command == "TITLE") track.title = text else track.performer = text
                        } else if (drafts.isEmpty()) {
                            if (command == "TITLE") album = text else albumPerformer = text
                        }
                    }
                    "INDEX" -> {
                        val track = requireNotNull(current) { "INDEX 缺少 TRACK" }
                        val match = requireNotNull(indexPattern.matchEntire(value)) { "INDEX 格式错误" }
                        val number = match.groupValues[1].toInt()
                        val frame = frames(match.groupValues[2])
                        val previous = track.indexes.entries.lastOrNull()
                        require(previous == null || (number > previous.key && frame >= previous.value)) {
                            "INDEX 编号或时间顺序错误"
                        }
                        track.indexes[number] = frame
                    }
                    "PREGAP" -> {
                        val track = requireNotNull(current) { "PREGAP 缺少 TRACK" }
                        require(track.pregap == null) { "重复的 PREGAP" }
                        track.pregap = frames(value)
                    }
                }
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("第 ${lineNumber + 1} 行：${error.message}", error)
            }
        }
        require(drafts.any { it.isAudio }) { "CUE 未包含音频曲目" }
        val ends = mutableMapOf<Int, Long>()
        for (sameFile in drafts.groupBy { it.audio }.values) {
            for ((previous, next) in sameFile.zipWithNext()) {
                val start = previous.start()
                val end = next.start()
                require(end > start && (next.indexes[0] ?: end) >= start) {
                    "同一音频的 TRACK ${previous.number} 和 ${next.number} 时间重叠或顺序错误"
                }
                ends[previous.number] = end
            }
        }
        return CueSheet(encoding, album, drafts.filter { it.isAudio }.map { track ->
            CueTrack(track.number, track.title?.takeIf { it.isNotBlank() } ?: "TRACK %02d".format(track.number),
                track.performer.artistName() ?: albumPerformer.artistName(),
                track.audio, track.start(), ends[track.number], track.indexes[0], track.pregap)
        })
    }

    private fun resolveAudio(folder: Path, value: String): Path {
        val portable = value.replace('\\', '/')
        require(!portable.startsWith('/') && ':' !in portable) { "FILE 须为 CUE 目录内的相对路径" }
        val path = folder.resolve(portable).normalize()
        require(path != folder && path.startsWith(folder)) { "FILE 指向 CUE 目录之外" }
        return path
    }

    private fun frames(value: String): Long {
        val match = requireNotNull(timestamp.matchEntire(value)) { "时间须为 MM:SS:FF" }
        val (_, minutes, seconds, frames) = match.groupValues
        require(seconds.toInt() < 60 && frames.toInt() < 75) { "秒须小于 60，帧须小于 75" }
        return (minutes.toLong() * 60 + seconds.toInt()) * 75 + frames.toInt()
    }

    private class Draft(val number: Int, val audio: Path, val isAudio: Boolean) {
        var title: String? = null
        var performer: String? = null
        var pregap: Long? = null
        val indexes = linkedMapOf<Int, Long>()

        fun start(): Long = requireNotNull(indexes[1]) { "TRACK %02d 缺少 INDEX 01".format(number) }
    }
}
