package spw.cue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal class AlbumRowScope(val cues: Map<String, AlbumCue>, val useTitleFallback: Boolean)

internal val LocalCueAlbumRow = staticCompositionLocalOf<AlbumRowScope?> { null }

internal class AlbumListScope(private val original: Any) : InvocationHandler {
    private class Entry(val method: Method, val args: Array<out Any?>) {
        val footer get() = args.size == 3 && args[0] == "footer"
        val wholeTrack get() = args.size == 6 && args[0] == 1
        var beforeReplay: (() -> Unit)? = null
    }

    private val entries = mutableListOf<Entry>()
    private var recording = true

    val hasFooter get() = entries.lastOrNull()?.footer == true

    override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
        val arguments = args.orEmpty()
        if (recording && method.returnType == Void.TYPE) {
            entries.add(Entry(method, arguments))
            return null
        }
        return delegate(method, arguments)
    }

    @Suppress("UNCHECKED_CAST")
    fun placeAlbumActions(
        isDiscHeading: (Any?) -> Boolean,
        decorate: @Composable (heading: @Composable () -> Unit) -> Unit,
        append: () -> Unit
    ) {
        // A native composable can implement several JVM FunctionN interfaces.
        val headingType = Function3::class.java as Class<@Composable (Any) -> Unit>
        val index = entries.indexOfFirst {
            it.args.size == 3 && headingType.isInstance(it.args[2]) && isDiscHeading(it.args[0])
        }
        if (index == -1) {
            // A disc with no number has no visible heading. Keep its controls above the tracks.
            val header = entries.indexOfFirst { it.args.size == 3 && it.args[0] == "header" }
            entries.getOrNull(header + 1)?.beforeReplay = append
            return
        }
        val entry = entries[index]
        val original = headingType.cast(entry.args[2])
        val heading: @Composable (Any) -> Unit = { scope -> decorate { original(scope) } }
        entries[index] = Entry(entry.method, arrayOf(entry.args[0], entry.args[1], heading))
    }

    fun apply(
        hideWholeTrack: Boolean,
        cues: Map<String, AlbumCue> = emptyMap(),
        appendCue: (Boolean) -> Boolean
    ) {
        recording = false
        val rowScope = AlbumRowScope(cues, useTitleFallback = true)
        var wholeTrack: Entry? = null
        try {
            for (entry in entries) {
                entry.beforeReplay?.invoke()
                if (entry.footer && !appendCue(wholeTrack != null)) {
                    wholeTrack?.let { delegate(it.method, it.args) }
                }
                if (hideWholeTrack && entry.wholeTrack) {
                    wholeTrack = entry
                } else {
                    val args = if (entry.wholeTrack) withAlbumRowScope(entry.args, rowScope) else entry.args
                    delegate(entry.method, args)
                }
            }
        } finally {
            entries.clear()
        }
    }

    fun applyByTrack(
        expectedIds: List<String>,
        hiddenIds: Set<String>,
        append: (String, Boolean) -> Unit,
        appendFallback: () -> Unit,
        cues: Map<String, AlbumCue> = emptyMap()
    ) {
        recording = false
        try {
            val idsByEntry = entries.map { entry ->
                val count = entry.args.firstOrNull() as? Int
                if (entry.args.size != 6 || count == null || count !in 1..expectedIds.size) {
                    emptyList()
                } else {
                    val key = entry.args[1] as? Function1<*, *>
                    @Suppress("UNCHECKED_CAST")
                    runCatching {
                        List(count) { (key as? (Int) -> Any?)?.invoke(it) as? String ?: "" }
                    }.getOrDefault(emptyList())
                }
            }
            val rowScope = AlbumRowScope(cues, useTitleFallback = false)
            val recordedIds = idsByEntry.flatten()
            val matched = expectedIds.distinct().size == expectedIds.size && recordedIds == expectedIds
            for ((index, entry) in entries.withIndex()) {
                entry.beforeReplay?.invoke()
                if (entry.footer && !matched) appendFallback()
                val ids = idsByEntry[index].takeIf { matched }.orEmpty()
                val shown = ids.indices.filter { ids[it] !in hiddenIds }
                val hide = shown.size < ids.size
                val args = when {
                    !hide -> entry.args
                    shown.isEmpty() -> null
                    else -> subsetArgs(entry.args, shown)
                }
                val applied = hide && (shown.isEmpty() || args != null)
                if (args != null || !applied) {
                    val shownArgs = args ?: entry.args
                    val scopedArgs = if (cues.isNotEmpty() && idsByEntry[index].isNotEmpty()) {
                        withAlbumRowScope(shownArgs, rowScope)
                    } else shownArgs
                    delegate(entry.method, scopedArgs)
                }
                ids.forEach { id -> append(id, applied && id in hiddenIds) }
            }
        } finally {
            entries.clear()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun subsetArgs(args: Array<out Any?>, shown: List<Int>): Array<Any?>? {
        val content = args.last()
        if (content != null && content !is Function4<*, *, *, *, *>) return null
        if (args.dropLast(1).any { it is Function<*> && it !is Function1<*, *> }) return null
        val nativeRow = content as? (@Composable (Any, Int) -> Unit)
        val remappedRow: @Composable (Any, Int) -> Unit = { scope, index ->
            nativeRow?.invoke(scope, shown[index])
        }
        return Array(args.size) { index ->
            val arg = args[index]
            when {
                index == 0 -> shown.size
                index == args.lastIndex && nativeRow != null -> remappedRow
                arg is Function1<*, *> -> {
                    val callback = arg as (Int) -> Any?
                    val remapped: (Int) -> Any? = { callback(shown[it]) }
                    remapped
                }
                else -> arg
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun withAlbumRowScope(args: Array<out Any?>, rowScope: AlbumRowScope): Array<out Any?> {
        val nativeRow = args.last() as? (@Composable (Any, Int) -> Unit) ?: return args
        val wrapped: @Composable (Any, Int) -> Unit = { scope, index ->
            CompositionLocalProvider(LocalCueAlbumRow provides rowScope) { nativeRow(scope, index) }
        }
        return Array(args.size) { index -> if (index == args.lastIndex) wrapped else args[index] }
    }

    private fun delegate(method: Method, args: Array<out Any?>): Any? = try {
        method.invoke(original, *args)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
