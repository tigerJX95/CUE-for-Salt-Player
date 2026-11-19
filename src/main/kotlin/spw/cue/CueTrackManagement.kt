package spw.cue

import java.nio.file.Path

internal enum class CueTrackStatusFilter(val label: String) {
    All("全部"), Hidden("已隐藏"), Removed("已移除");

    fun includes(key: String, records: SongVisibilityRecords): Boolean = when (this) {
        All -> true
        Hidden -> key in records.hiddenInSongs
        Removed -> key in records.removedFromLibrary
    }
}

internal sealed interface CueWindowRow {
    data class Disc(val audio: Path, val number: Int, val matchedCount: Int, val totalCount: Int) : CueWindowRow
    data class Track(val index: Int, val track: CueTrack, val key: String) : CueWindowRow
}

internal fun filterManagedCueTracks(
    tracks: List<CueTrack>, keys: List<String>, query: String,
    filter: CueTrackStatusFilter, records: SongVisibilityRecords
): List<IndexedValue<CueTrack>> =
    filterCueTracks(tracks, query).filter { filter.includes(keys[it.index], records) }

/** Disc numbers come from the whole sheet, so filtering never renumbers the remaining discs. */
internal fun cueWindowRows(
    tracks: List<CueTrack>, keys: List<String>, visible: List<IndexedValue<CueTrack>>
): List<CueWindowRow> {
    val discs = tracks.groupingBy { it.audio }.eachCount()
    val grouped = visible.groupBy { it.value.audio }
    return buildList {
        discs.entries.forEachIndexed { discIndex, (audio, count) ->
            val matches = grouped[audio].orEmpty()
            if (matches.isEmpty()) return@forEachIndexed
            if (discs.size > 1) add(CueWindowRow.Disc(audio, discIndex + 1, matches.size, count))
            matches.forEach { add(CueWindowRow.Track(it.index, it.value, keys[it.index])) }
        }
    }
}

/** Recovery only applies to checked entries that are still present in the displayed results. */
internal fun cueRecoveryKeys(
    checked: Set<String>, visible: List<IndexedValue<CueTrack>>, keys: List<String>,
    records: SongVisibilityRecords
): Set<String> = cueRecoveryPlan(checked, visible, keys, records).checked

internal data class CueRecoveryPlan(val checked: Set<String>, val visible: Set<String>)

/** Highlighting is for playback navigation. Recovery uses explicit checkboxes or the displayed scope. */
internal fun cueRecoveryPlan(
    checked: Set<String>, visible: List<IndexedValue<CueTrack>>, keys: List<String>, records: SongVisibilityRecords
): CueRecoveryPlan {
    val recoverable = visible.mapNotNullTo(linkedSetOf()) {
        keys[it.index].takeIf { key -> records.label(key) != null }
    }
    return CueRecoveryPlan(recoverable.filterTo(linkedSetOf()) { it in checked }, recoverable)
}

internal fun cueWindowPlayingIndex(tracks: List<CueTrack>, playing: CueTrack?): Int =
    tracks.indexOfFirst { it.matchesPlaybackTrack(playing) }
