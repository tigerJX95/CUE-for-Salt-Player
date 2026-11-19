package spw.cue

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal data class CatalogCoverQuery(val name: String, val albumArtist: Boolean, val album: String? = null)

// Shared, short-lived metadata snapshot. No disk or library work is performed on the UI thread.
@OptIn(UnstableSpwWorkshopApi::class)
internal class CatalogCueCover(
    private val page: (String?, Int, Long) -> List<MediaItem> = { after, limit, timeout ->
        WorkshopApi.library.getTracks(after, limit).toCompletableFuture().get(timeout, TimeUnit.NANOSECONDS)
    },
    private val clock: () -> Long = System::nanoTime
) {
    private var snapshot: List<MediaItem>? = null
    private var expiresAt = 0L

    fun load(query: CatalogCoverQuery, preferred: Path?, active: () -> Boolean): BufferedImage? {
        if (query.name.isBlank() && query.album.isNullOrBlank()) return null
        val matching = sources(active).filter { item ->
            if (query.album != null) {
                item.album == query.album && (query.name.isBlank() ||
                    matchesArtist(query.name, item.albumArtist.ifBlank { item.artist }))
            } else matchesArtist(query.name, if (query.albumArtist) item.albumArtist else item.artist)
        }
        val paths = matching.mapNotNull { item -> LibraryProbe.pathOf(item)?.let { item to it } }
            .distinctBy { it.second }.take(512)
        val cues = CueDiscovery.findAll(paths.map { it.second }, active = active)
        val groups = paths.filter { it.second in cues }.groupBy { (item, audio) ->
            // Blank album tags do not justify grouping unrelated audio folders.
            Triple(item.album, item.albumArtist, if (item.album.isBlank()) audio.parent else null)
        }.values.sortedBy { group -> if (group.any { it.second == preferred }) 0 else 1 }
        for (group in groups) {
            checkActive(active)
            val audio = group.map { it.second }
            val image = if (audio.size > 1) FolderCover.loadMultiDisc(audio) else FolderCover.loadCatalog(audio.single())
            if (image != null) return image
        }
        return null
    }

    @Synchronized
    private fun sources(active: () -> Boolean): List<MediaItem> {
        checkActive(active)
        snapshot?.takeIf { clock() < expiresAt }?.let { return it }
        val deadline = clock() + TimeUnit.SECONDS.toNanos(10)
        val items = mutableListOf<MediaItem>()
        val cursors = mutableSetOf<String>()
        var after: String? = null
        while (true) {
            checkActive(active)
            val remaining = deadline - clock()
            check(remaining > 0) { "读取艺术家封面来源超时" }
            val next = page(after, 256, remaining)
            check(next.size <= 256 && items.size + next.size <= 65_536) { "艺术家封面来源超过读取上限" }
            items += next
            if (next.size < 256) break
            val cursor = next.last().id
            check(cursor.isNotBlank() && cursors.add(cursor)) { "艺术家封面分页游标异常" }
            after = cursor
        }
        checkActive(active)
        return items.toList().also {
            snapshot = it
            expiresAt = clock() + TimeUnit.SECONDS.toNanos(30)
        }
    }

    private fun checkActive(active: () -> Boolean) {
        if (!active() || Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private fun matchesArtist(name: String, value: String): Boolean {
        val target = name.trim()
        if (value.trim() == target) return true
        return value.split(artistSeparators).any { it.trim() == target }
    }

    private companion object {
        val artistSeparators = Regex("[/;；,，、]|\\s+&\\s+")
    }
}
