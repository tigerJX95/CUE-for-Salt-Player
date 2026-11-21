package spw.cue

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.lang.classfile.Instruction
import java.lang.classfile.Opcode
import java.lang.classfile.instruction.ConstantInstruction
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.ref.WeakReference
import java.lang.reflect.Method

internal class SongLocateLayout(private val keys: List<Any>, beforeMeasure: LazyListLayoutInfo) {
    private val beforeMeasure = WeakReference(beforeMeasure)

    fun indexOf(key: String): Int? = keys.indexOf(key).takeIf { it >= 0 }

    fun matches(measured: LazyListLayoutInfo): Boolean =
        measured !== beforeMeasure.get() && measured.totalItemsCount == keys.size &&
            measured.visibleItemsInfo.isNotEmpty() &&
            measured.visibleItemsInfo.all { keys.getOrNull(it.index) == it.key }

    fun visibleRevealDelta(measured: LazyListLayoutInfo, key: String, headerClearance: Int): Int? {
        val row = measured.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
        val end = measured.viewportEndOffset - measured.afterContentPadding
        val start = (measured.viewportStartOffset + measured.beforeContentPadding + headerClearance)
            .coerceAtMost(end - row.size)
        return when {
            row.offset < start -> row.offset - start
            row.offset + row.size > end -> row.offset + row.size - end
            else -> 0
        }
    }
}

internal class NativeSongLocate private constructor(
    val caller: Class<*>,
    val scrollbar: Method,
    private val selectorState: Method,
    private val lazyState: Method,
    private val icon: (Composer, Int) -> Unit
) {
    fun scrollState(selector: Any): LazyListState =
        lazyState.invoke(selectorState.invoke(selector)) as LazyListState

    @Composable
    fun Button(modifier: Modifier, onClick: () -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        val hovered = interaction.collectIsHoveredAsState().value
        val theme = nativeSongRowTheme()
        val foreground = theme.titleColor?.let { Color(it.toULong()) } ?: Color(0xffeeeeee)
        val background = if (foreground.luminance() > .5f) Color(0xff2d2d2d) else Color(0xffeeeeee)
        val label = "定位当前播放歌曲"
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            if (hovered) {
                BasicText(label, Modifier.padding(end = 8.dp)
                    .background(background, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                    style = TextStyle(color = foreground, fontSize = 12.sp))
            }
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(background)
                    .border(Dp.Hairline, foreground.copy(alpha = .35f), CircleShape)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = interaction, indication = null, onClickLabel = label,
                        role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                // Reuse the host's glyph without opening its hover tooltip scene.
                icon(currentComposer, 0)
            }
        }
    }

    companion object {
        fun resolve(list: Method): NativeSongLocate {
            val caller = list.declaringClass
            val (defaults, scrollbarCall) = caller.declaredMethods.flatMap { method ->
                HostCatalog.code(method).filterIsInstance<Instruction>().zipWithNext()
            }.mapNotNull { (previous, instruction) ->
                val call = instruction as? InvokeInstruction ?: return@mapNotNull null
                if (call.type().stringValue() != "(Landroidx/compose/foundation/layout/BoxScope;" +
                    "Landroidx/compose/foundation/gestures/ScrollableState;Landroidx/compose/ui/Modifier;" +
                    "Landroidx/compose/runtime/Composer;II)V") return@mapNotNull null
                previous to call
            }.single()
            val defaultMask: Any? = (defaults as? ConstantInstruction)?.constantValue()
            check(defaultMask == 0) { "歌曲页滚动条调用使用默认参数" }
            val scrollbar = HostCatalog.resolve(scrollbarCall)
            check(scrollbar.parameterTypes.first() == BoxScope::class.java)
            val selector = list.parameterTypes[2]
            val getState = selector.methods.single { method ->
                method.parameterCount == 0 && method.returnType.methods.any { it.isLazyStateGetter() }
            }
            val getLazy = getState.returnType.methods.single { it.isLazyStateGetter() }
            val nativeButton = HostCatalog.tracedMethods("GoToNowPlaying (GoToNowPlaying.kt:").single()
            val iconGetter = HostCatalog.calls(nativeButton).single {
                it.opcode() == Opcode.INVOKESTATIC && it.type().stringValue() == "()Lkotlin/jvm/functions/Function2;"
            }.let(HostCatalog::resolve)
            @Suppress("UNCHECKED_CAST")
            val icon = iconGetter.invoke(null) as (Composer, Int) -> Unit
            return NativeSongLocate(caller, scrollbar, getState, getLazy, icon)
        }

        private fun Method.isLazyStateGetter() = parameterCount == 0 && returnType == LazyListState::class.java
    }
}
