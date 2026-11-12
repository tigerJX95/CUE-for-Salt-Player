package spw.cue

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier as JvmModifier

// Use the same default text parameters as the native disc heading.
private val discText by lazy {
    runCatching {
        val parameters = listOf(
            String::class.java, Modifier::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, FontStyle::class.java, FontWeight::class.java, FontFamily::class.java,
            Long::class.javaPrimitiveType, TextDecoration::class.java, TextAlign::class.java,
            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Function1::class.java, TextStyle::class.java,
            Composer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        HostCatalog.calls(HostCatalog.stickyHeader()).filter {
            it.type().stringValue().startsWith("(Ljava/lang/String;Landroidx/compose/ui/Modifier;")
        }.map(HostCatalog::resolve).distinct().single {
            JvmModifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.toList() == parameters
        }
    }.getOrNull()
}

@Composable
internal fun NativeAlbumText(
    label: String, modifier: Modifier = Modifier, singleLine: Boolean = false,
    style: TextStyle? = null, color: Long? = null
) {
    val text = discText
    if (text == null) {
        BasicText(
            label, modifier, style = (style ?: TextStyle(fontSize = 14.sp)).let {
                if (color == null) it else it.copy(color = Color(color.toULong()))
            },
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip
        )
    } else {
        val composer = currentComposer
        var defaults = if (style == null) 0x1fffc else 0xfffc
        if (color != null) defaults = defaults and (1 shl 2).inv()
        val mask = if (singleLine) defaults and ((1 shl 11) or (1 shl 13)).inv() else defaults
        val ellipsis = 2 // TextOverflow.Ellipsis JVM value
        try {
            text.invoke(
                null, label, modifier, color ?: 0L, 0L, null, null, null, 0L, null, null, 0L,
                ellipsis, false, 1, 0, null, style, composer, 0, 0, mask
            )
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }
}
