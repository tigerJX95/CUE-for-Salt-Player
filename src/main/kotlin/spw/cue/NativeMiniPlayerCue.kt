package spw.cue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import spw.cue.HostMiniPlayerText.Role
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JvmModifier
import java.lang.reflect.ParameterizedType
import java.awt.EventQueue

@OptIn(UnstableSpwWorkshopApi::class)
internal class NativeMiniPlayerCue(
    private val active: () -> Boolean,
    private val report: (String) -> Unit
) : AutoCloseable {
    private val host = HostCurrentPlayback()
    private val display = MutableStateFlow<Display?>(null)
    val taskbarTracks: Flow<Pair<Any, CueTrack>?> =
        display.map { match -> match?.let { it.item to it.track } }
    fun isCurrentItem(item: Any): Boolean = host.current.value === item
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val hooks = mutableListOf<AutoCloseable>()
    private val stack = StackWalker.getInstance()
    private var textBinding: HostMiniPlayerText? = null
    private val boundLabels = mutableSetOf<Role>()
    @Volatile private var navigationStatus = "等待注册"
    @Volatile private var titleStatus = "等待注册"
    @Volatile private var timeStatus = "等待注册"
    @Volatile private var progressStatus = "等待注册"
    @Volatile private var rowStatus = "等待注册"
    @Volatile private var playbackStatus = "跟踪当前分轨"

    init {
        navigationStatus = installPresentation {
            val caller = HostCatalog.owner("MiniPlayerControl (MiniPlayerControl.kt:")
            val controller = HostCatalog.type("com.xuncorp.voxzen.service.PlaybackController")
            val previous = controller.getMethod("previous")
            val next = controller.getMethod("next")
            for (method in listOf(previous, next)) {
                check(method.returnType == Void.TYPE && !JvmModifier.isStatic(method.modifiers))
                HostCatalog.requireSingleCall(caller, method)
            }
            val previousHook = installNavigationHook(previous, caller, -1)
            val nextHook = try {
                installNavigationHook(next, caller, 1)
            } catch (error: Throwable) {
                runCatching { previousHook.close() }
                throw error
            }
            AutoCloseable {
                try { nextHook.close() } finally { previousHook.close() }
            }
        }
        titleStatus = installPresentation {
            val text = HostMiniPlayerText()
            timeStatus = if (text.hasTimeLabels) "已定位，等待展开" else "未定位到展开时间行"
            WorkshopApi.hookRegistrar.afterComposable(
                text.render.declaringClass.name, text.render.name, text.caller.name,
                text.render.parameterTypes.takeWhile { it != Composer::class.java }.map { it.name }, 50
            ) { call -> renderText(call, text) }.also { textBinding = text }
        }
        progressStatus = installPresentation {
            val seekbar = HostMiniSeekbar()
            WorkshopApi.hookRegistrar.replaceComposable(
                seekbar.render.declaringClass.name, seekbar.render.name, seekbar.caller.name,
                listOf(Modifier::class.java.name), 50
            ) { call -> renderProgress(call, seekbar) }
        }
        rowStatus = installPresentation {
            val row = HostAlbumRow()
            HostCatalog.requireSingleCall(row.caller, row.render)
            WorkshopApi.hookRegistrar.replaceComposable(
                row.render.declaringClass.name, row.render.name, row.caller.name,
                row.render.parameterTypes.takeWhile { it != Composer::class.java }.map { it.name }, 50
            ) { call -> renderAlbumRow(call, row) }
        }
        status("跟踪当前分轨")
        scope.launch {
            host.current.collectLatest { item ->
                textBinding?.restoreVisibleLabels()
                display.value = null
                if (item != null && active()) {
                    status("正在查找当前音频的 CUE")
                    follow(item)
                } else if (active()) {
                    status("没有播放项")
                }
            }
        }
    }

    private fun installPresentation(install: () -> AutoCloseable): String = try {
        hooks += install()
        "已注册，等待绘制"
    } catch (error: Exception) {
        "注册失败（${error.message ?: error.javaClass.simpleName}）"
    } catch (error: LinkageError) {
        "宿主签名不匹配（${error.javaClass.simpleName}）"
    }

    private fun status(message: String) {
        playbackStatus = message
        publishStatus()
    }

    private fun publishStatus() =
        report("$playbackStatus · 切换 $navigationStatus · 标题 $titleStatus · 时间行 $timeStatus · 进度 $progressStatus · 整轨行 $rowStatus")

    private fun installNavigationHook(method: Method, caller: Class<*>, step: Int): AutoCloseable =
        WorkshopApi.hookRegistrar.hookMethod(method.declaringClass.name, method.name, emptyList(), 50) {
            before { call ->
                if (!active()) return@before
                val match = display.value?.takeIf { it.item === host.current.value } ?: return@before
                if (!stack.walk { frames -> frames.limit(28).anyMatch { it.className == caller.name } }) return@before
                val progress = host.progress.value ?: return@before
                val position = host.position.invoke(progress) as Long
                val duration = host.duration.invoke(progress) as Long
                val current = cueTrackAt(match.tracks, position, duration) ?: return@before
                val target = adjacentCueTrack(match.tracks, current, duration, step) ?: return@before
                if (host.current.value !== match.item) return@before
                call.setResult(null)
                try {
                    WorkshopApi.playback.seekTo(target.startMs)
                    navigationStatus = "已跳转第 ${target.number} 轨"
                    publishStatus()
                } catch (error: Exception) {
                    status("分轨切换失败：${error.javaClass.simpleName}")
                }
            }
        }

    private suspend fun follow(item: Any) {
        val audio = LibraryProbe.localPath(host.path.invoke(item) as? String)
        if (audio == null) {
            status("当前播放路径不可用")
            return
        }
        val cueTracks = try {
            runInterruptible(Dispatchers.IO) {
                CueDiscovery.find(audio)?.sheet?.tracks?.filter { it.audio == audio }.orEmpty()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            status("读取播放分轨失败：${error.javaClass.simpleName}")
            return
        }
        if (cueTracks.isEmpty()) {
            status("当前音频无匹配 CUE")
            return
        }
        val fallback = host.fallbackArtist(item)
        val tracks = if (fallback == null) cueTracks else cueTracks.map { track ->
            if (track.performer == null) track.copy(performer = fallback) else track
        }

        host.progress.map { progress ->
            if (progress == null) null else {
                val position = host.position.invoke(progress) as Long
                val duration = host.duration.invoke(progress) as Long
                cueTrackAt(tracks, position, duration)
            }
        }.distinctUntilChanged().collect { track ->
            if (active() && host.current.value === item) {
                display.value = track?.let { Display(item, tracks, it) }
                if (track == null) {
                    textBinding?.restoreVisibleLabels()
                } else {
                    updateVisibleMetadata(item, track)
                }
                status(if (track == null) "已找到 CUE，等待进入分轨区间" else "播放位置匹配第 ${track.number} 轨")
            }
        }
    }

    private fun updateVisibleMetadata(item: Any, track: CueTrack) {
        val text = textBinding ?: return
        if (Role.TITLE !in boundLabels) {
            if (runCatching { text.updateVisibleLabel(item, Role.TITLE, track.title) }.getOrDefault(false)) {
                titleStatus = "已更新当前页面第 ${track.number} 轨"
            }
        }
        val artist = track.performer
        if (artist == null) {
            text.restoreVisibleLabel(Role.ARTIST)
        } else if (Role.ARTIST !in boundLabels) {
            runCatching { text.updateVisibleLabel(item, Role.ARTIST, artist) }
        }
    }

    @Composable
    private fun currentDisplay(): Display? =
        display.collectAsState().value?.takeIf { active() && host.current.value === it.item }

    @Composable
    private fun currentCueProgress(match: Display?): TrackProgress? {
        if (match == null) return null
        val snapshot = host.progress.collectAsState().value ?: return null
        return cueProgress(match, snapshot)
    }

    private fun cueProgress(match: Display, snapshot: Any): TrackProgress? {
        val position = host.position.invoke(snapshot) as Long
        val duration = host.duration.invoke(snapshot) as Long
        val track = cueTrackAt(match.tracks, position, duration) ?: return null
        val window = CuePlaybackWindow.from(track, duration) ?: return null
        return TrackProgress(track, window, position)
    }

    @Composable
    private fun renderProgress(call: UiHookCall, seekbar: HostMiniSeekbar) {
        val modifier = call.arguments.firstOrNull() as? Modifier ?: Modifier
        val match = currentDisplay()
        val progress = currentCueProgress(match)
        if (match == null || progress == null) {
            invoke(seekbar.render, modifier, currentComposer, 0, 0)
            SideEffect { reportProgress("使用原生进度") }
            return
        }

        val (track, window, position) = progress
        val radius = (seekbar.cornerRadius.invoke(seekbar.geometry.current) as Float).coerceAtLeast(0f)
        val color = Color(seekbar.color(currentComposer).toULong())
        key(match.item, track) {
            CueMiniSeekbar(modifier, window, position, color, radius, seekbar.padding) { fraction ->
                seekWithinCue(match, track, window, fraction)
            }
        }
        SideEffect { reportProgress("已显示第 ${track.number} 轨（${formatCueTime(window.durationMs)}）") }
    }

    private fun reportProgress(next: String) {
        if (progressStatus != next) {
            progressStatus = next
            publishStatus()
        }
    }

    private fun seekWithinCue(
        match: Display, track: CueTrack, window: CuePlaybackWindow, fraction: Float
    ): Boolean {
        if (!active() || host.current.value !== match.item) return false
        val progress = host.progress.value?.let { cueProgress(match, it) } ?: return false
        if (progress.track != track || progress.window != window) return false
        val target = window.seekPosition(fraction) ?: return false
        return try {
            WorkshopApi.playback.seekTo(target)
            true
        } catch (error: Exception) {
            status("分轨定位失败：${error.javaClass.simpleName}")
            false
        }
    }

    @Composable
    private fun renderText(call: UiHookCall, text: HostMiniPlayerText) {
        val role = remember(text) { text.origin() } ?: return
        val original = call.arguments.firstOrNull() as? String ?: return
        val anchor = remember { MiniPlayerLabelAnchor() }
        val composition = currentComposer.compositionData
        val match = currentDisplay()
        val label = when (role) {
            Role.TITLE -> match?.track?.title
            Role.ARTIST -> match?.track?.performer
            Role.ELAPSED -> currentCueProgress(match)?.let {
                formatCueTime(it.window.elapsedMs(it.position))
            }
            Role.DURATION -> currentCueProgress(match)?.let { formatCueTime(it.window.durationMs) }
        }
        DisposableEffect(anchor) {
            onDispose {
                anchor.restore()
                boundLabels -= role
            }
        }
        SideEffect {
            if (role == Role.TITLE || role == Role.ARTIST) text.restoreVisibleLabel(role)
            val updated = if (label == null) {
                anchor.restore(original)
                false
            } else anchor.update(composition, original, label)
            if (role == Role.TITLE || role == Role.ARTIST) {
                if (label == null || updated) boundLabels += role else boundLabels -= role
            }
            if (role == Role.TITLE) {
                val next = when {
                    match == null -> "已进入原生标题，等待匹配分轨"
                    updated -> "已绘制第 ${match.track.number} 轨"
                    else -> "已进入原生标题，等待文本节点"
                }
                if (titleStatus != next) {
                    titleStatus = next
                    publishStatus()
                }
            }
            if (role == Role.DURATION) {
                val next = when {
                    label == null -> "使用原生时长"
                    updated -> "当前分轨 $label"
                    else -> "等待文本节点"
                }
                if (timeStatus != next) {
                    timeStatus = next
                    publishStatus()
                }
            }
        }
    }

    @Composable
    private fun renderAlbumRow(call: UiHookCall, row: HostAlbumRow) {
        val args = call.arguments
        val item = args[0]
        val match = if (active() && LocalCueAlbumRow.current) {
            display.collectAsState().value?.takeIf { it.item === host.current.value }
        } else null
        val audio = if (match == null || item == null) null else remember(item) {
            LibraryProbe.localPath(host.path.invoke(row.converter.invoke(null, item)) as? String)
        }
        val title = match?.track?.takeIf { it.audio == audio }?.title
        val rendered = remember(item, title) {
            if (title == null) item else row.withTitle(checkNotNull(item), title)
        }
        val defaults = (if (args[1] == null) 2 else 0) or (if (args[2] == null) 4 else 0)
        invoke(row.render, rendered, args[1], args[2], currentComposer, 0, defaults)
        if (title != null) SideEffect {
            if (rowStatus != "已绘制分轨曲名") {
                rowStatus = "已绘制分轨曲名"
                publishStatus()
            }
        }
    }

    private fun invoke(method: Method, vararg arguments: Any?) {
        try {
            method.invoke(null, *arguments)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    override fun close() {
        scope.cancel()
        EventQueue.invokeLater { textBinding?.restoreVisibleLabels() }
        for (hook in hooks.asReversed()) runCatching { hook.close() }
        hooks.clear()
    }

    private data class TrackProgress(val track: CueTrack, val window: CuePlaybackWindow, val position: Long)

    private data class Display(val item: Any, val tracks: List<CueTrack>, val track: CueTrack)

    private class HostAlbumRow {
        private val track = HostCatalog.track
        val caller = HostCatalog.owner("TrackLazyColumn (TrackLazyColumn.kt:")
        val render = HostCatalog.owner("TrackItemContent (TrackItem.kt:").declaredMethods.single {
            val types = it.parameterTypes
            JvmModifier.isStatic(it.modifiers) && JvmModifier.isPublic(it.modifiers) &&
                it.returnType == Void.TYPE && types.size == 6 &&
                types[0] == track && types[1] == Modifier::class.java &&
                types[3] == Composer::class.java && types.drop(4).all { it == Int::class.javaPrimitiveType }
        }
        val converter = HostCatalog.mediaConverter
        private val copy = track.declaredMethods.single { it.name == "copy\$default" && it.parameterCount == 26 }

        fun withTitle(item: Any, title: String): Any {
            val args = copy.parameterTypes.map<Class<*>, Any?> {
                when (it) {
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }.toTypedArray()
            args[0] = item
            args[2] = title
            args[args.lastIndex - 1] = -3 // Retain every field except title.
            return copy.invoke(null, *args)
        }
    }
}

private val miniCoverEntry by lazy {
    HostCatalog.owner("MiniPlayerCoverItem (MiniPlayerCoverItem.kt:").declaredMethods.single {
        val types = it.parameterTypes
        JvmModifier.isStatic(it.modifiers) && JvmModifier.isPublic(it.modifiers) && it.returnType == Void.TYPE &&
            types.size == 6 && types[0] == Function0::class.java && types[1] == Modifier::class.java &&
            types[3] == Composer::class.java && types.drop(4).all { it == Int::class.javaPrimitiveType }
    }
}

internal class HostCurrentPlayback {
    private val media = HostCatalog.media
    private val progressType = HostCatalog.type("com.xuncorp.pisces.PiscesMediaController\$Progress")
    private val mediaConstructor = media.getConstructor(*Array(6) { String::class.java })
    private val mediaFields = HostCatalog.accessors(mediaConstructor)
    val path = mediaFields.last()
    private val progressFields = HostCatalog.accessors(
        progressType.getConstructor(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
    )
    val position = progressFields[0]
    val duration = progressFields[1]
    private val currentGetter = HostCatalog.calls(miniCoverEntry).filter {
        it.type().stringValue() == "()Lkotlinx/coroutines/flow/StateFlow;" &&
            it.opcode() == java.lang.classfile.Opcode.INVOKESTATIC
    }.map(HostCatalog::resolve).distinct().single { it.flowType() == media }
    val current = currentGetter.invoke(null) as StateFlow<*>
    val progress = currentGetter.declaringClass.declaredMethods.single {
        JvmModifier.isStatic(it.modifiers) && it.flowType() == progressType
    }.invoke(null) as StateFlow<*>

    fun fallbackArtist(item: Any): String? =
        (mediaFields[2].invoke(item) as? String).artistName()
            ?: (mediaFields[4].invoke(item) as? String).artistName()

    private fun Method.flowType(): Class<*>? {
        val signature = genericReturnType as? ParameterizedType ?: return null
        if (parameterCount != 0 || signature.rawType != StateFlow::class.java) return null
        return signature.actualTypeArguments.singleOrNull() as? Class<*>
    }
}
