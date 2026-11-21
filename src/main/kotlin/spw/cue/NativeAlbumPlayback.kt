package spw.cue

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
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
    private val mediaConstructor by lazy { HostCatalog.media.getConstructor(*Array(6) { String::class.java }) }

    fun playAt(
        track: AlbumHookTarget.Track, position: Long, switchSource: Boolean,
        context: List<AlbumHookTarget.Track> = emptyList(), afterDisc: CueAlbumSequence.Disc? = null,
        replaceQueue: Boolean = false
    ) {
        playAt(nativeSource(track), position, switchSource, afterDisc, replaceQueue) { context.map(::nativeSource) }
    }

    fun playbackMode(): String? = queue?.mode()

    fun changeMode(mode: Enum<*>) {
        instance.type.getMethod("changePlaybackMode", mode.declaringJavaClass).invoke(instance.get(null), mode)
    }

    fun playFromWindow(track: MediaItem, position: Long, context: List<MediaItem>) {
        playAt(librarySource(track), position, switchSource = true) { context.map(::librarySource) }
    }

    private fun nativeSource(track: AlbumHookTarget.Track): CuePlaybackSource {
        val media = checkNotNull(converter.invoke(null, track.entity))
        val audio = LibraryProbe.localPath(HostCatalog.mediaLocation.invoke(media) as? String)
        return CuePlaybackSource(track.id, audio, media)
    }

    private fun librarySource(track: MediaItem): CuePlaybackSource {
        val audio = checkNotNull(LibraryProbe.pathOf(track))
        val media = mediaConstructor.newInstance(
            track.id, track.title, track.artist, track.album, track.albumArtist, audio.toString()
        )
        return CuePlaybackSource(track.id, audio, media)
    }

    private fun playAt(
        target: CuePlaybackSource, position: Long, switchSource: Boolean,
        afterDisc: CueAlbumSequence.Disc? = null, replaceQueue: Boolean = false, context: () -> List<CuePlaybackSource>
    ) {
        val controller = instance.get(null)
        val currentQueue = runCatching { queue?.snapshot() }.getOrNull()
        val existing = currentQueue?.sources.orEmpty().map { CuePlaybackSource(it.id, it.audio, it.media) }
        val alreadyComplete = existing.size > 1 && existing.any {
            it.id == target.id || target.audio != null && it.audio == target.audio
        }
        val selected = if (replaceQueue) cuePlaybackScope(emptyList(), context(), target) else afterDisc?.let {
            followAlbumDisc(existing, it.id, it.audio, target, runCatching { queue?.currentIndex() }.getOrNull())
        }
            ?: cuePlaybackScope(existing, if (alreadyComplete) emptyList() else context(), target)
        val repairSingleFile = selected.sources.size > 1 && existing.size <= 1
        if (switchSource || repairSingleFile || replaceQueue) {
            if (selected.sources.size > 1) {
                instance.type.getMethod("setPlaybackQueue", List::class.java, Int::class.javaPrimitiveType)
                    .invoke(controller, selected.sources.map { it.media }, selected.index)
            } else {
                setQueue.invoke(controller, selected.sources[selected.index].media)
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
