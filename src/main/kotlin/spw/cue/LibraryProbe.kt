package spw.cue

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal data class ProbeResult(
    val sheet: CueSheet,
    val matches: Map<Path, MediaItem>,
    val current: MediaItem?
)

internal data class AlbumCue(val tracks: List<CueTrack>, val durationMs: Long) {
    val trackDurationLabels = tracks.map { track ->
        val sourceEnd = durationMs.takeIf { it > 0 } ?: track.endMs ?: 0L
        val window = CuePlaybackWindow.from(track, sourceEnd)
        window?.let { formatCueTime(it.durationMs) } ?: "—"
    }
    val timeColumnChars = maxOf(8, trackDurationLabels.maxOfOrNull(String::length) ?: 0)
}

internal object LibraryProbe {
    fun inspect(cue: Path): ProbeResult = inspect(CueParser.read(cue), current())

    fun current(): MediaItem? = WorkshopApi.playback.getCurrentMediaItem()

    fun findAlbumCue(trackId: String): AlbumCue {
        val item = WorkshopApi.library.getTrackById(trackId)
            .toCompletableFuture().get(10, TimeUnit.SECONDS)
        check(item != null && item.id == trackId) { "公开曲库未找到对应整轨" }
        val audio = checkNotNull(pathOf(item)) { "公开曲库未返回可用的本地文件路径" }
        val tracks = CueDiscovery.find(audio)?.sheet?.tracks?.filter { it.audio == audio }.orEmpty()
        return AlbumCue(tracks, item.duration)
    }

    fun inspect(sheet: CueSheet, current: MediaItem?): ProbeResult {
        val currentPath = pathOf(current)
        val pending = sheet.tracks.mapTo(mutableSetOf()) { it.audio }
        val matches = mutableMapOf<Path, MediaItem>()
        if (currentPath != null && current != null && current.id.isNotBlank() && pending.remove(currentPath)) {
            matches[currentPath] = current
        }
        var afterId: String? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (pending.isNotEmpty()) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "读取原生曲库超时" }
            val page = WorkshopApi.library.getTracks(afterId, 256)
                .toCompletableFuture().get(remaining, TimeUnit.NANOSECONDS)
            for (item in page) {
                val path = pathOf(item) ?: continue
                if (pending.remove(path)) matches[path] = item
            }
            if (page.size < 256) break
            val nextId = page.last().id
            check(nextId.isNotBlank() && nextId != afterId) { "原生曲库分页游标异常" }
            afterId = nextId
        }
        return ProbeResult(sheet, matches, current)
    }

    fun pathOf(item: MediaItem?): Path? = localPath(item?.path)

    fun localPath(value: String?): Path? {
        val path = value?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val local = if (path.startsWith("file:", ignoreCase = true)) Path.of(URI(path)) else Path.of(path)
            local.takeIf { it.isAbsolute }?.normalize()
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: URISyntaxException) {
            null
        }
    }

    fun playAlbumTrack(
        track: CueTrack, albumDurationMs: Long, active: () -> Boolean,
        playAt: ((position: Long, switchSource: Boolean) -> Unit)?
    ) {
        val playback = WorkshopApi.playback
        val current = playback.getCurrentMediaItem()
        val sameFile = pathOf(current) == track.audio
        val duration = current?.duration?.takeIf { sameFile && it > 0 } ?: albumDurationMs
        check(track.startMs in 0 until duration) { "分轨起点超出整轨时长" }
        check(active() && !Thread.currentThread().isInterrupted) { "分轨播放已取消" }
        if (playAt != null) {
            playAt(track.startMs, !sameFile)
        } else {
            check(sameFile) { "请先使用专辑播放按钮启动整轨，再点击分轨" }
            playback.seekTo(track.startMs)
        }
    }

    fun seek(track: CueTrack, expectedId: String?, active: () -> Boolean): Long {
        val playback = WorkshopApi.playback
        val current = playback.getCurrentMediaItem()
        check(canSeek(track, current) && current?.id == expectedId) {
            "当前播放项已改变，或分轨起点超出时长。请播放对应整轨后重新查找 CUE"
        }
        check(active() && !Thread.currentThread().isInterrupted) { "验证已取消" }
        val requestedAt = System.nanoTime()
        playback.seekTo(track.startMs)
        return requestedAt
    }

    fun canSeek(track: CueTrack, current: MediaItem?): Boolean =
        current != null && current.id.isNotBlank() && track.audio == pathOf(current) &&
            track.startMs < current.duration
}
