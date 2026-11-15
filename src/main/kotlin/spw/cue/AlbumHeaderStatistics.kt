package spw.cue

import androidx.compose.runtime.Composer
import androidx.compose.ui.Modifier
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JvmModifier

internal data class AlbumHeaderCount(val discs: Int, val tracks: Int)

internal fun albumHeaderCount(groups: List<List<String>>, cueCounts: Map<String, Int>): AlbumHeaderCount? {
    if (groups.none { group -> group.any(cueCounts::containsKey) }) return null
    return AlbumHeaderCount(groups.size, groups.sumOf { group -> group.sumOf { cueCounts[it] ?: 1 } })
}

internal class AlbumHeaderStatistics private constructor(
    private val stateConstructor: Constructor<*>,
    private val stateReaders: List<Method>,
    private val albumConstructor: Constructor<*>,
    private val albumReaders: List<Method>,
    private val trackCountIndex: Int
) {
    fun stateWithCount(state: Any, count: Int): Any {
        val stateArgs = stateReaders.map { it.invoke(state) }.toTypedArray()
        val album = stateArgs[0]
        val albumArgs = albumReaders.map { it.invoke(album) }.toTypedArray()
        albumArgs[trackCountIndex] = count
        stateArgs[0] = albumConstructor.newInstance(*albumArgs)
        return stateConstructor.newInstance(*stateArgs)
    }

    companion object {
        fun create(header: Method): AlbumHeaderStatistics {
            val owner = header.declaringClass
            val state = header.parameterTypes.first()
            val composer = Composer::class.java
            val int = Int::class.javaPrimitiveType
            val block = HostCatalog.calls(header).filter {
                it.owner().asInternalName() == owner.name.replace('.', '/')
            }.map(HostCatalog::resolve).single {
                it.returnType == Void.TYPE && JvmModifier.isStatic(it.modifiers) &&
                    it.parameterTypes.toList() == listOf(state, Modifier::class.java, composer, int, int)
            }
            val calls = HostCatalog.calls(block)
            val countIndex = calls.indices.single { index ->
                val call = calls[index]
                call.owner().asInternalName() == "com/xuncorp/voxzen/data/entity/Album" &&
                    call.name().stringValue() == "getTrackCount" && call.type().stringValue() == "()I"
            }
            val getter = HostCatalog.resolve(calls[countIndex])
            val albumReader = HostCatalog.resolve(calls[countIndex - 1])
            val numberText = calls[countIndex + 1]
            check(numberText.owner().asInternalName() == "java/lang/String" &&
                numberText.name().stringValue() == "valueOf" &&
                numberText.type().stringValue() == "(I)Ljava/lang/String;")

            val stateConstructor = state.getConstructor(
                getter.declaringClass, List::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType
            )
            val stateReaders = HostCatalog.accessors(stateConstructor)
            check(stateReaders.first() == albumReader)
            val albumConstructor = getter.declaringClass.constructors.single { !it.isSynthetic }
            val albumReaders = HostCatalog.accessors(albumConstructor) {
                it.name.startsWith("get") || it.name.startsWith("is")
            }
            val trackCountIndex = albumReaders.indexOf(getter)
            check(trackCountIndex >= 0)
            return AlbumHeaderStatistics(stateConstructor, stateReaders, albumConstructor, albumReaders, trackCountIndex)
        }
    }
}
