package spw.cue

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class NativeAlbumPlayback private constructor(
    private val converter: Method,
    private val instance: Field,
    private val setQueue: Method,
    private val prepare: Method,
    private val seek: Method,
    private val play: Method
) {
    private val queue by lazy { runCatching { HostCueQueue() }.getOrNull() }

    fun playAt(
        track: AlbumHookTarget.Track, position: Long, switchSource: Boolean,
        context: List<AlbumHookTarget.Track> = emptyList()
    ) {
        val controller = instance.get(null)
        val media = checkNotNull(converter.invoke(null, track.entity))
        val audio = LibraryProbe.localPath(HostCatalog.mediaLocation.invoke(media) as? String)
        val target = CuePlaybackSource(track.id, audio, media)
        val currentQueue = runCatching { queue?.snapshot() }.getOrNull()
        val existing = currentQueue?.sources.orEmpty().map { CuePlaybackSource(it.id, it.audio, it.media) }
        val alreadyComplete = existing.size > 1 && existing.any {
            it.id == track.id || audio != null && it.audio == audio
        }
        val pageSources = if (alreadyComplete) emptyList() else context.map { source ->
            val item = checkNotNull(converter.invoke(null, source.entity))
            CuePlaybackSource(source.id, LibraryProbe.localPath(HostCatalog.mediaLocation.invoke(item) as? String), item)
        }
        val selected = cuePlaybackScope(existing, pageSources, target)
        val repairSingleFile = selected.sources.size > 1 && existing.size <= 1
        if (switchSource || repairSingleFile) {
            if (selected.sources.size > 1) {
                instance.type.getMethod("setPlaybackQueue", List::class.java, Int::class.javaPrimitiveType)
                    .invoke(controller, selected.sources.map { it.media }, selected.index)
            } else {
                setQueue.invoke(controller, media)
            }
            prepare.invoke(controller)
        }
        // The host queues this seek and applies it when the new source is ready.
        seek.invoke(controller, position)
        play.invoke(controller)
    }

    companion object {
        fun resolve(): NativeAlbumPlayback? = try {
            val media = HostCatalog.media
            val controller = HostCatalog.type("com.xuncorp.voxzen.service.PlaybackController")
            val converter = HostCatalog.mediaConverter
            val instance = controller.getField("INSTANCE")
            check(Modifier.isStatic(instance.modifiers) && instance.type == controller)
            val setQueue = controller.getMethod("setPlaybackQueue", media)
            val prepare = controller.getMethod("prepare")
            val seek = controller.getMethod("seekToQueued", Long::class.javaPrimitiveType)
            val play = controller.getMethod("play")
            check(listOf(setQueue, prepare, seek, play).all { it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers) })
            NativeAlbumPlayback(converter, instance, setQueue, prepare, seek, play)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }
}
