package spw.cue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal val LocalCueAlbumRow = staticCompositionLocalOf { false }

internal class AlbumListScope(private val original: Any) : InvocationHandler {
    private class Entry(val method: Method, val args: Array<out Any?>) {
        val footer get() = args.size == 3 && args[0] == "footer"
        val wholeTrack get() = args.size == 6 && args[0] == 1
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

    fun apply(hideWholeTrack: Boolean, appendCue: (Boolean) -> Boolean) {
        recording = false
        var wholeTrack: Entry? = null
        try {
            for (entry in entries) {
                if (entry.footer && !appendCue(wholeTrack != null)) {
                    wholeTrack?.let { delegate(it.method, it.args) }
                }
                if (hideWholeTrack && entry.wholeTrack) {
                    wholeTrack = entry
                } else {
                    val args = if (entry.wholeTrack) withAlbumRowScope(entry.args) else entry.args
                    delegate(entry.method, args)
                }
            }
        } finally {
            entries.clear()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun withAlbumRowScope(args: Array<out Any?>): Array<out Any?> {
        val nativeRow = args.last() as? (@Composable (Any, Int) -> Unit) ?: return args
        val wrapped: @Composable (Any, Int) -> Unit = { scope, index ->
            CompositionLocalProvider(LocalCueAlbumRow provides true) { nativeRow(scope, index) }
        }
        return Array(args.size) { index -> if (index == args.lastIndex) wrapped else args[index] }
    }

    private fun delegate(method: Method, args: Array<out Any?>): Any? = try {
        method.invoke(original, *args)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
