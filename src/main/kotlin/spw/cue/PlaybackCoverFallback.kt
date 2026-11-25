package spw.cue

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal fun readPlaybackCover(
    audio: Path, cache: FolderCover.DecodedCache? = null, libraryArtwork: () -> ByteArray? = { null }
): BufferedImage? {
    if (CueDiscovery.find(audio) == null) return null
    return FolderCover.loadSong(audio, cache = cache) ?: libraryArtwork()?.let { FolderCover.decodeEmbedded(it, 512) }
}

@OptIn(UnstableSpwWorkshopApi::class)
internal class PlaybackCoverFallback(
    private val active: () -> Boolean,
    private val report: (String) -> Unit
) : AutoCloseable {
    private val playback = HostCurrentPlayback()
    private val host = HostArtwork()
    private val stopped = AtomicBoolean()
    private val transition = PlaybackCoverTransition<BufferedImage>()
    private val folderCovers = FolderCover.DecodedCache()
    private val refresh = MutableStateFlow<Refresh?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var published: Published? = null
    private fun interceptNativeCover(nativeCover: Any?): Boolean {
        if (!running()) return false
        var skip = false
        guarded {
            val service = host.service()
            synchronized(host.lock.get(service)) {
                if (!running()) return@synchronized
                if (nativeCover != null) {
                    transition.nativePublished()
                } else {
                    val item = playback.current.value
                    skip = transition.interceptMissing(item, audioOf(item) != null) { image ->
                        publish(service, checkNotNull(item), image)
                    }
                }
            }
        }
        return skip
    }

    private val monitorHook = try {
        host.interceptMonitor(::interceptNativeCover)
    } catch (failure: Exception) {
        report("已订阅封面状态 · 切歌过渡接口不可用（${failure.javaClass.simpleName}）")
        null
    } catch (failure: LinkageError) {
        report("已订阅封面状态 · 切歌过渡接口不兼容（${failure.javaClass.simpleName}）")
        null
    }

    init {
        scope.launch {
            host.events.collect { owner ->
                if (owner == null && running()) guarded(::restoreAfterNativeClear)
            }
        }
        scope.launch {
            combine(playback.current, refresh) { item, request ->
                item to request?.takeIf { it.item === item }
            }.distinctUntilChanged().collectLatest { (item, request) ->
                guarded {
                    if (!running()) return@guarded
                    if (item == null) clearPublished() else follow(item, refreshCover = request != null)
                }
            }
        }
    }

    fun refreshAlbum(trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val item = playback.current.value ?: return
        scope.launch {
            guarded {
                if (!running() || playback.current.value !== item) return@guarded
                val audio = audioOf(item) ?: return@guarded
                val id = playback.id.invoke(item) as? String
                val matches = id in trackIds || runInterruptible(Dispatchers.IO) {
                    LibraryProbe.albumContainsAudio(trackIds, audio) { running() && playback.current.value === item }
                }
                if (matches && running() && playback.current.value === item) refresh.value = Refresh(item)
            }
        }
    }

    private fun running() = !stopped.get() && active()

    private fun audioOf(item: Any?): Path? =
        item?.let { LibraryProbe.localPath(playback.path.invoke(it) as? String) }

    private fun restoreAfterNativeClear() {
        val service = host.service()
        synchronized(host.lock.get(service)) {
            if (!running() || host.owner.get(service) != null || host.events.replayCache.lastOrNull() != null) return
            val item = playback.current.value ?: return
            transition.restoreAfterNativeClear(item) { publish(service, item, it) }
        }
    }

    private suspend fun follow(item: Any, refreshCover: Boolean) {
        val audio = audioOf(item)
        report("正在查找当前音频的同目录封面")
        val image = try {
            audio?.let {
                runInterruptible(Dispatchers.IO) {
                    if (refreshCover) folderCovers.invalidateSong(it)
                    readPlaybackCover(it, folderCovers) {
                        val id = playback.id.invoke(item) as? String
                        id?.takeIf(String::isNotBlank)?.let { trackId ->
                            WorkshopApi.library.getCoverById(trackId).toCompletableFuture().get(5, TimeUnit.SECONDS)
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (refreshCover) {
                if (playback.current.value === item) report("刷新播放封面失败：${error.javaClass.simpleName}")
                return
            }
            null
        } catch (error: LinkageError) {
            if (refreshCover) {
                if (playback.current.value === item) report("刷新播放封面接口不兼容：${error.javaClass.simpleName}")
                return
            }
            null
        }
        val service = host.service()
        synchronized(host.lock.get(service)) {
            if (!running()) return
            val owner = host.owner.get(service)
            val fallback = ownedFallback(service, owner)
            val replacingFallback = refreshCover && fallback != null
            val keepOwner = owner != null && !replacingFallback
            if (!transition.complete(item, playback.current.value, image, keepOwner, replacingFallback) {
                    publish(service, item, it)
                }) return
            if (keepOwner) {
                report(if (fallback != null) "已补齐播放封面" else "已保留播放器原生封面")
            } else if (image == null) {
                report(if (audio == null) "当前播放路径不可用" else "当前音频没有匹配的 CUE 或目录封面")
            }
        }
    }

    // Called under the cover lock. The native loader closes the old owner after its callback.
    // A refresh that replaces a fallback still installed in the owner slot releases it here.
    private fun publish(service: Any, item: Any, image: BufferedImage?) {
        val fallback = image?.let { host.createOwner(it.toComposeImageBitmap()) }
        if (!running() || playback.current.value !== item) {
            fallback?.close()
            return
        }
        val previous = ownedFallback(service, host.owner.get(service))
        try {
            host.publish(service, fallback)
            published = fallback?.let { Published(service, it) }
        } catch (error: Throwable) {
            if (fallback != null) {
                try {
                    host.publish(service, null)
                } finally {
                    fallback.close()
                }
            }
            throw error
        } finally {
            if (previous != null && host.owner.get(service) !== previous.image) {
                if (published === previous) published = null
                previous.image.close()
            }
        }
        if (fallback != null) report("已补齐播放封面")
    }

    private fun ownedFallback(service: Any, owner: Any?): Published? =
        published?.takeIf { it.service === service && it.image === owner }

    private fun clearPublished() {
        val service = host.service()
        synchronized(host.lock.get(service)) {
            if (!stopped.get() && playback.current.value != null) return
            val deferred = transition.reset()
            val previous = published
            published = null
            val owner = host.owner.get(service)
            if (previous != null && previous.service === service && owner === previous.image) {
                try {
                    host.publish(service, null)
                } finally {
                    previous.image.close()
                }
            } else if (deferred && owner == null) {
                host.publish(service, null)
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
        if (!stopped.compareAndSet(false, true)) return
        scope.cancel()
        // Serialize release with the host's playback-state changes.
        try {
            monitorHook?.close()
        } finally {
            CoroutineScope(Dispatchers.Main).launch { guarded(::clearPublished) }
        }
    }

    private data class Published(val service: Any, val image: AutoCloseable)
    private class Refresh(val item: Any)

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
            check(Modifier.isStatic(update.modifiers) && update.returnType == Void.TYPE)
        }

        fun interceptMonitor(intercept: (Any?) -> Boolean): AutoCloseable =
            WorkshopApi.hookRegistrar.hookMethod(
                update.declaringClass.name, update.name, update.parameterTypes.map { it.name }, 50
            ) {
                before { call ->
                    if (!call.isOriginalSkipped && intercept(call.args[1])) call.setResult(null)
                }
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
