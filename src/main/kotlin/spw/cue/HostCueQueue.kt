package spw.cue

import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.nio.file.Path

/** All obfuscated accessors are resolved from constructor slots and validated return types. */
internal class HostCueQueue {
    private val controllerType = HostCatalog.type("com.xuncorp.voxzen.service.PlaybackController")
    private val instance = controllerType.getField("INSTANCE")
    private val queueGetter = controllerType.getMethod("getPlaybackQueueState")
    private val stateType = ((queueGetter.genericReturnType as ParameterizedType)
        .actualTypeArguments.single() as ParameterizedType).rawType as Class<*>
    private val constructor = stateType.constructors.single { constructor ->
        val t = constructor.parameterTypes
        t.size == 6 && t[0].isEnum && t[1] == List::class.java && t[2] == Int::class.javaPrimitiveType &&
            t[3] == List::class.java && t[4] == Int::class.javaPrimitiveType && t[5] == Boolean::class.javaPrimitiveType
    }
    private val fields = HostCatalog.accessors(constructor)
    private val entryType = ((fields[1].genericReturnType as ParameterizedType)
        .actualTypeArguments.single() as ParameterizedType).rawType as Class<*>
    private val entry = HostCatalog.accessors(entryType.getConstructor(Int::class.javaPrimitiveType, Any::class.java))[1]
    private val media = HostCatalog.media
    private val mediaFields = HostCatalog.accessors(media.getConstructor(*Array(6) { String::class.java }))
    private val setQueue = controllerType.getMethod("setPlaybackQueue", List::class.java, Int::class.javaPrimitiveType)
    private val prepare = controllerType.getMethod("prepare")
    private val seek = controllerType.getMethod("seekToQueued", Long::class.javaPrimitiveType)
    private val play = controllerType.getMethod("play")
    private val speed = controllerType.getMethod("getPlaybackSpeed")

    init {
        check(Modifier.isStatic(instance.modifiers) && instance.type == controllerType)
        check(queueGetter.returnType == StateFlow::class.java && entry.returnType == Any::class.java)
        check(constructor.parameterTypes[0].enumConstants.map { (it as Enum<*>).name }
            .toSet() == setOf("Circle", "CircleEnd", "RepeatOne", "Random"))
        check(listOf(setQueue, prepare, seek, play).all { !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE })
        check(speed.returnType == Float::class.javaPrimitiveType)
    }

    val flow: StateFlow<*> get() = queueGetter.invoke(instance.get(null)) as StateFlow<*>
    fun mode(): String? = flow.value?.let { (fields[0].invoke(it) as Enum<*>).name }
    fun nativeStep(direction: Int) {
        controllerType.getMethod(if (direction < 0) "previous" else "next").invoke(instance.get(null))
    }
    fun speed(): Float = speed.invoke(instance.get(null)) as Float

    data class Source(val index: Int, val media: Any, val id: String, val audio: Path?)
    data class Snapshot(val items: List<Any>, val sources: List<Source>)

    fun snapshot(state: Any): Snapshot {
        val wrapped = fields[1].invoke(state) as List<*>
        check(wrapped.size <= 100_000) { "当前播放队列过大" }
        val items = wrapped.map { checkNotNull(entry.invoke(it)).also { item -> check(media.isInstance(item)) } }
        val sources = items.mapIndexed { index, item ->
            Source(index, item, mediaFields[0].invoke(item) as String,
                LibraryProbe.localPath(mediaFields.last().invoke(item) as? String))
        }
        return Snapshot(items, sources)
    }

    fun snapshot(): Snapshot? = flow.value?.let(::snapshot)

    fun currentIndex(): Int? = flow.value?.let { fields[2].invoke(it) as Int }

    fun currentSourceIndex(snapshot: Snapshot, current: Any): Int? {
        val id = mediaFields[0].invoke(current) as String
        val audio = LibraryProbe.localPath(mediaFields.last().invoke(current) as? String)
        fun Source.matches(): Boolean = id == this.id || audio != null && audio == this.audio
        val index = currentIndex()
        if (index != null && snapshot.sources.getOrNull(index)?.matches() == true) return index
        return snapshot.sources.firstOrNull { it.media === current || it.matches() }?.index
    }

    fun play(snapshot: Snapshot, sourceIndex: Int, positionMs: Long, sameSource: Boolean) {
        check(sourceIndex in snapshot.items.indices)
        val controller = instance.get(null)
        if (!sameSource) {
            // Preserve the host's complete scope instead of replacing it with a one-file queue.
            setQueue.invoke(controller, snapshot.items, sourceIndex)
            prepare.invoke(controller)
        }
        seek.invoke(controller, positionMs)
        play.invoke(controller)
    }
}
