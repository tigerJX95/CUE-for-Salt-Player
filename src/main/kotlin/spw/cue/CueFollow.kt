package spw.cue

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.Timer

internal data class FollowedCue(val file: Path, val result: ProbeResult)

internal class CueFollow(private val changed: (FollowedCue?) -> Unit) : AutoCloseable {
    private var closed = false
    private var pending = false
    private var worker: SwingWorker<Update, Void>? = null
    private var previous: MediaKey? = null
    private val debounce = Timer(300) {
        if (worker == null) refresh() else pending = true
    }.apply { isRepeats = false }

    fun signal() {
        SwingUtilities.invokeLater {
            if (!closed) debounce.restart()
        }
    }

    private fun refresh() {
        val last = previous
        worker = object : SwingWorker<Update, Void>() {
            override fun doInBackground(): Update {
                val current = LibraryProbe.current()
                val key = keyOf(current)
                if (key == last) return Update.Unchanged
                val cue = key?.let { find(it.path, current) }
                return if (key == keyOf(LibraryProbe.current())) Update.Changed(key, cue) else Update.Retry
            }

            override fun done() {
                if (closed) return
                worker = null
                val update = try {
                    get()
                } catch (_: ExecutionException) {
                    Update.Changed(null, null)
                }
                when (update) {
                    is Update.Changed -> {
                        previous = update.key
                        changed(update.cue)
                    }
                    Update.Retry -> debounce.restart()
                    Update.Unchanged -> Unit
                }
                if (pending) {
                    pending = false
                    debounce.restart()
                }
            }
        }.also { it.execute() }
    }

    private fun find(audio: Path, current: MediaItem?): FollowedCue? {
        val located = try {
            CueDiscovery.find(audio)
        } catch (_: IOException) {
            null
        } catch (_: IllegalStateException) {
            null
        } ?: return null
        return FollowedCue(located.path, LibraryProbe.inspect(located.sheet, current))
    }

    override fun close() {
        closed = true
        debounce.stop()
        worker?.cancel(true)
        worker = null
    }

    private fun keyOf(item: MediaItem?): MediaKey? =
        item?.let { media -> LibraryProbe.pathOf(media)?.let { MediaKey(media.id, it) } }

    private data class MediaKey(val id: String, val path: Path)

    private sealed interface Update {
        data class Changed(val key: MediaKey?, val cue: FollowedCue?) : Update
        data object Retry : Update
        data object Unchanged : Update
    }
}
