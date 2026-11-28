package spw.cue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xuncorp.spw.workshop.api.ActionShortcut
import com.xuncorp.spw.workshop.api.Channel
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel as CoroutineChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.awt.event.KeyEvent
import java.util.function.Consumer
import javax.swing.SwingUtilities

class CuePlugin(context: PluginContext) : SpwPlugin(context) {
    @OptIn(UnstableSpwWorkshopApi::class)
    private class Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val albumRefresh = AlbumCueRefresh()
        val configChanges = CoroutineChannel<ConfigHelper>(CoroutineChannel.CONFLATED)
        var configReady = false
        var preparingNativeUi = false
    }

    @Volatile private var activeSession: Session? = null
    private var nativeIndexReady = false
    private var shortcut: AutoCloseable? = null
    private var window: ProbeWindow? = null
    private var follow: CueFollow? = null
    private var windowPinned = false
    private var albumHook: AutoCloseable? = null
    private var albumCoverHook: AutoCloseable? = null
    private var gridCoverHook: AutoCloseable? = null
    private var artistCoverHook: AutoCloseable? = null
    private var playbackCover: PlaybackCoverFallback? = null
    private var songCoverHook: AutoCloseable? = null
    private var songPageHook: AutoCloseable? = null
    private var songPage: NativeSongCue? = null
    private var songVisibility by mutableStateOf<SongVisibility?>(null)
    private var songDiagnostics = SongPageDiagnostics()
    private var miniPlayerCue by mutableStateOf<NativeMiniPlayerCue?>(null)
    private var taskbarCue: AutoCloseable? = null
    private var configSubscription: AutoCloseable? = null
    private val hideWholeTrack = mutableStateOf(false)
    private val showCueSongs = mutableStateOf(true)
    @Volatile private var albumHookStatus = "尚未注册"
    @Volatile private var albumCoverStatus = "尚未注册"
    @Volatile private var albumStatisticsStatus = "尚未注册"
    @Volatile private var gridCoverStatus = "尚未注册"
    @Volatile private var artistCoverStatus = "尚未注册"
    @Volatile private var albumPageStatus = "尚未收到专辑页回调"
    @Volatile private var songCoverStatus = "尚未注册"
    @Volatile private var playbackCoverStatus = "尚未注册"
    @Volatile private var miniPlayerStatus = "尚未注册"
    @Volatile private var taskbarStatus: String? = null

    @Synchronized
    override fun start() {
        if (activeSession != null) return
        val session = Session()
        activeSession = session
        val manager = WorkshopApi.manager
        if (manager.isPermissionGranted(PluginPermission.KEY_BINDINGS)) {
            try {
                shortcut = manager.keyBindingManager.register(
                    "open-cue-probe", "CUE for Salt Player：打开 CUE 分轨窗口",
                    ActionShortcut(KeyEvent.VK_U, ActionShortcut.CONTROL or ActionShortcut.ALT),
                    false
                ) {
                    dispatch(session) {
                        val current = windowFor(session)
                        windowPinned = true
                        current.isAutoRequestFocus = true
                        current.isVisible = true
                        current.toFront()
                        current.loadOnFirstOpen()
                    }
                }
            } catch (error: Exception) {
                WorkshopApi.ui.toast("CUE 快捷键不可用：${error.javaClass.simpleName}", WorkshopApi.Ui.ToastType.Warning)
            } catch (error: LinkageError) {
                WorkshopApi.ui.toast("当前宿主的快捷键接口不兼容", WorkshopApi.Ui.ToastType.Warning)
            }
        }

        albumHookStatus = "正在准备专辑页接入"
        albumPageStatus = "尚未收到专辑页回调"
        albumCoverStatus = "尚未注册"
        albumStatisticsStatus = "尚未注册"
        gridCoverStatus = "尚未注册"
        artistCoverStatus = "尚未注册"
        songCoverStatus = "尚未注册"
        miniPlayerStatus = "尚未注册"
        playbackCoverStatus = "尚未注册"
        val unavailable = nativeUnavailableReason()
        if (unavailable == null) {
            // Stage album hooks together while the host is starting the plugin.
            registerAlbumHook(session, retry = false)
        } else {
            showNativeUnavailable(unavailable)
        }
        observeConfig(session)
    }

    private fun dispatch(session: Session, action: () -> Unit) {
        SwingUtilities.invokeLater {
            synchronized(this) {
                if (activeSession === session) action()
            }
        }
    }

    private fun canReadLibrary(): Boolean =
        WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ)

    private fun nativeUnavailableReason(): String? = when {
        !canTryNativeUi() -> "当前宿主未启用原生接入"
        !canReadLibrary() -> "未授予曲库读取权限"
        !WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM) ->
            "未授予方法与 UI Hook 权限"
        else -> null
    }

    private fun prepareNativeUi(session: Session) {
        nativeUnavailableReason()?.let { reason ->
            showNativeUnavailable(reason)
            startPopupFallback(session)
            return
        }
        if (!session.configReady || session.preparingNativeUi || nativeIndexReady) return
        session.preparingNativeUi = true
        session.scope.launch {
            val failure = try {
                runInterruptible { HostCatalog.prepare() }
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                "宿主索引准备失败：${error.javaClass.simpleName}"
            } catch (error: LinkageError) {
                "宿主接口不兼容：${error.javaClass.simpleName}"
            }
            dispatch(session) {
                session.preparingNativeUi = false
                val reason = nativeUnavailableReason() ?: failure
                if (reason == null) {
                    nativeIndexReady = true
                    if (albumHook == null) registerAlbumHook(session, retry = false)
                    registerSongs(session)
                    registerPlaybackCover(session)
                    registerMiniPlayer(session)
                } else {
                    showNativeUnavailable(reason)
                }
                startPopupFallback(session)
            }
        }
    }

    private fun showNativeUnavailable(reason: String) {
        albumHookStatus = reason
        albumStatisticsStatus = reason
        playbackCoverStatus = reason
        miniPlayerStatus = reason
        songDiagnostics.registration = reason
    }

    private fun registerMiniPlayer(session: Session) {
        miniPlayerStatus = try {
            miniPlayerCue = NativeMiniPlayerCue({ activeSession === session }, { songVisibility?.recovery?.value?.removedFromLibrary.orEmpty() }) { status ->
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

    private fun startPopupFallback(session: Session) {
        if (albumHook != null || follow != null || !canReadLibrary()) return
        val listener = CueFollow({ activeSession === session && canReadLibrary() }) { cue ->
            synchronized(this) {
                if (activeSession === session) showFollowedCue(session, cue)
            }
        }
        follow = listener
        PlaybackProbe.mediaChanged = listener::signal
        listener.signal()
    }

    private fun registerTaskbarTitle(session: Session, playback: NativeMiniPlayerCue) {
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
    private fun observeConfig(session: Session) {
        session.scope.launch {
            try {
                val (manager, config) = runInterruptible {
                    val manager = WorkshopApi.manager.createConfigManager()
                    manager to manager.getConfig("config.json")
                }
                dispatch(session) {
                    try {
                        val listener = Consumer<ConfigHelper> { session.configChanges.trySend(it) }
                        manager.addConfigChangeListener("config.json", listener)
                        configSubscription = AutoCloseable { manager.removeConfigChangeListener(listener) }
                    } catch (error: Exception) {
                        albumHookStatus = "配置监听失败：${error.javaClass.simpleName}"
                    } catch (error: LinkageError) {
                        albumHookStatus = "配置监听不兼容：${error.javaClass.simpleName}"
                    }
                    session.configChanges.trySend(config)
                }

                var initial = true
                for (helper in session.configChanges) {
                    val visibility = if (initial) try {
                        runInterruptible {
                            SongVisibility(helper.getConfigPath().toAbsolutePath().resolveSibling("song-visibility.txt"))
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    } else null
                    val options = try {
                        runInterruptible {
                            helper.reload()
                            helper.get("hideWholeTrack", false) to helper.get("showCueSongs", true)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                    val initialize = initial
                    initial = false
                    dispatch(session) {
                        options?.let { (hide, show) ->
                            hideWholeTrack.value = hide
                            showCueSongs.value = show
                        }
                        if (initialize) {
                            songVisibility = visibility
                            CueRemoval.bind(visibility)
                            session.configReady = true
                            prepareNativeUi(session)
                        } else if (options != null) {
                            songPage?.setAutomatic(showCueSongs.value)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                dispatch(session) {
                    session.configReady = true
                    prepareNativeUi(session)
                }
            } catch (_: LinkageError) {
                dispatch(session) {
                    session.configReady = true
                    prepareNativeUi(session)
                }
            }
        }
    }

    @OptIn(UnstableSpwWorkshopApi::class)
    @Synchronized
    private fun registerAlbumHook(session: Session, retry: Boolean) {
        if (activeSession !== session) return
        nativeUnavailableReason()?.let { reason ->
            albumHookStatus = reason
            return
        }
        if (retry && !nativeIndexReady && albumHook == null) {
            prepareNativeUi(session)
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
            val content = NativeAlbumCue(
                target, { activeSession === session }, { hideWholeTrack.value }, session.scope,
                session.albumRefresh,
                { ids ->
                    dispatch(session) {
                        refreshAlbum(session, ids)
                        WorkshopApi.ui.toast("正在重新识别当前专辑的 CUE", WorkshopApi.Ui.ToastType.Success)
                    }
                },
                { ids -> dispatch(session) { showAlbumWindow(session, ids) } },
                { miniPlayerCue?.currentTrack() }
            ) { status ->
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
            albumStatisticsStatus = "沿用原生计数"
            albumCoverHook = target.cover?.let { cover ->
                try {
                    cover.install(
                        active = { activeSession === session },
                        trackIds = { groups -> target.trackGroupsIn(groups).map { disc -> disc.map { it.id } } },
                        refresh = session.albumRefresh,
                        artwork = { content.artwork },
                        report = { if (activeSession === session) albumCoverStatus = it },
                        reportStatistics = { if (activeSession === session) albumStatisticsStatus = it }
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
            artistCoverStatus = "未识别到艺术家头像入口"
            artistCoverHook = target.cover?.let { cover ->
                try {
                    cover.installArtists({ activeSession === session }) { status ->
                        if (activeSession === session) artistCoverStatus = status
                    }.also { artistCoverStatus = "已注册，等待艺术家头像绘制" }
                } catch (error: Exception) {
                    artistCoverStatus = "注册失败：${error.message ?: error.javaClass.simpleName}"
                    null
                } catch (error: LinkageError) {
                    artistCoverStatus = "宿主接口不兼容：${error.javaClass.simpleName}"
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
            stopPopupFallback()
            if (retry) "重新注册成功，请退出专辑详情再进入" else "已提交原生专辑页 Hook"
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
        runCatching { artistCoverHook?.close() }
        artistCoverHook = null
        runCatching { songCoverHook?.close() }
        songCoverHook = null
    }

    private fun registerPlaybackCover(session: Session) {
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
    @Synchronized
    private fun registerSongs(session: Session) {
        if (activeSession !== session || songPageHook != null) return
        val unavailable = nativeUnavailableReason()
        if (unavailable != null || pluginContext.spwVersion != "1.19.3" || !nativeIndexReady) {
            songDiagnostics.registration = unavailable ?: if (pluginContext.spwVersion != "1.19.3")
                "需要受支持的 1.19.3 宿主" else "正在准备宿主索引"
            return
        }
        val diagnostics = SongPageDiagnostics()
        songDiagnostics = diagnostics
        try {
            val candidate = NativeSongCue(SongHookTarget.resolve(), { hideWholeTrack.value },
                { activeSession === session }, diagnostics, songVisibility, { miniPlayerCue?.currentTrack() })
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

    private fun diagnoseSongs(session: Session, onUpdated: () -> Unit) {
        if (activeSession !== session) return
        registerSongs(session)
        songPage?.startDiagnostics(onUpdated)
    }

    private fun mountSongs(session: Session) {
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

    private fun refreshAlbum(session: Session, ids: List<String>) {
        if (activeSession !== session || ids.isEmpty()) return
        session.albumRefresh.request(ids)
        songPage?.refreshAlbum(ids)
        miniPlayerCue?.refreshCue()
        playbackCover?.refreshAlbum(ids)
        window?.takeIf { it.isDisplayable }?.reloadManagedAlbum(ids)
    }

    private fun showAlbumWindow(session: Session, ids: List<String>) {
        val current = windowFor(session)
        windowPinned = true
        current.isAutoRequestFocus = true
        current.isVisible = true
        current.toFront()
        current.openAlbum(ids)
    }

    private fun restoreSongs(session: Session, entries: Set<String>?) {
        if (activeSession !== session) return
        songPage?.let {
            it.restoreHiddenSongs(entries)
            return
        }
        val store = songVisibility ?: return
        session.scope.launch {
            if (activeSession !== session) return@launch
            try {
                if (entries == null) store.restoreAll() else store.restore(entries)
            } catch (error: Exception) {
                dispatch(session) {
                    WorkshopApi.ui.toast(error.message ?: "无法恢复分轨", WorkshopApi.Ui.ToastType.Warning)
                }
            }
        }
    }

    private fun windowFor(session: Session): ProbeWindow =
        window?.takeIf { it.isDisplayable } ?: ProbeWindow(
            pluginContext.pluginVersion,
            "${pluginContext.spwVersion} / ${pluginContext.spwChannel} / ${System.getProperty("os.name")}",
            albumStatus = {
                val taskbar = taskbarStatus?.let { "\n任务栏  $it" }.orEmpty()
                "注册  $albumHookStatus\n页面  $albumPageStatus\n专辑统计  $albumStatisticsStatus\n封面  $albumCoverStatus\n专辑卡片  $gridCoverStatus\n艺术家头像  $artistCoverStatus\n歌曲封面  $songCoverStatus\n播放封面  $playbackCoverStatus\n播控条  $miniPlayerStatus$taskbar"
            },
            retryAlbumHook = { registerAlbumHook(session, retry = true) },
            songReport = { songDiagnostics.report() },
            diagnoseSongs = { onUpdated -> diagnoseSongs(session, onUpdated) },
            mountSongs = { mountSongs(session) },
            visibility = { songVisibility?.recovery },
            restoreHiddenSongs = { restoreSongs(session, it) },
            refreshAlbum = { refreshAlbum(session, it) },
            currentTrack = { miniPlayerCue?.currentTrack() },
            canReadLibrary = ::canReadLibrary,
            refreshVisibility = { songVisibility?.refresh() },
            active = { activeSession === session }
        ).also {
            window = it
            windowPinned = false
        }

    private fun showFollowedCue(session: Session, cue: FollowedCue?) {
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

    private fun stopPopupFallback() {
        PlaybackProbe.mediaChanged = null
        val stoppedFollow = follow
        follow = null
        if (stoppedFollow != null) SwingUtilities.invokeLater { stoppedFollow.close() }
    }

    @Synchronized
    override fun stop() {
        val session = activeSession ?: return
        activeSession = null
        nativeIndexReady = false
        session.scope.cancel()
        stopPopupFallback()
        val stoppedWindow = window
        window = null
        windowPinned = false
        SwingUtilities.invokeLater { stoppedWindow?.dispose() }
        runCatching { songPageHook?.close() }
        songPageHook = null
        songPage = null
        songVisibility = null
        CueRemoval.bind(null)
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
            showCueSongs.value = true
            albumHook = null
            shortcut = null
        }
    }
}
