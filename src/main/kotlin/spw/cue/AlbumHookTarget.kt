package spw.cue

import androidx.compose.runtime.Composable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy

internal class AlbumHookTarget(
    val listBuilder: Method,
    private val albumDecorator: Class<*>,
    private val entities: Field,
    private val trackId: Method,
    private val addItem: Method,
    private val discNumber: Method?,
    val playback: NativeAlbumPlayback?,
    val cover: NativeCoverFallback?
) {
    data class Track(val id: String, val entity: Any)

    fun albumTracks(args: Array<Any?>): List<Track> {
        if (!albumDecorator.isInstance(args.getOrNull(2)) || args.getOrNull(1) != false) return emptyList()
        return trackGroupsIn(args.firstOrNull() as? List<*> ?: return emptyList()).flatten()
    }

    fun trackGroupsIn(groups: List<*>): List<List<Track>> = groups.filterNotNull().mapNotNull { group ->
        val rows = entities.get(group) as? List<*> ?: return emptyList()
        if (rows.isEmpty()) return@mapNotNull null
        rows.map { row -> readTrack(row ?: return emptyList()) ?: return emptyList() }
    }

    private fun readTrack(track: Any): Track? {
        val id = (trackId.invoke(track) as? String)?.takeIf { it.isNotBlank() } ?: return null
        return Track(id, track)
    }

    fun recordList(scope: Any): Pair<Any, AlbumListScope> {
        val handler = AlbumListScope(scope)
        val scopeType = listBuilder.parameterTypes.last()
        val proxy = Proxy.newProxyInstance(scopeType.classLoader, arrayOf(scopeType), handler)
        return proxy to handler
    }

    fun appendCue(scope: Any, trackId: String, content: @Composable (Any) -> Unit) {
        addItem.invoke(null, scope, "spw.cue.support:$trackId", content)
    }

    fun isVisibleDiscHeading(key: Any?): Boolean {
        val number = discNumber ?: return false
        return number.declaringClass.isInstance(key) &&
            runCatching { number.invoke(key) as Int != 0 }.getOrDefault(false)
    }

    companion object {
        fun resolve(): AlbumHookTarget {
            val track = HostCatalog.track
            val composer = HostCatalog.type("androidx.compose.runtime.Composer")
            val padding = HostCatalog.type("androidx.compose.foundation.layout.PaddingValues")
            val int = Int::class.javaPrimitiveType
            val bool = Boolean::class.javaPrimitiveType
            val decorators = HostCatalog.candidates("AlbumContentScreen.kt:").map(HostCatalog::type)
            val gridCovers = HostCatalog.candidates("AlbumCover (AlbumCover.kt:")
            val songCovers = HostCatalog.candidates("TrackItemContent (TrackItem.kt:")
            val artistCovers = HostCatalog.candidates("ArtistCoverImage (ArtistCoverImage.kt:")
            val decorator = checkNotNull(decorators.singleOrNull { type ->
                type.declaredMethods.any {
                    !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                        it.parameterTypes.toList() == listOf(padding, Function2::class.java, composer, int)
                }
            }) { "无法唯一定位专辑页列表标识" }
            val trackList = HostCatalog.owner("TrackLazyColumn (TrackLazyColumn.kt:")
            val list = checkNotNull(trackList.declaredMethods.singleOrNull { method ->
                val types = method.parameterTypes
                Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers) &&
                    method.returnType == Void.TYPE && types.size == 15 && types[0] == List::class.java &&
                    types[6].isAssignableFrom(decorator) && types[11] == composer
            }) { "无法确认宿主曲目列表签名" }

            val groupType = (list.genericParameterTypes[0] as ParameterizedType)
                .actualTypeArguments.single() as ParameterizedType
            check(groupType.actualTypeArguments.last() == track) { "专辑曲目类型不匹配" }
            val group = groupType.rawType as Class<*>
            val entities = checkNotNull(group.declaredFields.singleOrNull { it.type == List::class.java })
            check(entities.trySetAccessible()) { "无法读取专辑曲目分组" }

            val builder = checkNotNull(trackList.declaredMethods.singleOrNull { method ->
                val types = method.parameterTypes
                Modifier.isPrivate(method.modifiers) && Modifier.isStatic(method.modifiers) &&
                    method.returnType == Unit::class.java && types.size == 16 &&
                    types[0] == List::class.java && types[1] == bool && types[2] == list.parameterTypes[6] &&
                    types[3] == Function2::class.java && types.last().isInterface
            }) { "无法确认曲目列表构建器" }
            val scope = builder.parameterTypes.last()
            val item = checkNotNull(scope.declaredMethods.singleOrNull {
                Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                    it.parameterTypes.toList() == listOf(scope, Any::class.java, Function3::class.java)
            }) { "无法确认列表追加入口" }
            val playback = NativeAlbumPlayback.resolve()
            val cover = NativeCoverFallback.resolve(decorator, groupType, gridCovers, songCovers, artistCovers)
            val discNumber = runCatching {
                // Native disc keys carry the disc number, track count and total duration.
                val keyType = groupType.actualTypeArguments.first() as Class<*>
                val disc = keyType.permittedSubclasses.orEmpty().flatMap { it.constructors.asList() }.single {
                    it.parameterTypes.toList() == listOf(int, int, Long::class.javaPrimitiveType)
                }
                HostCatalog.accessors(disc).first()
            }.getOrNull()
            return AlbumHookTarget(builder, decorator, entities, track.getMethod("getId"), item, discNumber, playback, cover)
        }
    }
}
