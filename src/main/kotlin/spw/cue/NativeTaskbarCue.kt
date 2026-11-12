package spw.cue

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener

internal class NativeTaskbarCue(
    private val playback: NativeMiniPlayerCue,
    private val active: () -> Boolean,
    private val report: (String) -> Unit
) : AutoCloseable {
    private val host = HostTaskbarTitle()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var selected: Pair<Any, CueTrack>? = null
    private var caption: TaskbarCaption? = null
    private val windowEvents = AWTEventListener { event ->
        if (event is WindowEvent && (event.id == WindowEvent.WINDOW_OPENED || event.id == WindowEvent.WINDOW_CLOSED) &&
            host.isComposeWindow(event.window)
        ) scheduleRefresh()
    }

    init {
        scope.launch {
            guarded {
                val toolkit = Toolkit.getDefaultToolkit()
                toolkit.addAWTEventListener(windowEvents, AWTEvent.WINDOW_EVENT_MASK)
                try {
                    playback.taskbarTracks.combine(snapshotFlow { host.showsTrack() }) { track, enabled ->
                        track.takeIf { enabled }
                    }.collect { track ->
                        selected = track
                        guarded(::refresh)
                    }
                } finally {
                    toolkit.removeAWTEventListener(windowEvents)
                    caption?.close()
                    caption = null
                }
            }
        }
    }

    private fun scheduleRefresh() {
        EventQueue.invokeLater { if (active()) guarded(::refresh) }
    }

    private fun refresh() {
        val match = selected?.takeIf { active() && playback.isCurrentItem(it.first) && host.showsTrack() }
        val frame = match?.let { host.window() }
        if (caption?.frame !== frame) {
            caption?.close()
            caption = frame?.let { TaskbarCaption(it, ::scheduleRefresh) }
        }
        if (match == null || frame == null) {
            report(if (match == null) "使用宿主标题" else "等待宿主主窗口")
            return
        }
        val (item, track) = match
        val applied = caption?.show(host.nativeTitle(item), host.format(item, track)) == true
        report(if (applied) "已更新第 ${track.number} 轨标题" else "等待宿主曲名标题")
    }

    private inline fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            report("更新失败（${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}）")
        } catch (error: LinkageError) {
            report("宿主接口不匹配（${error.javaClass.simpleName}）")
        }
    }

    override fun close() = scope.cancel()
}

// All accesses happen on the AWT event thread.
internal class TaskbarCaption(val frame: Frame, refresh: () -> Unit) : AutoCloseable {
    private var original: String? = null
    private var applied: String? = null
    private val listener = PropertyChangeListener { event ->
        if (event.newValue != applied) {
            applied = null
            refresh()
        }
    }

    init {
        frame.addPropertyChangeListener("title", listener)
    }

    fun show(nativeTitle: String, cueTitle: String): Boolean {
        val shown = frame.title
        if (shown != nativeTitle && shown != applied) return false
        if (shown == cueTitle) return true
        if (shown != applied) original = shown
        applied = cueTitle
        frame.title = cueTitle
        return true
    }

    override fun close() {
        frame.removePropertyChangeListener("title", listener)
        if (applied != null && frame.title == applied) frame.title = original
        applied = null
    }
}
