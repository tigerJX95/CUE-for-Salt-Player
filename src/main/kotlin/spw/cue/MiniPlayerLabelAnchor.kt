package spw.cue

import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.ui.Modifier

// The native Text and its afterComposable callback are siblings in the same
// Workshop composition group, even when the native call uses default arguments.
internal class MiniPlayerLabelAnchor {
    private var binding: Binding? = null

    fun update(composition: CompositionData, original: String, label: String): Boolean {
        return runCatching {
            val previous = binding?.takeIf { it.node.isAttached }
            val current = previous ?: findLabel(composition, original)?.let { Binding(it, original) }
                ?: return false
            binding = current
            val shown = host.read(current.node)
            if (shown != original && shown != current.applied) return false
            current.original = original
            applyLabel(current, label)
        }.getOrDefault(false)
    }

    fun bindExisting(group: CompositionGroup): Boolean {
        val layout = group.layoutNodes().take(2).toList().singleOrNull() ?: return false
        val node = host.textNode(layout) ?: return false
        val original = host.read(node)
        if (group.data.none { it == original }) return false
        binding = Binding(node, original)
        return true
    }

    fun updateBound(label: String): Boolean = runCatching {
        val current = binding?.takeIf { it.node.isAttached } ?: return false
        val shown = host.read(current.node)
        if (shown != current.original && shown != current.applied) return false
        applyLabel(current, label)
    }.getOrDefault(false)

    private fun applyLabel(current: Binding, label: String): Boolean {
        host.write(current.node, label)
        current.applied = label
        return true
    }

    fun restore(original: String? = null) {
        val current = binding ?: return
        binding = null
        runCatching {
            if (current.node.isAttached && host.read(current.node) == current.applied) {
                host.write(current.node, original ?: current.original)
            }
        }
    }

    private fun findLabel(composition: CompositionData, original: String): Modifier.Node? {
        val path = precedingGroups(composition) ?: return null
        // The closest preceding sibling at an ancestor is the original Text group.
        for (group in path) {
            val node = group.layoutNodes().take(2).toList().singleOrNull() ?: continue
            val text = host.textNode(node) ?: continue
            if (host.read(text) == original) return text
        }
        return null
    }

    private fun precedingGroups(data: CompositionData): List<CompositionGroup>? {
        var previous: CompositionGroup? = null
        for (group in data.compositionGroups) {
            if (group.data.any { it === this }) return listOfNotNull(previous)
            val preceding = precedingGroups(group)
            if (preceding != null) return if (previous == null) preceding else preceding + previous
            previous = group
        }
        return null
    }

    private fun CompositionGroup.layoutNodes(): Sequence<Any> = sequence {
        node?.takeIf(host.layout::isInstance)?.let { yield(it) }
        for (child in compositionGroups) yieldAll(child.layoutNodes())
    }

    private class Binding(val node: Modifier.Node, var original: String, var applied: String? = null)

    private class HostTextNode {
        val layout = HostCatalog.type("androidx.compose.ui.node.LayoutNode")
        private val nodes = layout.getMethod("getNodes\$ui")
        private val visit = nodes.returnType.getMethod("headToTail\$ui", Function1::class.java)
        private val textNode = HostCatalog.type("androidx.compose.foundation.text.modifiers.TextStringSimpleNode")
        private val text = textNode.getDeclaredField("text").also {
            check(it.type == String::class.java && it.trySetAccessible())
        }
        private val update = textNode.getMethod("updateText", String::class.java)
        private val invalidate = textNode.getMethod(
            "doInvalidations", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
        )

        fun textNode(node: Any): Modifier.Node? {
            val matches = mutableListOf<Modifier.Node>()
            val inspect: (Modifier.Node) -> Unit = { if (textNode.isInstance(it)) matches += it }
            visit.invoke(nodes.invoke(node), inspect)
            return matches.singleOrNull()?.takeIf { it.isAttached }
        }

        fun read(node: Modifier.Node): String = text.get(node) as String

        fun write(node: Modifier.Node, value: String) {
            if (update.invoke(node, value) == true) {
                invalidate.invoke(node, false, true, false)
            }
        }
    }

    companion object {
        private val host by lazy { HostTextNode() }
    }
}
