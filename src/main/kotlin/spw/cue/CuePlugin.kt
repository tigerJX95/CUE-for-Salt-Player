package spw.cue

import androidx.compose.runtime.mutableStateOf
import com.xuncorp.spw.workshop.api.ActionShortcut
import com.xuncorp.spw.workshop.api.Channel
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import org.pf4j.Extension
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Path
import java.util.function.Consumer
import javax.swing.SwingUtilities

class CuePlugin(context: PluginContext) : SpwPlugin(context) {
    @Volatile private var activeSession: Any? = null
    private var shortcut: AutoCloseable? = null
    private var window: ProbeWindow? = null
    private var follow: CueFollow? = null
    private var windowPinned = false
    private var albumHook: AutoCloseable? = null
    private var albumCoverHook: AutoCloseable? = null
    private var gridCoverHook: AutoCloseable? = null
    private var playbackCover: AutoCloseable? = null
    private var songCoverHook: AutoCloseable? = null
    private var songPageHook: AutoCloseable? = null
    private var songPage: NativeSongCue? = null
    private var songVisibility: SongVisibility? = null
    private var songDiagnostics = SongPageDiagnostics()
    private var miniPlayerCue: NativeMiniPlayerCue? = null
    private var taskbarCue: AutoCloseable? = null
    private var configSubscription: AutoCloseable? = null
    private val hideWholeTrack = mutableStateOf(false)
    private val showCueSongs = mutableStateOf(true)
    @Volatile private var albumHookStatus = "尚未注册"
    @Volatile private var albumCoverStatus = "尚未注册"
    @Volatile private var gridCoverStatus = "尚未注册"
    @Volatile private var albumPageStatus = "尚未收到专辑页回调"
    @Volatile private var songCoverStatus = "尚未注册"
    @Volatile private var playbackCoverStatus = "尚未注册"
    @Volatile private var miniPlayerStatus = "尚未注册"
    @Volatile private var taskbarStatus: String? = null

    override fun start() {
        val manager = WorkshopApi.manager
        if (!manager.isPermissionGranted(PluginPermission.KEY_BINDINGS) ||
            !manager.isPermissionGranted(PluginPermission.LIBRARY_READ)
        ) {
            WorkshopApi.ui.toast("CUE 分轨需要快捷键与曲库读取权限", WorkshopApi.Ui.ToastType.Warning)
            return
        }
        val session = Any()
        shortcut = manager.keyBindingManager.register(
            "open-cue-probe", "CUE for Salt Player：打开 CUE 分轨窗口",
            ActionShortcut(KeyEvent.VK_U, ActionShortcut.CONTROL or ActionShortcut.ALT),
            false
        ) {
            SwingUtilities.invokeLater {
                if (activeSession === session) {
                    val current = windowFor(session)
                    windowPinned = true
                    current.isAutoRequestFocus = true
                    current.isVisible = true
                    current.toFront()
                    current.loadOnFirstOpen()
                }
            }
        }
        activeSession = session
        observeConfig(session)
        registerAlbumHook(session, retry = false)
        registerSongs(session)
        registerPlaybackCover(session)
        if (canTryNativeUi() && manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
            miniPlayerStatus = try {
                miniPlayerCue = NativeMiniPlayerCue({ activeSession === session }) { status ->
                    if (activeSession === session) miniPlayerStatus = status
                }
                miniPlayerStatus
            } catch (error: Exception) {
                "注册失败：${error.cause?.message ?: error.message ?: error.javaClass.simpleName}"
            } catch (error: LinkageError) {
                "宿主接口不兼容：${error.javaClass.simpleName}"
            }
            miniPlayerCue?.let { registerTaskbarTitle(session, it) }
        }
        val usePopup = albumHook == null
        SwingUtilities.invokeLater {
            if (activeSession === session && usePopup) {
                val listener = CueFollow { cue ->
                    if (activeSession === session) showFollowedCue(session, cue)
                }
                follow = listener
                PlaybackProbe.mediaChanged = listener::signal
                listener.signal()
            }
        }
    }

    private fun registerTaskbarTitle(session: Any, playback: NativeMiniPlayerCue) {
        taskbarStatus = "等待宿主主窗口"
        try {
            taskbarCue = NativeTaskbarCue(playback, { activeSession === session }) { status ->
                if (activeSession === session) taskbarStatus = status
            }
        } catch (error: Exception) {
            taskbarStatus = "接入失败（${error.message ?: error.javaClass.simpleName}）"
        } catch (error: LinkageError) {
            taskbarStatus = "宿主接口不兼容（${error.javaClass.simpleName}）"
        }
    }

    private fun canTryNativeUi(): Boolean {
        val os = System.getProperty("os.name").orEmpty()
        val windows = os.startsWith("Windows", ignoreCase = true)
        val linux = os.startsWith("Linux", ignoreCase = true)
        return when (pluginContext.spwChannel) {
            Channel.MS -> windows
            Channel.Steam -> windows || linux
        }
    }

    @OptIn(UnstableSpwWorkshopApi::class)
    private fun observeConfig(session: Any) {
        try {
            val manager = WorkshopApi.manager.createConfigManager()
            val config = manager.getConfig("config.json")
            songVisibility = runCatching {
                // Workshop builds expose the same configuration location as either a File or a Path.
                val location = ConfigHelper::class.java.getMethod("getConfigPath").invoke(config)
                val path = when (location) {
                    is Path -> location
                    is File -> location.toPath()
                    else -> error("无法定位插件配置目录")
                }
                SongVisibility(path.toAbsolutePath().resolveSibling("song-visibility.txt"))
            }.getOrNull()
            val listener = Consumer<ConfigHelper> { helper ->
                if (activeSession === session) {
                    try {
                        helper.reload()
                        hideWholeTrack.value = helper.get("hideWholeTrack", false)
                        showCueSongs.value = helper.get("showCueSongs", true)
                    } catch (_: Exception) {
                        hideWholeTrack.value = false
                        showCueSongs.value = false
                    }
                    songPage?.setAutomatic(showCueSongs.value)
                }
            }
            manager.addConfigChangeListener("config.json", listener)
            configSubscription = AutoCloseable { manager.removeConfigChangeListener(listener) }
            listener.accept(config)
        } catch (_: Exception) {
            hideWholeTrack.value = false
            showCueSongs.value = false
        }
    }

    @OptIn(UnstableSpwWorkshopApi::class)
    @Synchronized
    private fun registerAlbumHook(session: Any, retry: Boolean) {
        if (activeSession !== session) return
        if (!canTryNativeUi()) {
            albumHookStatus = "当前宿主未启用原生接入"
            return
        }
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
            albumHookStatus = "未授予方法与 UI Hook 权限"
            return
        }
        albumHookStatus = try {
            if (retry) {
                closeAlbumCoverHook()
                albumHook?.close()
                albumHook = null
            }
            albumPageStatus = "尚未收到专辑页回调"
            val target = AlbumHookTarget.resolve()
            val content = NativeAlbumCue(target, { activeSession === session }, { hideWholeTrack.value }) { status ->
                if (activeSession === session) albumPageStatus = status
            }
            val builder = target.listBuilder
            albumHook = WorkshopApi.hookRegistrar.hookMethod(
                builder.declaringClass.name,
                builder.name,
                builder.parameterTypes.map { it.name },
                50
            ) {
                before { content.beforeList(it) }
                after { content.afterList(it) }
            }
            albumCoverStatus = "未识别到专辑封面入口"
            albumCoverHook = target.cover?.let { cover ->
                try {
                    cover.install(
                        active = { activeSession === session },
                        trackId = { target.singleTrackIn(it)?.id },
                        artwork = { content.artwork },
                        report = { if (activeSession === session) albumCoverStatus = it }
                    ).also { albumCoverStatus = "已注册，等待专辑页绘制" }
                } catch (error: Exception) {
                    albumCoverStatus = "注册失败：${error.message ?: error.javaClass.simpleName}"
                    null
                } catch (error: LinkageError) {
                    albumCoverStatus = "宿主接口不兼容：${error.javaClass.simpleName}"
                    null
                }
            }
            gridCoverStatus = "未识别到专辑卡片封面入口"
            gridCoverHook = target.cover?.let { cover ->
                try {
                    cover.installGrid({ activeSession === session }) { status ->
                        if (activeSession === session) gridCoverStatus = status
                    }.also { gridCoverStatus = "已注册，等待专辑卡片绘制" }
                } catch (error: Exception) {
                    gridCoverStatus = "注册失败：${error.message ?: error.javaClass.simpleName}"
                    null
                } catch (error: LinkageError) {
                    gridCoverStatus = "宿主接口不兼容：${error.javaClass.simpleName}"
                    null
                }
            }
            songCoverStatus = "未识别到歌曲封面入口"
            songCoverHook = target.cover?.let { cover ->
                try {
                    cover.installSongs({ activeSession === session }) { status ->
                        if (activeSession === session) songCoverStatus = status
                    }.also { songCoverStatus = "已注册，等待歌曲封面绘制" }
                } catch (error: Exception) {
                    songCoverStatus = "注册失败：${error.message ?: error.javaClass.simpleName}"
                    null
                } catch (error: LinkageError) {
                    songCoverStatus = "宿主接口不兼容：${error.javaClass.simpleName}"
                    null
                }
            }
            if (retry) "启动后重新注册成功，请退出专辑详情再进入" else "启动时已提交原生专辑页 Hook 注册"
        } catch (error: Exception) {
            "专辑页注册失败：${error.message ?: error.javaClass.simpleName}"
        } catch (error: LinkageError) {
            "宿主界面不兼容：${error.javaClass.simpleName}"
        }
    }

    private fun closeAlbumCoverHook() {
        runCatching { albumCoverHook?.close() }
        albumCoverHook = null
        runCatching { gridCoverHook?.close() }
        gridCoverHook = null
        runCatching { songCoverHook?.close() }
        songCoverHook = null
    }

    private fun registerPlaybackCover(session: Any) {
        playbackCoverStatus = when {
            !canTryNativeUi() ->
                "当前宿主未启用原生接入"
            !WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM) ->
                "未授予方法与 UI Hook 权限"
            else -> {
                playbackCoverStatus = "已订阅播放状态，等待当前音频"
                try {
                    playbackCover = PlaybackCoverFallback({ activeSession === session }) { status ->
                        if (activeSession === session) playbackCoverStatus = status
                    }
                    return
                } catch (error: Exception) {
                    "注册失败：${error.message ?: error.javaClass.simpleName}"
                } catch (error: LinkageError) {
                    "宿主接口不兼容：${error.javaClass.simpleName}"
                }
            }
        }
    }

    @OptIn(UnstableSpwWorkshopApi::class)
    private fun registerSongs(session: Any) {
        if (activeSession !== session || songPageHook != null) return
        if (!canTryNativeUi() || pluginContext.spwVersion != "1.19.3" ||
            !WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)
        ) {
            songDiagnostics.registration = "需要受支持的 1.19.3 宿主和方法与 UI Hook 权限"
            return
        }
        val diagnostics = SongPageDiagnostics()
        songDiagnostics = diagnostics
        try {
            val candidate = NativeSongCue(SongHookTarget.resolve(), { hideWholeTrack.value },
                { activeSession === session }, diagnostics, songVisibility)
            songPageHook = candidate.install()
            songPage = candidate
            diagnostics.registration = "已连接歌曲页 · 自动显示待启用"
            candidate.setAutomatic(showCueSongs.value)
        } catch (failure: Exception) {
            diagnostics.registration = "注册失败"
            diagnostics.failed("注册", failure)
        } catch (failure: LinkageError) {
            diagnostics.registration = "宿主接口不兼容"
            diagnostics.failed("注册", failure)
        }
    }

    private fun diagnoseSongs(session: Any, onUpdated: () -> Unit) {
        if (activeSession !== session) return
        registerSongs(session)
        songPage?.startDiagnostics(onUpdated)
    }

    private fun mountSongs(session: Any) {
        if (activeSession !== session) return
        val candidate = songPage ?: return
        try {
            candidate.enableMount()
            songDiagnostics.registration = "本次已启用试挂载 · 歌曲页打开时自动更新 · 重启后关闭"
        } catch (failure: Exception) {
            songDiagnostics.failed("启用试挂载", failure)
        } catch (failure: LinkageError) {
            songDiagnostics.failed("启用试挂载", failure)
        }
    }

    private fun windowFor(session: Any): ProbeWindow =
        window?.takeIf { it.isDisplayable } ?: ProbeWindow(
            pluginContext.pluginVersion,
            "${pluginContext.spwVersion} / ${pluginContext.spwChannel} / ${System.getProperty("os.name")}",
            albumStatus = {
                val taskbar = taskbarStatus?.let { "\n任务栏  $it" }.orEmpty()
                "注册  $albumHookStatus\n页面  $albumPageStatus\n封面  $albumCoverStatus\n专辑卡片  $gridCoverStatus\n歌曲封面  $songCoverStatus\n播放封面  $playbackCoverStatus\n播控条  $miniPlayerStatus$taskbar"
            },
            retryAlbumHook = { registerAlbumHook(session, retry = true) },
            songReport = { songDiagnostics.report() },
            diagnoseSongs = { onUpdated -> diagnoseSongs(session, onUpdated) },
            mountSongs = { mountSongs(session) },
            hiddenSongs = songVisibility?.hidden,
            restoreHiddenSongs = { if (activeSession === session) songPage?.restoreHiddenSongs() },
            active = { activeSession === session }
        ).also {
            window = it
            windowPinned = false
        }

    private fun showFollowedCue(session: Any, cue: FollowedCue?) {
        if (cue != null) {
            val current = windowFor(session)
            current.showAutomatic(cue)
            if (!current.isVisible) {
                current.isAutoRequestFocus = false
                current.isVisible = true
            }
        } else {
            val current = window?.takeIf { it.isDisplayable } ?: return
            if (windowPinned) current.clearAutomatic() else current.dispose()
        }
    }

    @Synchronized
    override fun stop() {
        activeSession = null
        PlaybackProbe.mediaChanged = null
        runCatching { songPageHook?.close() }
        songPageHook = null
        songPage = null
        songVisibility = null
        songDiagnostics = SongPageDiagnostics()
        runCatching { taskbarCue?.close() }
        taskbarCue = null
        taskbarStatus = null
        runCatching { miniPlayerCue?.close() }
        miniPlayerCue = null
        runCatching { playbackCover?.close() }
        playbackCover = null
        closeAlbumCoverHook()
        try {
            try {
                albumHook?.close()
            } finally {
                shortcut?.close()
            }
        } finally {
            runCatching { configSubscription?.close() }
            configSubscription = null
            hideWholeTrack.value = false
            showCueSongs.value = false
            albumHook = null
            shortcut = null
            SwingUtilities.invokeLater {
                follow?.close()
                follow = null
                window?.dispose()
                window = null
            }
        }
    }
}

@Extension
class PlaybackProbe : PlaybackExtensionPoint {
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        mediaChanged?.invoke()
        return null
    }

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        if (state == PlaybackExtensionPoint.State.Ready || state == PlaybackExtensionPoint.State.Idle) {
            mediaChanged?.invoke()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) mediaChanged?.invoke()
    }

    override fun onSeekTo(position: Long) {
        lastSeek = Sample(position, System.nanoTime())
    }

    override fun onPositionUpdated(position: Long) {
        lastPosition = Sample(position, System.nanoTime())
    }

    companion object {
        @Volatile internal var mediaChanged: (() -> Unit)? = null
        @Volatile internal var lastSeek: Sample? = null
            private set
        @Volatile internal var lastPosition: Sample? = null
            private set
    }

    internal data class Sample(val position: Long, val observedAt: Long)
}
