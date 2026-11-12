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
    fun playAt(track: AlbumHookTarget.Track, position: Long, switchSource: Boolean) {
        val controller = instance.get(null)
        if (switchSource) {
            val media = checkNotNull(converter.invoke(null, track.entity))
            setQueue.invoke(controller, media)
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
