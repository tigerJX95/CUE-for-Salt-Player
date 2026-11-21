package spw.cue

import java.lang.classfile.instruction.FieldInstruction
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class ArtistSongSearch(val tracks: List<*>, val query: String)

/** Read the debounced query and complete list captured by the artist's state producer. */
internal class ArtistSongSearchBinding private constructor(
    val producer: Method,
    private val viewModel: Field,
    private val tracks: Field,
    private val query: Field,
    private val groupTracks: Method,
    private val groupMode: Field
) {
    fun read(producer: Any, expectedOwner: Any): ArtistSongSearch? {
        if (viewModel.get(producer) !== expectedOwner) return null
        return ArtistSongSearch(tracks.get(producer) as List<*>, query.get(producer).toString())
    }

    fun group(tracks: List<Any>): Map<*, *> = groupTracks.invoke(null, tracks, groupMode.get(null)) as Map<*, *>

    companion object {
        fun resolve(owner: Class<*>, success: Class<*>): ArtistSongSearchBinding {
            val prefix = "${owner.name}\$"
            val state = success.name.replace('.', '/')
            val producer = HostCatalog.candidates("ArtistWithTracks").filter { it.startsWith(prefix) }
                .map(HostCatalog::type).filter { candidate ->
                    val captures = candidate.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map(Field::getType)
                    captures.containsAll(listOf(owner, List::class.java, CharSequence::class.java))
                }.flatMap { it.declaredMethods.asList() }.single { method ->
                    method.name == "invokeSuspend" && method.returnType == Any::class.java &&
                        method.parameterTypes.contentEquals(arrayOf(Any::class.java)) &&
                        HostCatalog.calls(method).any {
                            it.owner().asInternalName() == state && it.name().stringValue() == "<init>"
                        }
                }
            fun captured(type: Class<*>): Field = producer.declaringClass.declaredFields.single {
                it.type == type && !Modifier.isStatic(it.modifiers)
            }.apply { check(trySetAccessible()) }

            val groupTracks = HostCatalog.calls(producer)
                .filter { it.type().stringValue().startsWith("(Ljava/util/List;L") &&
                    it.type().stringValue().endsWith(";)Ljava/util/Map;") }
                .map(HostCatalog::resolve).single {
                    Modifier.isStatic(it.modifiers) && it.parameterCount == 2 && it.parameterTypes[1].isEnum
                }
            val mode = groupTracks.parameterTypes[1]
            val field = HostCatalog.code(producer).filterIsInstance<FieldInstruction>().single {
                it.owner().asInternalName() == mode.name.replace('.', '/') &&
                    it.type().stringValue() == mode.descriptorString()
            }
            return ArtistSongSearchBinding(
                producer, captured(owner), captured(List::class.java), captured(CharSequence::class.java),
                groupTracks, mode.getField(field.name().stringValue())
            )
        }
    }
}
