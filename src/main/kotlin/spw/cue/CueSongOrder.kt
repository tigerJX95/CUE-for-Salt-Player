package spw.cue

import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class OrderedSong(val source: AlbumHookTarget.Track, val cue: CueTrack?)

/** Keep the visible song order separately from the native queue of whole audio files. */
internal object CueSongOrder {
    internal class Cursor(
        val rows: List<OrderedSong>,
        val index: Int,
        val active: () -> Boolean,
        val play: ((OrderedSong, Boolean, Boolean) -> Unit)?
    ) {
        val row: OrderedSong get() = rows[index]
        @Volatile private var reachedSource = false

        fun matches(id: String?, audio: Path?): Boolean =
            id == row.source.id || audio != null && audio == row.cue?.audio

        fun hasReachedSource(matches: Boolean): Boolean {
            if (matches) reachedSource = true
            return reachedSource
        }
    }

    private val selected = MutableStateFlow<Cursor?>(null)

    val wholeSourceId = selected.map { it?.wholeSourceId() }.distinctUntilChanged()

    private fun Cursor.wholeSourceId(): String? = row.source.id.takeIf { row.cue == null && active() }

    fun isWholeFile(id: String?): Boolean = id != null && id == selected.value?.wholeSourceId()

    fun clear() { selected.value = null }

    fun isOrdered(mode: String?): Boolean = mode == "Circle" || mode == "CircleEnd"

    fun start(
        rows: List<OrderedSong>, index: Int, active: () -> Boolean,
        replaceQueue: Boolean = true, switchSource: Boolean = true,
        play: (OrderedSong, Boolean, Boolean) -> Unit
    ) {
        val cursor = Cursor(rows, index, active, play)
        selected.value = cursor
        try {
            play(cursor.row, switchSource, replaceQueue)
        } catch (error: Throwable) {
            selected.compareAndSet(cursor, null)
            throw error
        }
    }

    fun remember(rows: List<OrderedSong>, index: Int, active: () -> Boolean, play: (OrderedSong, Boolean, Boolean) -> Unit) {
        selected.value = Cursor(rows, index, active, play)
    }

    fun startWholeFile(source: AlbumHookTarget.Track, active: () -> Boolean) {
        selected.value = Cursor(listOf(OrderedSong(source, null)), 0, active, play = null)
    }

    fun sourceChanged(id: String?, audio: Path?) {
        // Rebuilding the host queue can briefly emit no current media.
        if (id == null && audio == null) return
        matchingCursor(id, audio)
    }

    private fun matchingCursor(id: String?, audio: Path?): Cursor? {
        val cursor = selected.value ?: return null
        if (!cursor.active()) return null
        val matches = cursor.matches(id, audio)
        if (cursor.hasReachedSource(matches) && !matches && (id != null || audio != null)) {
            selected.compareAndSet(cursor, null)
        }
        return cursor.takeIf { matches }
    }

    fun current(mode: String?, id: String?, audio: Path?): Cursor? =
        matchingCursor(id, audio)?.takeIf { isOrdered(mode) && it.play != null }

    fun ownsNavigation(mode: String?): Boolean =
        isOrdered(mode) && selected.value?.let { it.play != null && it.active() } == true

    fun step(
        mode: String?, id: String?, audio: Path?, direction: Int,
        excluded: Set<String>, stop: () -> Unit
    ): Boolean {
        val cursor = current(mode, id, audio)
        if (!ownsNavigation(mode)) return false
        // During a source switch and at the first row, there is no native CUE mark to step to.
        if (cursor != null) advance(cursor, mode, direction, excluded, stop)
        return true
    }

    fun advance(
        cursor: Cursor, mode: String?, direction: Int,
        excluded: Set<String>, stop: () -> Unit, segmentEnded: Boolean = false, atFileEnd: Boolean = false
    ): Boolean {
        val play = cursor.play ?: return false
        if (!cursor.active() || selected.value !== cursor || !isOrdered(mode)) return false
        val next = nextOrderedSong(cursor.rows, cursor.index, direction, mode == "Circle", excluded)
        if (next == null) {
            if (direction < 0) return false
            stop()
            return true
        }
        val target = Cursor(cursor.rows, next, cursor.active, play)
        if (!selected.compareAndSet(cursor, target)) return false
        val sameSource = target.row.source.id == cursor.row.source.id
        val nextCue = target.row.cue
        val contiguous = segmentEnded && sameSource && nextCue != null && cursor.row.cue?.endMs == nextCue.startMs
        try {
            if (!contiguous) play(target.row, atFileEnd || !sameSource, false)
        } catch (error: Throwable) {
            selected.compareAndSet(target, cursor)
            throw error
        }
        return true
    }
}

internal fun nextOrderedSong(
    rows: List<OrderedSong>, current: Int, direction: Int, loop: Boolean, excluded: Set<String>
): Int? {
    if (current !in rows.indices || direction !in listOf(-1, 1)) return null
    var index = current
    repeat(rows.size) {
        index += direction
        if (index !in rows.indices) {
            if (!loop) return null
            index = if (direction > 0) 0 else rows.lastIndex
        }
        val track = rows[index].cue
        if (track == null || SongVisibility.key(track) !in excluded) return index
    }
    return null
}
