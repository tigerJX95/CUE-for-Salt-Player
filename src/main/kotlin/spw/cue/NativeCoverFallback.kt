package spw.cue

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.lang.classfile.ClassFile
import java.nio.file.Path
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.invoke.MethodType
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JvmModifier
import java.lang.reflect.ParameterizedType
import java.io.IOException

@OptIn(UnstableSpwWorkshopApi::class)
internal class NativeCoverFallback private constructor(
    val render: Method,
    private val albumHeader: Header?,
    private val gridCaller: String?,
    private val artistCaller: String?,
    private val songCaller: String?,
    private val platformPath: Method?
) {
    private data class Header(val entry: Method, val body: Method, val decorator: Class<*>, val groups: Field)

    private val localSources = staticCompositionLocalOf<List<AlbumCueSource>> { emptyList() }
    private val localTrackIds = staticCompositionLocalOf<List<String>> { emptyList() }
    private val localArtist = staticCompositionLocalOf<CatalogCoverQuery?> { null }
    private val artistCovers = CatalogCueCover()

    fun install(
        active: () -> Boolean, trackIds: (List<*>) -> List<List<String>>, refresh: AlbumCueRefresh,
        artwork: () -> NativeAlbumCue.Artwork?,
        report: (String) -> Unit, reportStatistics: (String) -> Unit
    ): AutoCloseable {
        val header = checkNotNull(albumHeader) { "未找到专辑详情封面调用" }
        val registrar = WorkshopApi.hookRegistrar
        val caller = header.entry.declaringClass
        HostCatalog.requireSingleCall(caller, render)
        HostCatalog.requireSingleCall(header.decorator, header.entry)
        val imageHook = registrar.replaceComposable(
            render.declaringClass.name, render.name, caller.name, render.composeParameters(), 50
        ) { call ->
            cover(call, active, refresh, artwork, report)
        }
        val statistics = try {
            AlbumHeaderStatistics.create(header.body).also {
                reportStatistics("等待识别分轨")
            }
        } catch (error: Exception) {
            reportStatistics("沿用原生计数（${error.javaClass.simpleName}）")
            null
        } catch (error: LinkageError) {
            reportStatistics("宿主接口不兼容，沿用原生计数")
            null
        }
        val headerHook = try {
            registrar.replaceComposable(
                caller.name, header.entry.name, header.decorator.name, header.entry.composeParameters(), 50
            ) { call ->
                albumHeader(call, header, trackIds, active, refresh, report, statistics, reportStatistics)
            }
        } catch (error: Throwable) {
            runCatching { imageHook.close() }
            throw error
        }
        return AutoCloseable {
            try {
                headerHook.close()
            } finally {
                imageHook.close()
            }
        }
    }

    fun installGrid(active: () -> Boolean, report: (String) -> Unit): AutoCloseable {
        val album = HostCatalog.type("com.xuncorp.voxzen.data.entity.Album")
        val title = album.getMethod("getTitle")
        val artist = album.getMethod("getArtist")
        return installCatalogContext(checkNotNull(gridCaller) { "未找到专辑卡片封面调用" },
            "AlbumCover (AlbumCover.kt:", album, listOf(Boolean::class.javaPrimitiveType!!), active, report
        ) { item -> CatalogCoverQuery(artist.invoke(item) as String, true, title.invoke(item) as String) }
    }

    fun installArtists(active: () -> Boolean, report: (String) -> Unit): AutoCloseable {
        val artist = HostCatalog.type("com.xuncorp.voxzen.data.entity.Artist")
        val name = artist.getMethod("getName")
        val albumArtist = artist.getMethod("isAlbumArtist")
        return installCatalogContext(checkNotNull(artistCaller) { "未找到艺术家头像封面调用" },
            "ArtistCoverImage (ArtistCoverImage.kt:", artist, emptyList(), active, report
        ) { item -> CatalogCoverQuery(name.invoke(item) as String, albumArtist.invoke(item) as Boolean) }
    }

    private fun installCatalogContext(
        callerName: String, marker: String, entity: Class<*>, extraTypes: List<Class<*>>,
        active: () -> Boolean, report: (String) -> Unit, queryFor: (Any) -> CatalogCoverQuery
    ): AutoCloseable {
        val caller = HostCatalog.type(callerName)
        val entry = HostCatalog.tracedMethods(marker).single {
            JvmModifier.isPublic(it.modifiers) && JvmModifier.isStatic(it.modifiers) &&
                it.returnType == Void.TYPE && it.parameterTypes.toList() ==
                    listOf(entity, Modifier::class.java) + extraTypes + listOf(
                        Composer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }
        check(entry.declaringClass == caller)
        val callers = HostCatalog.callersOf(entry)
        check(callers.isNotEmpty()) { "未找到艺术家头像上下文调用" }
        val hooks = mutableListOf<AutoCloseable>()
        try {
            for (owner in callers) {
                HostCatalog.requireSingleCall(owner, entry)
                hooks += WorkshopApi.hookRegistrar.replaceComposable(
                    caller.name, entry.name, owner.name, entry.composeParameters(), 50
                ) { call ->
                    val item = call.arguments.firstOrNull()
                    val query = if (item != null && active()) runCatching {
                        queryFor(item)
                    }.getOrNull() else null
                    CompositionLocalProvider(localArtist provides query) {
                        val defaults = if (call.arguments.getOrNull(1) == null) 1 shl 1 else 0
                        invoke(entry, call.arguments, currentComposer, defaults)
                    }
                }
            }
            hooks += installFileCover(caller.name, active, report, useArtistArtwork = true)
        } catch (error: Throwable) {
            hooks.asReversed().forEach { runCatching { it.close() } }
            throw error
        }
        return AutoCloseable { hooks.asReversed().forEach { runCatching { it.close() } } }
    }

    fun installSongs(active: () -> Boolean, report: (String) -> Unit): AutoCloseable =
        installFileCover(checkNotNull(songCaller) { "未找到歌曲封面调用" }, active, report, useSongArtwork = true)

    private fun installFileCover(
        caller: String, active: () -> Boolean, report: (String) -> Unit, useSongArtwork: Boolean = false,
        useArtistArtwork: Boolean = false
    ): AutoCloseable {
        val getVri = render.parameterTypes[0].getMethod("getVri")
        val toPlatformPath = platformPath
        check(useSongArtwork || toPlatformPath != null) { "未找到宿主音频路径转换器" }
        HostCatalog.requireSingleCall(HostCatalog.type(caller), render)
        return WorkshopApi.hookRegistrar.replaceComposable(
            render.declaringClass.name, render.name, caller, render.composeParameters(), 50
        ) { call ->
            val model = call.arguments.firstOrNull()
            val songArtwork = if (useSongArtwork && active()) LocalCueSongArtwork.current else null
            val artistQuery = if (useArtistArtwork && active()) localArtist.current else null
            val path = if (songArtwork == null && active() && model != null && toPlatformPath != null) runCatching {
                LibraryProbe.localPath(toPlatformPath.invoke(null, getVri.invoke(model)) as? String)
            }.getOrNull() else null
            coverWithPlaceholder(call, active(), { found ->
                if (found && active()) report("已绘制同目录封面")
            }) {
                when {
                    !active() -> null
                    songArtwork != null -> songArtwork.image()
                    artistQuery != null -> key(artistQuery, path, model) { artistArtwork(artistQuery, path, active, report) }
                    path != null -> key(path, model) { gridArtwork(path, report) }
                    else -> {
                        SideEffect { report("原生无封面，宿主音频路径不可用") }
                        null
                    }
                }
            }
        }
    }

    @Composable
    private fun artistArtwork(
        query: CatalogCoverQuery, path: Path?, active: () -> Boolean, report: (String) -> Unit
    ): ImageBitmap? = produceState<ImageBitmap?>(null, query, path) {
        try {
            val image = runInterruptible(gridReads) {
                val direct = try {
                    path?.takeIf { CueDiscovery.find(it) != null }?.let { FolderCover.loadCatalog(it) }
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                } catch (_: IllegalStateException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
                (direct ?: artistCovers.load(query, path, active))?.toComposeImageBitmap()
            }
            if (active()) {
                value = image
                report(if (image == null) "艺术家关联的 CUE 未找到可读取封面" else "已读取艺术家关联的 CUE 专辑封面")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (active()) report("读取艺术家封面失败：${error.javaClass.simpleName}")
        }
    }.value

    @Composable
    private fun gridArtwork(path: Path, report: (String) -> Unit): ImageBitmap? =
        produceState<ImageBitmap?>(null, path) {
            report("原生无封面，正在查找同目录 CUE 和图片")
            try {
                val (image, status) = runInterruptible(gridReads) {
                    if (CueDiscovery.find(path) == null) {
                        null to "路径已解析，未找到匹配的同目录 CUE"
                    } else {
                        val cover = FolderCover.loadCatalog(path)?.toComposeImageBitmap()
                        cover to if (cover == null) "CUE 已匹配，未找到可读取的同目录封面" else "已读取同目录封面"
                    }
                }
                value = image
                report(status)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                report("查找同目录封面失败：${error.javaClass.simpleName}")
            }
        }.value

    @Composable
    private fun albumHeader(
        call: UiHookCall, header: Header, trackIds: (List<*>) -> List<List<String>>, active: () -> Boolean,
        refresh: AlbumCueRefresh,
        report: (String) -> Unit, statistics: AlbumHeaderStatistics?, reportStatistics: (String) -> Unit
    ) {
        val groups = call.arguments.firstOrNull()?.let { state ->
            (header.groups.get(state) as? List<*>)?.let(trackIds)
        }.orEmpty()
        val ids = groups.flatten()
        val sources = key(ids, refresh.revision(ids)) {
            produceState<List<AlbumCueSource>>(emptyList()) {
                try {
                    val loaded = runInterruptible(gridReads) {
                        LibraryProbe.findAlbumCueSources(ids, active)
                    }
                    if (active()) value = loaded
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (active()) report("专辑 CUE 读取失败：${error.javaClass.simpleName}")
                }
            }.value
        }
        val counts = albumHeaderCount(groups, sources.associate {
            it.trackId to CueRemoval.visibleAlbum(it.cue).tracks.size
        })
        val state = call.arguments.firstOrNull()
        val displayState = remember(state, counts, statistics, active()) {
            if (state == null || counts == null || statistics == null || !active()) null
            else runCatching { statistics.stateWithCount(state, counts.tracks) }
        }
        val projected = displayState?.getOrNull()
        val arguments = if (projected == null) call.arguments else listOf(projected) + call.arguments.drop(1)
        CompositionLocalProvider(localTrackIds provides ids, localSources provides sources) {
            invoke(header.entry, arguments, currentComposer)
        }
        SideEffect {
            if (active() && statistics != null) {
                val status = when {
                    projected != null && counts != null -> "已应用 · ${counts.discs} 碟 · ${counts.tracks} 轨"
                    displayState?.isFailure == true -> "无法构建统计视图，沿用原生计数"
                    else -> "未识别到可统计的分轨，沿用原生计数"
                }
                reportStatistics(status)
            }
        }
    }

    @Composable
    private fun cover(
        call: UiHookCall, active: () -> Boolean, refresh: AlbumCueRefresh,
        artwork: () -> NativeAlbumCue.Artwork?, report: (String) -> Unit
    ) {
        val ids = localTrackIds.current
        if (ids.isEmpty() && active()) SideEffect { report("已进入封面绘制，未关联整轨") }
        coverWithPlaceholder(call, ids.isNotEmpty(), { found ->
            if (active()) report(if (found) "已绘制专辑目录封面" else "原生封面缺失，等待匹配的目录图片")
        }) {
            when {
                !active() -> null
                ids.size > 1 -> multiDiscArtwork(ids, active)
                else -> artwork()?.takeIf { it.trackId == ids.singleOrNull() && it.revision == refresh.revision(ids) }?.image
            }
        }
    }

    @Composable
    private fun multiDiscArtwork(ids: List<String>, active: () -> Boolean): ImageBitmap? =
        key(ids, localSources.current) {
            val sources = localSources.current
            produceState<ImageBitmap?>(null) {
                if (sources.isEmpty() || !active()) return@produceState
                try {
                    value = runInterruptible(gridReads) {
                        FolderCover.loadMultiDisc(sources.map { it.audio })?.toComposeImageBitmap()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Preserve the host placeholder when a library source or image is unavailable.
                }
            }.value
        }

    @Composable
    private fun coverWithPlaceholder(
        call: UiHookCall, useFallback: Boolean, onFallback: (Boolean) -> Unit,
        imageFor: @Composable () -> ImageBitmap?
    ) {
        val args = call.arguments.toMutableList()
        val placeholder = args[5]
        // The native placeholder implements several Function interfaces.
        if (useFallback && Function3::class.java.isInstance(placeholder)) {
            val fallback: @Composable BoxScope.() -> Unit = {
                val image = imageFor()
                if (image != null) {
                    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    invokePlaceholder(placeholder, this, currentComposer)
                }
                SideEffect { onFallback(image != null) }
            }
            args[5] = fallback
        }
        val defaultScale = if (args[4] == null) 1 shl 4 else 0
        invoke(render, args, currentComposer, defaults = defaultScale)
    }

    private fun invokePlaceholder(placeholder: Any?, scope: BoxScope, composer: Composer) {
        try {
            placeholderInvoke.invoke(placeholder, scope, composer, 0)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun invoke(method: Method, args: List<Any?>, composer: Composer, defaults: Int? = null) {
        val tail = if (defaults != null) listOf(composer, 0, defaults) else listOf(composer, 0)
        try {
            method.invoke(null, *(args + tail).toTypedArray())
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun Method.composeParameters(): List<String> =
        parameterTypes.takeWhile { it != Composer::class.java }.map { it.name }

    companion object {
        private val gridReads = Dispatchers.IO.limitedParallelism(2)
        private val placeholderInvoke = Function3::class.java.getMethod(
            "invoke", Any::class.java, Any::class.java, Any::class.java
        )

        fun resolve(
            decorator: Class<*>, groupType: ParameterizedType,
            gridCovers: List<String>, songCovers: List<String>, artistCovers: List<String>
        ): NativeCoverFallback? = try {
            val model = HostCatalog.type("com.xuncorp.voxzen.image.AudioCover")
            val owner = HostCatalog.type("com.xuncorp.voxzen.image.AudioCoverImageKt")
            val render = owner.declaredMethods.single {
                val types = it.parameterTypes
                it.name == "AudioCoverImage" && it.returnType == Void.TYPE && JvmModifier.isStatic(it.modifiers) &&
                    types.size == 9 && types[0] == model && types[5] == Function3::class.java &&
                    types[6] == Composer::class.java && types[7] == Int::class.javaPrimitiveType &&
                    types[8] == Int::class.javaPrimitiveType
            }
            val path = resolvePlatformPath(model.getMethod("getVri").returnType)
            val header = runCatching { resolveHeader(decorator, render, groupType) }.getOrNull()
            NativeCoverFallback(render, header, gridCovers.singleOrNull(), artistCovers.singleOrNull(), songCovers.singleOrNull(), path)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }

        private fun resolvePlatformPath(vri: Class<*>): Method? = runCatching {
            val signature = MethodType.methodType(String::class.java, vri).descriptorString()
            HostCatalog.calls(HostCatalog.mediaConverter).filter {
                it.type().stringValue() == signature
            }.map(HostCatalog::resolve).distinct().single {
                JvmModifier.isStatic(it.modifiers) && it.returnType == String::class.java
            }
        }.getOrNull()

        private fun resolveHeader(decorator: Class<*>, render: Method, groupType: ParameterizedType): Header? {
            val owner = decorator.enclosingClass ?: return null
            val resource = "/${owner.name.replace('.', '/')}.class"
            val bytes = owner.getResourceAsStream(resource)?.use { it.readNBytes(262_145) } ?: return null
            check(bytes.size <= 262_144)
            val renderOwner = render.declaringClass.name.replace('.', '/')
            val renderType = render.hostDescriptor()
            val caller = ClassFile.of().parse(bytes).methods().singleOrNull { method ->
                method.code().orElse(null)?.elementList()?.filterIsInstance<InvokeInstruction>()?.any {
                    it.owner().asInternalName() == renderOwner &&
                        it.name().stringValue() == render.name && it.type().stringValue() == renderType
                } == true
            } ?: return null
            val header = owner.declaredMethods.single {
                JvmModifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                    it.name == caller.methodName().stringValue() && it.hostDescriptor() == caller.methodType().stringValue()
            }
            val state = header.parameterTypes.first()
            val groups = state.declaredFields.singleOrNull { field ->
                val type = field.genericType as? ParameterizedType
                type?.rawType == List::class.java && type.actualTypeArguments.singleOrNull() == groupType
            } ?: return null
            if (header.parameterTypes.drop(1) != listOf(Function2::class.java, Composer::class.java, Int::class.javaPrimitiveType)) return null
            val entry = owner.declaredMethods.singleOrNull {
                JvmModifier.isPublic(it.modifiers) && JvmModifier.isStatic(it.modifiers) &&
                    it.returnType == Void.TYPE && it.parameterTypes.contentEquals(header.parameterTypes)
            } ?: return null
            return Header(entry, header, decorator, groups).takeIf { groups.trySetAccessible() }
        }

    }
}
