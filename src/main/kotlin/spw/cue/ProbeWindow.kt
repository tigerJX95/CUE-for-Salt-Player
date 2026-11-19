package spw.cue

import androidx.compose.runtime.Composable
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
    private val visibility: () -> StateFlow<SongVisibilityRecords>?,
    private val restoreHiddenSongs: (Set<String>?) -> Unit,
    private val refreshAlbum: (List<String>) -> Unit,
    private val currentTrack: @Composable () -> CueTrack?,
    private val canReadLibrary: () -> Boolean,
    private val active: () -> Boolean,
    private val refreshVisibility: () -> Unit = {}
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
    private var albumIds by mutableStateOf<List<String>?>(null)
    private var firstOpen = true

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        minimumSize = Dimension(650, 560)
        val panel = ComposePanel().apply {
            preferredSize = Dimension(800, 700)
            setContent {
                LaunchedEffect(showingDiagnostics && apiReport != null) {
                    while (showingDiagnostics && apiReport != null && active()) {
                        apiReport = nativeReport()
                        delay(350)
                    }
                }
                val playing = currentTrack()
                ProbeWindowContent(
                    playingTrack = playing,
                    version = version,
                    source = result?.let { data ->
                        cuePath?.fileName?.toString()
                            ?: "当前专辑 · ${data.matches.size} 个音频文件"
                    },
                    result = result,
                    selectedRow = selectedRow,
                    status = status,
                    busy = worker != null,
                    canSeek = canSeekSelected(playing),
                    diagnostics = showingDiagnostics,
                    report = apiReport ?: listOf(report, seekReport).filter(String::isNotBlank).joinToString("\n\n"),
                    showingApi = apiReport != null,
                    managingAlbum = albumIds != null,
                    onDiscover = if (albumIds == null) ::discoverCurrent else ::refreshManagedAlbum,
                    onOpen = ::chooseFile,
                    onSelect = { selectedRow = it },
                    onPlay = { index ->
                        selectedRow = index
                        seekSelected(playing)
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
                    visibility = visibility()?.collectAsState()?.value ?: SongVisibilityRecords(),
                    onRestoreHiddenSongs = restoreHiddenSongs,
                    onSeek = { seekSelected(playing) }
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
        albumIds = null
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

    fun openAlbum(ids: List<String>) {
        if (!active() || ids.isEmpty()) return
        firstOpen = false
        cancelWork()
        albumIds = ids
        showingDiagnostics = false
        apiReport = null
        discoverAlbum(ids)
    }

    private fun refreshManagedAlbum() {
        val ids = albumIds ?: return
        if (worker != null || !active() || !canReadLibrary()) return
        refreshAlbum(ids)
        discoverAlbum(ids)
    }

    private fun discoverAlbum(ids: List<String>) {
        if (!canReadLibrary()) {
            clearResult("读取专辑 CUE 需要曲库读取权限。")
            return
        }
        cuePath = null
        clearResult("正在读取当前专辑的 CUE…")
        runTask({ LibraryProbe.inspectAlbum(ids, active) }) { inspected ->
            if (inspected.sheet.tracks.isEmpty()) status = "当前专辑没有匹配的 CUE 分轨。"
            else showResult(null, inspected, "已读取专辑")
        }
    }

    private fun discoverCurrent() {
        if (worker != null || !active()) return
        if (!canReadLibrary()) {
            status = "查找当前音频需要曲库读取权限，可手动打开 CUE 预览。"
            return
        }
        clearResult("正在查找当前专辑的外置或内嵌 CUE…")
        runTask({
            val current = LibraryProbe.current()
            val audio = LibraryProbe.pathOf(current)
            audio to if (audio != null && current != null) LibraryProbe.inspectPlayingAlbum(current, audio, active) else null
        }) { (audio, cue) ->
            when {
                audio == null -> status = "请先在椒盐播放本地整轨，或手动打开 CUE。"
                cue == null -> {
                    report = "当前播放  $audio"
                    status = "未找到匹配的外置或内嵌 CUE，可手动打开外置 CUE 排查。"
                }
                else -> showResult(cue.file, cue.result, "已找到")
            }
        }
    }

    private fun nativeReport(): String =
        songReport() + "\n\n${visibilityReport()}\n\n原生专辑页\n${albumStatus()}\n\n" + hostApiReport

    private fun visibilityReport(): String {
        val records = visibility()?.value ?: return "恢复记录：尚未加载"
        val keys = result?.sheet?.tracks.orEmpty().mapTo(mutableSetOf(), SongVisibility::key)
        return "恢复记录（当前列表多选／累加同步）\n" +
            "已隐藏 ${records.hiddenInSongs.size} · 当前 CUE 匹配 ${keys.count { it in records.hiddenInSongs }}\n" +
            "已移除 ${records.removedFromLibrary.size} · 当前 CUE 匹配 ${keys.count { it in records.removedFromLibrary }}"
    }

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
        albumIds = null
        showingDiagnostics = false
        apiReport = null
        clearResult("正在解析 CUE…")
        runTask({ LibraryProbe.inspect(file, readLibrary = canReadLibrary()) }) { inspected ->
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

    private fun showResult(file: Path?, data: ProbeResult, action: String) {
        cuePath = file
        result = data
        report = buildString {
            appendLine("CUE  ${file?.fileName ?: "当前专辑"}  ·  ${data.sheet.encoding}  ·  ${data.sheet.title.orEmpty()}")
            appendLine("当前播放  ${data.current?.path ?: "无"}")
            if (canReadLibrary()) {
                appendLine("曲库匹配  ${data.matches.size} / ${data.sheet.tracks.map { it.audio }.distinct().size} 个音频文件")
            } else {
                appendLine("未授予曲库读取权限 · 仅显示 CUE 内容")
            }
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
            !canReadLibrary() -> " · 曲库匹配与定位需要曲库读取权限"
            data.current == null -> " · 当前无播放项"
            selectedRow < 0 -> if (albumIds != null) " · 可管理专辑分轨" else " · 当前播放项无可定位分轨"
            else -> " · 双击可定位到当前整轨中的分轨"
        }
    }

    private fun canSeekSelected(playing: CueTrack? = null): Boolean {
        val data = result ?: return false
        val track = data.sheet.tracks.getOrNull(selectedRow) ?: return false
        val source = data.matches[track.audio] ?: return false
        val samePlayingFile = if (playing == null) LibraryProbe.canSeek(track, data.current)
            else playing.audio == track.audio && track.startMs in 0 until source.duration
        return canReadLibrary() && worker == null && samePlayingFile
    }

    private fun seekSelected(playing: CueTrack? = null) {
        if (!canSeekSelected(playing)) return
        val inspected = checkNotNull(result)
        val track = inspected.sheet.tracks[selectedRow]
        seekTimer?.stop()
        seekReport = ""
        status = "正在重新确认当前播放项…"
        runTask({ LibraryProbe.seek(track, LibraryProbe.current()?.id, active) }) { requestedAt ->
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
            override fun doInBackground(): T {
                refreshVisibility()
                return work()
            }
            override fun done() {
                if (worker !== this) return
                worker = null
                if (isDisplayable && active() && !isCancelled) {
                    try {
                        completed(get())
                    } catch (error: ExecutionException) {
                        val cause = error.cause ?: error
                        status = if (cause.isLibraryPermissionDenied()) "未授予曲库读取权限，可手动打开 CUE 预览。"
                        else "操作失败：${cause.message ?: cause.javaClass.simpleName}"
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
