package spw.cue

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposePanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.awt.Dimension
import java.awt.FileDialog
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.Timer
import javax.swing.WindowConstants
import javax.swing.filechooser.FileNameExtensionFilter

internal class ProbeWindow(
    version: String, private val host: String,
    private val albumStatus: () -> String,
    private val retryAlbumHook: () -> Unit,
    private val songReport: () -> String,
    private val diagnoseSongs: (() -> Unit) -> Unit,
    private val mountSongs: () -> Unit,
    private val hiddenSongs: StateFlow<Set<String>>?,
    private val restoreHiddenSongs: () -> Unit,
    private val active: () -> Boolean
) : JFrame("CUE 分轨 · CUE for Salt Player") {
    private var report by mutableStateOf("")
    private var apiReport by mutableStateOf<String?>(null)
    private val hostApiReport by lazy { NativeApiReport.describe(host) }
    private var showingDiagnostics by mutableStateOf(false)
    private var status by mutableStateOf("请先在椒盐播放本地整轨，或手动打开 CUE。")
    private var result by mutableStateOf<ProbeResult?>(null)
    private var selectedRow by mutableStateOf(-1)
    private var worker by mutableStateOf<SwingWorker<*, Void>?>(null)
    private var seekTimer: Timer? = null
    private var seekReport by mutableStateOf("")
    private var cuePath by mutableStateOf<Path?>(null)
    private var firstOpen = true

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        minimumSize = Dimension(650, 390)
        val panel = ComposePanel().apply {
            preferredSize = Dimension(760, 540)
            setContent {
                LaunchedEffect(showingDiagnostics && apiReport != null) {
                    while (showingDiagnostics && apiReport != null && active()) {
                        apiReport = nativeReport()
                        delay(350)
                    }
                }
                ProbeWindowContent(
                    version = version,
                    source = cuePath?.fileName?.toString()?.takeIf { result != null },
                    result = result,
                    selectedRow = selectedRow,
                    status = status,
                    busy = worker != null,
                    canSeek = canSeekSelected(),
                    diagnostics = showingDiagnostics,
                    report = apiReport ?: listOf(report, seekReport).filter(String::isNotBlank).joinToString("\n\n"),
                    showingApi = apiReport != null,
                    onDiscover = ::discoverCurrent,
                    onOpen = ::chooseFile,
                    onSelect = { selectedRow = it },
                    onPlay = { index ->
                        selectedRow = index
                        seekSelected()
                    },
                    onToggleDiagnostics = {
                        showingDiagnostics = !showingDiagnostics
                        apiReport = null
                    },
                    onShowApi = { apiReport = if (apiReport == null) nativeReport() else null },
                    onRetry = {
                        if (active()) {
                            retryAlbumHook()
                            apiReport = nativeReport()
                        }
                    },
                    onRefreshReport = { apiReport = nativeReport() },
                    onDiagnoseSongs = {
                        diagnoseSongs {
                            SwingUtilities.invokeLater {
                                if (active() && isDisplayable && apiReport != null) apiReport = nativeReport()
                            }
                        }
                        apiReport = nativeReport()
                    },
                    onMountSongs = {
                        mountSongs()
                        apiReport = nativeReport()
                    },
                    hiddenSongCount = hiddenSongs?.collectAsState()?.value?.size ?: 0,
                    onRestoreHiddenSongs = restoreHiddenSongs,
                    onSeek = ::seekSelected
                )
            }
        }
        contentPane.add(panel)
        pack()
        setLocationRelativeTo(null)
    }

    fun loadOnFirstOpen() {
        if (!firstOpen) return
        firstOpen = false
        discoverCurrent()
    }

    fun showAutomatic(cue: FollowedCue) {
        firstOpen = false
        cancelWork()
        clearResult("")
        showResult(cue.file, cue.result, "已识别")
        showingDiagnostics = false
        apiReport = null
    }

    fun clearAutomatic() {
        cancelWork()
        clearResult("当前播放项没有匹配的 CUE")
    }

    private fun discoverCurrent() {
        if (worker != null) return
        clearResult("正在查找当前音频同目录的 CUE…")
        runTask({
            val current = LibraryProbe.current()
            val audio = LibraryProbe.pathOf(current)
            val located = audio?.let(CueDiscovery::find)
            val inspected = located?.let { LibraryProbe.inspect(it.sheet, current) }
            Triple(audio, located?.path, inspected)
        }) { (audio, file, inspected) ->
            when {
                audio == null -> status = "请先在椒盐播放本地整轨，或手动打开 CUE。"
                file == null || inspected == null -> {
                    report = "当前播放  $audio"
                    status = "同目录未找到匹配的 CUE，可手动打开查看具体错误。"
                }
                else -> showResult(file, inspected, "已找到")
            }
        }
    }

    private fun nativeReport(): String =
        songReport() + "\n\n原生专辑页\n${albumStatus()}\n\n" + hostApiReport

    private fun chooseFile() {
        if (worker != null) return
        val folder = cuePath?.parent
        val os = System.getProperty("os.name").orEmpty()
        if (os.startsWith("Linux", ignoreCase = true)) {
            runTask({ LinuxCueFileDialog.choose(folder) }) { choice ->
                openChosenFile(choice.getOrElse { chooseSwingFile(folder) })
            }
            return
        }
        val file = if (os.startsWith("Windows", ignoreCase = true)) {
            val dialog = FileDialog(this, "打开 CUE 文件 (*.cue)", FileDialog.LOAD)
            try {
                dialog.directory = folder?.toString()
                dialog.isVisible = true
                dialog.files.singleOrNull()?.toPath()
            } finally {
                dialog.dispose()
            }
        } else {
            chooseSwingFile(folder)
        }
        openChosenFile(file)
    }

    private fun chooseSwingFile(folder: Path?): Path? {
        val chooser = JFileChooser(folder?.toFile())
        chooser.fileFilter = FileNameExtensionFilter("CUE 文件 (*.cue)", "cue")
        chooser.isAcceptAllFileFilterUsed = false
        return if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
    }

    private fun openChosenFile(file: Path?) {
        if (file == null || !active() || !isDisplayable) return
        if (!file.fileName.toString().endsWith(".cue", ignoreCase = true)) {
            status = "请选择 .cue 文件"
            return
        }
        cancelWork()
        showingDiagnostics = false
        apiReport = null
        clearResult("正在解析 CUE 并匹配曲库…")
        runTask({ LibraryProbe.inspect(file) }) { inspected ->
            showResult(file, inspected, "已打开")
        }
    }

    private fun clearResult(message: String) {
        seekTimer?.stop()
        result = null
        selectedRow = -1
        report = ""
        seekReport = ""
        status = message
    }

    private fun showResult(file: Path, data: ProbeResult, action: String) {
        cuePath = file
        result = data
        report = buildString {
            appendLine("CUE  ${file.fileName}  ·  ${data.sheet.encoding}  ·  ${data.sheet.title.orEmpty()}")
            appendLine("当前播放  ${data.current?.path ?: "无"}")
            appendLine("曲库匹配  ${data.matches.size} / ${data.sheet.tracks.map { it.audio }.distinct().size} 个音频文件")
            appendLine()
            for (track in data.sheet.tracks) {
                val match = data.matches[track.audio]
                val fileDuration = match?.duration?.takeIf { it > 0 }
                val end = track.endMs ?: fileDuration
                appendLine("%02d  %s  /  %s".format(track.number, track.title, track.performer ?: "—"))
                val endLabel = end?.let { "$it ms" } ?: "文件结束"
                appendLine("    ${track.audio.fileName}   ${track.startMs} ms → $endLabel" +
                    if (match == null) "   曲库未匹配" else "   曲库 ID ${match.id}")
                if (fileDuration != null && (track.startMs >= fileDuration || (end ?: 0) > fileDuration)) {
                    appendLine("    时间超出曲库记录的音频时长 ${fileDuration} ms")
                }
            }
        }
        selectedRow = data.sheet.tracks.indexOfFirst { LibraryProbe.canSeek(it, data.current) }
        status = "$action ${data.sheet.tracks.size} 轨" + when {
            data.current == null -> " · 当前无播放项"
            selectedRow < 0 -> " · 当前播放项无可定位分轨"
            else -> " · 双击可定位到当前整轨中的分轨"
        }
    }

    private fun canSeekSelected(): Boolean {
        val data = result ?: return false
        val track = data.sheet.tracks.getOrNull(selectedRow) ?: return false
        return worker == null && track.audio in data.matches && LibraryProbe.canSeek(track, data.current)
    }

    private fun seekSelected() {
        if (!canSeekSelected()) return
        val inspected = checkNotNull(result)
        val track = inspected.sheet.tracks[selectedRow]
        seekTimer?.stop()
        seekReport = ""
        status = "正在重新确认当前播放项…"
        runTask({ LibraryProbe.seek(track, inspected.current?.id, active) }) { requestedAt ->
            status = "已请求定位到 ${track.title}"
            seekTimer = Timer(2_500) {
                fun sample(value: PlaybackProbe.Sample?) =
                    value?.takeIf { it.observedAt >= requestedAt }?.let { "${it.position} ms" } ?: "未收到"
                seekReport = "请求 ${track.startMs} ms  ·  onSeekTo ${sample(PlaybackProbe.lastSeek)}" +
                    "  ·  onPositionUpdated ${sample(PlaybackProbe.lastPosition)}"
            }.apply { isRepeats = false; start() }
        }
    }

    private fun <T> runTask(work: () -> T, completed: (T) -> Unit) {
        worker = object : SwingWorker<T, Void>() {
            override fun doInBackground(): T = work()
            override fun done() {
                if (worker !== this) return
                worker = null
                if (isDisplayable && active() && !isCancelled) {
                    try {
                        completed(get())
                    } catch (error: ExecutionException) {
                        val cause = error.cause ?: error
                        status = "操作失败：${cause.message ?: cause.javaClass.simpleName}"
                    }
                }
            }
        }.also { it.execute() }
    }

    private fun cancelWork() {
        val pending = worker
        worker = null
        pending?.cancel(true)
    }

    override fun dispose() {
        cancelWork()
        seekTimer?.stop()
        super.dispose()
    }
}
