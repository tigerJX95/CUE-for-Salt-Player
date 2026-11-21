package spw.cue

import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

internal class CueAlbumSequence(
    private val discs: List<Disc?>,
    private val playAfter: ((from: Disc, to: Disc, position: Long) -> Unit)? = null,
    private val active: () -> Boolean
) {
    data class Disc(
        val id: String,
        val audio: Path,
        val cue: AlbumCue,
        val play: (Long, Boolean) -> Unit
    )

    private class Position(val sequence: CueAlbumSequence, val index: Int) {
        val disc = checkNotNull(sequence.discs[index])
        val startedAt = System.nanoTime()
        fun matches(audio: Path?) = disc.audio == audio
    }

    fun hasDisc(id: String): Boolean = discs.any { it?.id == id }

    fun select(id: String, position: Long, switchSource: Boolean) {
        val index = discs.indexOfFirst { it?.id == id }
        val selected = Position(this, index)
        val previous = current.getAndSet(selected)
        try {
            val needsQueue = previous?.disc?.id != selected.disc.id
            selected.disc.play(position, switchSource || needsQueue)
        } catch (error: Exception) {
            current.compareAndSet(selected, null)
            throw error
        }
    }

    companion object {
        private val current = AtomicReference<Position?>(null)

        fun clear() {
            current.set(null)
        }

        fun sourceChanged(audio: Path?) {
            val selected = current.get() ?: return
            if (audio != null && audio != selected.disc.audio) current.compareAndSet(selected, null)
        }

        fun step(from: CueTrack, step: Int): CueTrack? {
            val selected = current.get() ?: return null
            if (selected.disc.audio != from.audio || !isCurrent(selected)) return null
            val tracks = selected.disc.cue.tracks
            val edge = if (step > 0) tracks.lastOrNull() else tracks.firstOrNull()
            if (edge?.startMs != from.startMs) return null
            return move(selected, step)
        }

        fun hasNativeSuccessor(mode: String?): Boolean =
            nativeEndPosition(mode)?.let { destination(it, 1) != null } == true

        fun advanceAtNativeEnd(mode: String?): CueTrack? =
            nativeEndPosition(mode)?.let { move(it, 1, natural = true) }

        private fun nativeEndPosition(mode: String?): Position? {
            if (mode != "Circle" && mode != "CircleEnd") return null
            val selected = current.get() ?: return null
            return selected.takeIf(::isCurrent)
        }

        fun ended(sample: PlaybackProbe.Sample?) {
            val selected = current.get() ?: return
            if (sample == null || sample.observedAt < selected.startedAt || !selected.sequence.active()) return
            // Ignore end notifications if progress has not reached this disc's final seconds.
            if (sample.position <= 0L || sample.position < selected.disc.cue.durationMs - 2_000L) return
            EventQueue.invokeLater {
                if (current.get() !== selected) return@invokeLater
                runCatching { if (isCurrent(selected)) move(selected, 1, natural = true) }
            }
        }

        private fun isCurrent(selected: Position): Boolean {
            if (!selected.sequence.active()) return false
            val item = LibraryProbe.current() ?: return false
            return selected.matches(LibraryProbe.pathOf(item))
        }

        private fun destination(from: Position, step: Int): CueTrack? {
            val disc = from.sequence.discs.getOrNull(from.index + step) ?: return null
            val visible = CueRemoval.visibleAlbum(disc.cue).tracks
            val ordered = if (step > 0) visible else visible.asReversed()
            return ordered.firstOrNull { CuePlaybackWindow.from(it, disc.cue.durationMs) != null }
        }

        private fun move(from: Position, step: Int, natural: Boolean = false): CueTrack? {
            val track = destination(from, step) ?: return null
            val target = Position(from.sequence, from.index + step)
            if (!current.compareAndSet(from, target)) return null
            try {
                val playAfter = from.sequence.playAfter
                if (natural && playAfter != null) playAfter(from.disc, target.disc, track.startMs)
                else target.disc.play(track.startMs, true)
            } catch (error: Exception) {
                current.compareAndSet(target, null)
                throw error
            }
            return track
        }
    }
}
