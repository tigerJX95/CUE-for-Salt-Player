package spw.cue

import java.util.Locale

internal fun formatCueTime(milliseconds: Long): String {
    val seconds = milliseconds / 1_000
    val hours = seconds / 3_600
    return if (hours == 0L) String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
}

internal fun cueTrackAt(tracks: List<CueTrack>, positionMs: Long, durationMs: Long): CueTrack? {
    if (durationMs <= 0 || positionMs < 0 || positionMs > durationMs) return null
    val track = tracks.lastOrNull { it.startMs <= positionMs && it.startMs < durationMs } ?: return null
    val end = track.endMs
    return track.takeIf { end == null || positionMs < end }
}

internal fun adjacentCueTrack(tracks: List<CueTrack>, current: CueTrack, durationMs: Long, step: Int): CueTrack? {
    val index = tracks.indexOf(current)
    if (index < 0) return null
    return tracks.getOrNull(index + step)?.takeIf { it.startMs in 0 until durationMs }
}

@ConsistentCopyVisibility
internal data class CuePlaybackWindow private constructor(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs

    fun elapsedMs(positionMs: Long): Long = positionMs.coerceIn(startMs, endMs) - startMs

    fun seekPosition(fraction: Float): Long? {
        if (!fraction.isFinite()) return null
        val elapsed = (durationMs * fraction.coerceIn(0f, 1f).toDouble()).toLong()
        return startMs + elapsed.coerceAtMost(durationMs - 1)
    }

    companion object {
        fun from(track: CueTrack, sourceDurationMs: Long): CuePlaybackWindow? {
            val endMs = minOf(track.endMs ?: sourceDurationMs, sourceDurationMs)
            return if (track.startMs in 0 until endMs) CuePlaybackWindow(track.startMs, endMs) else null
        }
    }
}
