package spw.cue

import java.nio.file.Path

internal data class CuePlaybackSource(val id: String, val audio: Path?, val media: Any)
internal data class CuePlaybackScope(val sources: List<CuePlaybackSource>, val index: Int)

/** Insert the next disc immediately after the finished item, retaining the other queued audio. */
internal fun followAlbumDisc(
    existing: List<CuePlaybackSource>, fromId: String, fromAudio: Path, target: CuePlaybackSource,
    currentIndex: Int? = null
): CuePlaybackScope? {
    fun CuePlaybackSource.isFinishedDisc() = audio == fromAudio || audio == null && id == fromId
    val finishedIndex = currentIndex?.takeIf { existing.getOrNull(it)?.isFinishedDisc() == true }
        ?: existing.indexOfFirst { it.isFinishedDisc() }
    if (finishedIndex < 0) return null
    val queue = existing.toMutableList()
    val nextDiscIndex = queue.indexOfFirst { it.id == target.id || target.audio != null && it.audio == target.audio }
    val nextDisc = if (nextDiscIndex >= 0) queue.removeAt(nextDiscIndex) else target
    val insertion = if (nextDiscIndex in 0 until finishedIndex) finishedIndex else finishedIndex + 1
    queue.add(insertion, nextDisc)
    return CuePlaybackScope(queue, insertion)
}

/** Prefer the established queue; repair an old one-file queue using the visible page's source scope. */
internal fun cuePlaybackScope(
    existing: List<CuePlaybackSource>, context: List<CuePlaybackSource>, target: CuePlaybackSource
): CuePlaybackScope {
    fun index(sources: List<CuePlaybackSource>): Int = sources.indexOfFirst {
        it.id == target.id || target.audio != null && it.audio == target.audio
    }
    val existingIndex = index(existing)
    if (existing.size > 1 && existingIndex >= 0) return CuePlaybackScope(existing, existingIndex)
    val distinctContext = context.distinctBy { it.audio ?: it.id }
    val contextIndex = index(distinctContext)
    if (contextIndex >= 0 && distinctContext.size > 1) return CuePlaybackScope(distinctContext, contextIndex)
    if (existingIndex >= 0) return CuePlaybackScope(existing, existingIndex)
    return CuePlaybackScope(listOf(target), 0)
}

internal fun cueSourceEdge(tracks: List<CueTrack>, current: CueTrack, durationMs: Long, step: Int): Boolean {
    if (step != -1 && step != 1) return false
    val playable = tracks.filter { CuePlaybackWindow.from(it, durationMs) != null }
    val edge = if (step > 0) playable.lastOrNull() else playable.firstOrNull()
    return edge?.matchesPlaybackTrack(current) == true
}

/** Move by source position, so the current disc's first segment is never mistaken for its successor. */
internal fun nextCueQueueSource(
    entries: List<CueShuffleEntry>, currentSource: Int, direction: Int, mode: String?, removed: Set<String>
): CueShuffleEntry? {
    if (direction != -1 && direction != 1 || mode !in setOf("Circle", "CircleEnd")) return null
    val available = entries.filter { it.track == null || it.key !in removed }
        .groupBy { it.sourceIndex }.toSortedMap()
    val candidate = if (direction > 0) available.keys.firstOrNull { it > currentSource }
        else available.keys.lastOrNull { it < currentSource }
    val target = candidate ?: if (mode == "Circle") {
        if (direction > 0) available.keys.firstOrNull() else available.keys.lastOrNull()
    } else null
    if (target == null) return null
    val tracks = available[target].orEmpty()
    return if (direction > 0) tracks.firstOrNull() else tracks.lastOrNull()
}
