package spw.cue

import java.nio.file.Path
import kotlin.random.Random

internal data class CueShuffleEntry(
    val sourceIndex: Int, val audio: Path?, val sourceId: String,
    val track: CueTrack?, val durationMs: Long
) {
    val key: String = track?.let(SongVisibility::key) ?: "source:${audio ?: sourceId}"
    val startMs: Long get() = track?.startMs ?: 0L
}

/** Sample individual tracks, never sample a disc first and then its tracks. */
internal fun chooseCueShuffleEntry(
    entries: List<CueShuffleEntry>, currentAudio: Path?, currentTrack: CueTrack?,
    removed: Set<String>, random: Random = Random.Default
): CueShuffleEntry? {
    val candidates = entries.distinctBy { it.key }.filter { entry ->
        (entry.track == null || entry.key !in removed) &&
            !(entry.audio == currentAudio && (entry.track == null || entry.track.matchesPlaybackTrack(currentTrack)))
    }
    return candidates.takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)] }
}

/** A completed source/segment switch must release the gate in every playback mode. */
internal data class CuePendingTransition(val audio: Path?, val track: CueTrack?, val expires: Long) {
    fun blocks(currentAudio: Path?, currentTrack: CueTrack?, now: Long = System.nanoTime()): Boolean =
        now < expires && !(currentAudio == audio && (track == null || track.matchesPlaybackTrack(currentTrack)))
}

/** A seek or source change establishes a new boundary instead of ending the former track. */
internal class CueShuffleBoundary {
    private data class Anchor(
        val source: Any, val track: CueTrack, val endMs: Long,
        val positionMs: Long, val sampledAt: Long, val seekAt: Long?
    )
    private var anchor: Anchor? = null

    fun reset() { anchor = null }

    fun observe(
        source: Any, tracks: List<CueTrack>, positionMs: Long, durationMs: Long,
        seekAt: Long?, playing: Boolean?, now: Long = System.nanoTime(), speed: Float = 1f
    ): CueTrack? {
        val previous = anchor
        val current = cueTrackAt(tracks, positionMs, durationMs)
        anchor = current?.let { track ->
            CuePlaybackWindow.from(track, durationMs)?.let { window ->
                Anchor(source, track, window.endMs, positionMs, now, seekAt)
            }
        }
        if (playing == false || previous == null || previous.source !== source || previous.seekAt != seekAt) return null
        if (positionMs < previous.positionMs || positionMs < previous.endMs || previous.positionMs >= previous.endMs) return null
        val elapsedMs = ((now - previous.sampledAt).coerceAtLeast(0L) / 1_000_000.0)
        val plausibleAdvance = elapsedMs * speed.takeIf { it.isFinite() && it > 0f }.let { it ?: 1f } + 750.0
        if (positionMs - previous.positionMs > plausibleAdvance) return null
        return previous.track
    }
}
