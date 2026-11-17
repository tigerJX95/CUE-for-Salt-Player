package spw.cue

import androidx.compose.runtime.mutableStateMapOf

internal class AlbumCueRefresh {
    private val revisions = mutableStateMapOf<String, Long>()
    private var revision = 0L

    fun request(ids: List<String>) {
        if (ids.isEmpty()) return
        revision++
        revisions.putAll(ids.associateWith { revision })
    }

    fun revision(ids: List<String>): Long = ids.maxOfOrNull { revisions[it] ?: 0L } ?: 0L
}
