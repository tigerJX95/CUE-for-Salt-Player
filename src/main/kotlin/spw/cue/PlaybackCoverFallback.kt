package spw.cue

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.awt.image.BufferedImage
import java.lang.reflect.Modifier

internal class PlaybackCoverFallback(
    private val active: () -> Boolean,
    private val report: (String) -> Unit
) : AutoCloseable {
    private val playback = HostCurrentPlayback()
    private val host = HostArtwork()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var published: Published? = null

    init {
        scope.launch {
            playback.current.collectLatest { item ->
                guarded {
                    clearPublished()
                    if (item != null && active()) {
                        follow(item)
                    } else if (active()) {
                        report("没有播放项")
                    }
                }
            }
        }
    }

    private suspend fun follow(item: Any) {
        val audio = LibraryProbe.localPath(playback.path.invoke(item) as? String)
        if (audio == null) {
            report("当前播放路径不可用")
            return
        }
        val service = host.service()
        val lock = host.lock.get(service)
        var loaded = false
        var image: BufferedImage? = null
        report("正在检查播放器封面状态")

        // Give the native loader its first opportunity, then react to its published image
        // instead of depending on the completion callback of a particular suspend function.
        delay(300)
        host.events.onStart { emit(null) }.collect {
            guarded {
                if (!active() || playback.current.value !== item) return@guarded
                val owner = synchronized(lock) { host.owner.get(service) }
                if (owner != null) {
                    report(if (published?.image === owner) "已补齐同目录封面" else "已保留播放器原生封面")
                    return@guarded
                }
                if (!loaded) {
                    report("原生封面缺失，正在查找同目录图片")
                    image = runInterruptible(Dispatchers.IO) {
                        if (CueDiscovery.find(audio) == null) null else FolderCover.load(audio)
                    }
                    loaded = true
                }
                val cover = image
                if (cover == null) {
                    report("当前音频没有匹配的 CUE 或同目录封面")
                    return@guarded
                }
                synchronized(lock) {
                    if (!active() || playback.current.value !== item || host.owner.get(service) != null) {
                        return@synchronized
                    }
                    val fallback = host.createOwner(cover.toComposeImageBitmap())
                    try {
                        host.publish(service, fallback)
                    } catch (error: Throwable) {
                        try {
                            host.publish(service, null)
                        } finally {
                            fallback.close()
                        }
                        throw error
                    }
                    published = Published(service, fallback)
                    report("已补齐同目录封面")
                }
            }
        }
    }

    private fun clearPublished() {
        val previous = published ?: return
        published = null
        synchronized(host.lock.get(previous.service)) {
            if (host.owner.get(previous.service) === previous.image) {
                try {
                    host.publish(previous.service, null)
                } finally {
                    previous.image.close()
                }
            }
        }
    }

    private inline fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            report("播放封面处理失败：${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}")
        } catch (error: LinkageError) {
            report("播放封面接口不兼容：${error.javaClass.simpleName}")
        }
    }

    override fun close() {
        scope.cancel()
        // Serialize release with the host's playback-state changes.
        CoroutineScope(Dispatchers.Main).launch { guarded(::clearPublished) }
    }

    private data class Published(val service: Any, val image: AutoCloseable)

    private class HostArtwork {
        private val playbackService = HostCatalog.type("com.xuncorp.voxzen.service.PlaybackService")
        val owner = playbackService.getDeclaredField("imageBitmapOwner").also { check(it.trySetAccessible()) }
        val lock = playbackService.getDeclaredField("imageBitmapOwnerLock").also {
            check(it.type == Any::class.java && it.trySetAccessible())
        }
        private val controller = HostCatalog.type("com.xuncorp.voxzen.service.PlaybackController")
        private val serviceField = controller.declaredFields.single {
            Modifier.isStatic(it.modifiers) && it.type == playbackService
        }.also { check(it.trySetAccessible()) }
        private val monitor = HostCatalog.type("com.xuncorp.voxzen.service.PlaybackMonitor")
        private val monitorInstance = monitor.getField("INSTANCE").get(null)
        private val monitorMethods = HostCatalog.type(monitor.name + "Kt")
        val events = monitorMethods.getMethod("getImageBitmap", monitor).invoke(null, monitorInstance) as SharedFlow<*>
        private val update = monitorMethods.getMethod("updateImageBitmap", monitor, owner.type)
        private val cache = HostCatalog.type("com.xuncorp.voxzen.image.ScaledImageBitmapCacheKt")
            .getMethod("getSharedScaledImageBitmapCache")
        private val onSourceChanged = cache.returnType.getMethod("onSourceChanged", owner.type)
        private val ownerConstructor = owner.type.getConstructor(org.jetbrains.skia.Bitmap::class.java)

        init {
            check(AutoCloseable::class.java.isAssignableFrom(owner.type))
        }

        fun service(): Any = checkNotNull(serviceField.get(null))

        fun createOwner(image: ImageBitmap): AutoCloseable =
            ownerConstructor.newInstance(image.asSkiaBitmap()) as AutoCloseable

        fun publish(service: Any, image: AutoCloseable?) {
            owner.set(service, image)
            onSourceChanged.invoke(cache.invoke(null), image)
            update.invoke(null, monitorInstance, image)
        }
    }
}
