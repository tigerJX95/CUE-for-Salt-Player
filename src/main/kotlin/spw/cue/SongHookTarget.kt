package spw.cue

import androidx.compose.runtime.Composer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

internal class SongHookTarget private constructor(
    val filter: Method,
    val stateFlow: Method,
    val listBuilder: Method,
    val itemBatch: Method,
    val playback: NativeAlbumPlayback,
    val selection: NativeSongSelection?,
    private val songState: Constructor<*>,
    private val stateAccessors: List<Method>,
    private val group: Constructor<*>,
    private val groupKey: Field,
    private val groupTracks: Field,
    private val sourceId: Method,
    private val sourceTitle: Method,
    private val sourceArtist: Method,
    private val sourceAlbumArtist: Method,
    private val sourceAlbum: Method,
    private val sourcePath: Method,
    private val sourceDuration: Method,
    private val sourceCopy: Method
) {
    fun id(entity: Any): String = sourceId.invoke(entity) as String
    fun path(entity: Any): String? = sourcePath.invoke(entity) as? String

    fun audioPath(entity: Any): Path? {
        val raw = path(entity)
        val parsed = LibraryProbe.localPath(raw)
        if (parsed != null && raw?.startsWith("file:", ignoreCase = true) != true) return parsed
        // Salt's file addresses can contain literal spaces and brackets. Use its playback conversion.
        val media = HostCatalog.mediaConverter.invoke(null, entity)
        val native = LibraryProbe.localPath(HostCatalog.mediaLocation.invoke(media) as? String)
        return if (native != null && (parsed == null || native == parsed || Files.isRegularFile(native))) native else parsed
    }

    fun duration(entity: Any): Long = sourceDuration.invoke(entity) as Long
    fun album(entity: Any): String? = (sourceAlbum.invoke(entity) as? String)?.takeIf { it.isNotBlank() }
    fun artist(entity: Any): String? =
        (sourceArtist.invoke(entity) as? String).artistName() ?: (sourceAlbumArtist.invoke(entity) as? String).artistName()

    fun groupEntities(state: Any): List<*> {
        check(songState.declaringClass.isInstance(state)) { "歌曲筛选返回类型不匹配" }
        return stateAccessors.first().invoke(state) as List<*>
    }

    fun tracksIn(value: Any): List<*> = groupTracks.get(value) as List<*>

    fun count(state: Any): Int = stateAccessors[2].invoke(state) as Int

    @Suppress("UNCHECKED_CAST")
    fun mutablePageState(source: StateFlow<*>): MutableStateFlow<Any>? {
        if (source is MutableStateFlow<*>) return source as MutableStateFlow<Any>
        // The verified host getter returns the read-only view created by stateIn.
        // Keep the getter and the wrapper intact and use the existing StateFlow API to publish.
        if (source.javaClass.name != "kotlinx.coroutines.flow.ReadonlyStateFlow") return null
        val field = source.javaClass.declaredFields.singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.type == StateFlow::class.java
        } ?: return null
        if (!field.trySetAccessible()) return null
        val delegate = field.get(source) as? MutableStateFlow<*> ?: return null
        return delegate as MutableStateFlow<Any>
    }

    fun projection(original: Any, groups: List<Any>, count: Int): Any {
        check(songState.declaringClass.isInstance(original) && count >= 0)
        val args = stateAccessors.map { it.invoke(original) }.toTypedArray()
        args[0] = groups
        args[2] = count
        return songState.newInstance(*args).also {
            check(groupEntities(it) === groups && count(it) == count) { "宿主状态构造未生效" }
        }
    }

    fun probeFilter() {
        val sort = songState.parameterTypes[3].enumConstants.first()
        val result = filterTracks(emptyList(), sort, false, "")
        check(count(result) == 0) { "空列表未返回有效的歌曲页状态" }
    }

    fun filterTracks(tracks: List<Any>, sort: Any, grouped: Boolean, query: String): Any {
        val constructor = filter.declaringClass.getDeclaredConstructor(Continuation::class.java)
        check(constructor.trySetAccessible() && filter.trySetAccessible())
        val receiver = constructor.newInstance(null)
        val completion = object : Continuation<Any?> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) {
                error("歌曲筛选意外挂起")
            }
        }
        return checkNotNull(filter.invoke(receiver, tracks, sort, grouped, query, completion)) {
            "歌曲筛选未返回状态"
        }
    }

    fun probeList() {
        val types = listBuilder.parameterTypes
        val scope = types.last()
        val arguments = Array<Any?>(types.size) { index ->
            when (types[index]) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }
        arguments[0] = emptyList<Any>()
        arguments[arguments.lastIndex] = Proxy.newProxyInstance(scope.classLoader, arrayOf(scope)) { _, _, _ -> null }
        check(listBuilder.trySetAccessible())
        check(listBuilder.invoke(null, *arguments) == Unit) { "空列表构建未正常返回" }
    }

    fun replaceTracks(value: Any, tracks: List<Any>): Any = group.newInstance(groupKey.get(value), tracks)

    fun trackForFilter(entity: Any, id: String, track: CueTrack, artist: String, duration: Long): Any {
        val args = Array<Any?>(sourceCopy.parameterCount) { index ->
            when (sourceCopy.parameterTypes[index]) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                else -> null
            }
        }
        args[0] = entity
        args[1] = id
        args[2] = track.title
        args[3] = artist
        args[8] = track.number
        args[18] = duration
        val replaced = (1 shl 0) or (1 shl 1) or (1 shl 2) or (1 shl 7) or (1 shl 17)
        args[args.lastIndex - 1] = ((1 shl 23) - 1) and replaced.inv()
        val clone = checkNotNull(sourceCopy.invoke(null, *args))
        check(id(clone) == id && sourceTitle.invoke(clone) == track.title && sourceArtist.invoke(clone) == artist &&
            sourceDuration.invoke(clone) == duration && path(clone) == path(entity)) {
            "歌曲元数据布局不匹配"
        }
        return clone
    }

    companion object {
        fun resolve(): SongHookTarget {
            val track = HostCatalog.track
            val composer = Composer::class.java
            val listType = List::class.java
            val bool = Boolean::class.javaPrimitiveType
            val int = Int::class.javaPrimitiveType

            val screen = HostCatalog.tracedMethods("TrackScreen (TrackScreen.kt:").single()
            val viewModel = screen.parameterTypes.first()
            val stateFlow = HostCatalog.calls(screen).filter {
                it.owner().asInternalName() == viewModel.name.replace('.', '/') &&
                    it.type().stringValue() == "()Lkotlinx/coroutines/flow/StateFlow;"
            }.map(HostCatalog::resolve).distinct().single()
            check(stateFlow.returnType == StateFlow::class.java && stateFlow.parameterCount == 0)

            val filter = viewModel.declaredConstructors.flatMap(HostCatalog::constructorCalls)
                .filter { it.name().stringValue() == "<init>" &&
                    it.type().stringValue() == "(Lkotlin/coroutines/Continuation;)V" }
                .map { HostCatalog.type(it.owner().asInternalName()) }.distinct().single { type ->
                Function5::class.java.isAssignableFrom(type) && type.genericInterfaces.any {
                    it is ParameterizedType && it.rawType == Function5::class.java &&
                        it.actualTypeArguments.first().typeName.contains(track.name)
                }
            }.getMethod("invoke", *Array(5) { Any::class.java })

            val nativeList = HostCatalog.tracedMethods("TrackLazyColumn (TrackLazyColumn.kt:").single { method ->
                val types = method.parameterTypes
                Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers) &&
                    method.returnType == Void.TYPE && types.size == 15 && types.first() == listType && types[11] == composer
            }
            HostCatalog.requireSingleCall(screen.declaringClass, nativeList)
            val listOwner = nativeList.declaringClass.name.replace('.', '/')
            val success = screen.declaringClass.declaredMethods.single { candidate ->
                HostCatalog.calls(candidate).any {
                    it.owner().asInternalName() == listOwner && it.name().stringValue() == nativeList.name &&
                        it.type().stringValue() == nativeList.hostDescriptor()
                }
            }.parameterTypes.first()
            val state = success.declaredConstructors.single { constructor ->
                val types = constructor.parameterTypes
                types.size == 5 && types[0] == listType && types[1] == bool && types[2] == int && types[4] == bool
            }
            val groupType = (nativeList.genericParameterTypes[0] as ParameterizedType)
                .actualTypeArguments.single() as ParameterizedType
            check(groupType.actualTypeArguments.last() == track) { "歌曲分组类型不匹配" }
            val groupClass = groupType.rawType as Class<*>
            val group = groupClass.getConstructor(Any::class.java, listType)
            val groupKey = groupClass.declaredFields.single { !Modifier.isStatic(it.modifiers) && it.type == Any::class.java }
            val groupTracks = groupClass.declaredFields.single { !Modifier.isStatic(it.modifiers) && it.type == listType }
            check(groupKey.trySetAccessible() && groupTracks.trySetAccessible()) { "无法读取歌曲分组" }

            val builder = nativeList.declaringClass.declaredMethods.single { method ->
                val types = method.parameterTypes
                Modifier.isPrivate(method.modifiers) && Modifier.isStatic(method.modifiers) &&
                    method.returnType == Unit::class.java && types.size == 16 && types[0] == listType &&
                    types[1] == bool && types[2] == nativeList.parameterTypes[6] &&
                    types[3] == Function2::class.java && types.last().isInterface
            }
            val batch = builder.parameterTypes.last().methods.single { method ->
                !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                    method.parameterTypes.toList() == listOf(int, Function1::class.java, Function1::class.java,
                        Function1::class.java, Function4::class.java, Function4::class.java)
            }
            val copy = track.declaredMethods.single { method ->
                val types = method.parameterTypes
                Modifier.isStatic(method.modifiers) && method.returnType == track &&
                    method.name == "copy\$default" && types.size == 26 && types[0] == track &&
                    (1..3).all { types[it] == String::class.java } && types[8] == int &&
                    types[12] == String::class.java && types[13] == bool && types[14] == Long::class.javaPrimitiveType &&
                    types[18] == Long::class.javaPrimitiveType && types[23] == String::class.java && types[24] == int && types[25] == Any::class.java
            }
            val accessors = HostCatalog.accessors(state)
            return SongHookTarget(
                filter, stateFlow, builder, batch, checkNotNull(NativeAlbumPlayback.resolve()) { "无法定位歌曲点播入口" },
                NativeSongSelection.resolve(nativeList),
                state, accessors,
                group, groupKey, groupTracks, track.getMethod("getId"), track.getMethod("getTitle"),
                track.getMethod("getArtist"), track.getMethod("getAlbumArtist"), track.getMethod("getAlbum"),
                track.getMethod("getPath"), track.getMethod("getDuration"), copy
            )
        }
    }
}
