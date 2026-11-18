package spw.cue

/** Keep original indices: playback and visibility actions use the complete CUE sheet. */
internal fun filterCueTracks(tracks: List<CueTrack>, query: String): List<IndexedValue<CueTrack>> {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return tracks.withIndex().filter { (_, track) ->
        terms.all { term ->
            track.title.contains(term, ignoreCase = true) ||
                track.performer?.contains(term, ignoreCase = true) == true
        }
    }
}
