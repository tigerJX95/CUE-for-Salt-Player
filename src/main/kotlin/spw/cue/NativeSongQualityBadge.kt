package spw.cue

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import java.lang.classfile.Opcode
import java.lang.classfile.constantpool.StringEntry
import java.lang.classfile.instruction.ConstantInstruction.LoadConstantInstruction
import java.lang.invoke.MethodType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

private data class QualityBadgeBinding(val config: Any, val enabled: Method, val painter: Method)

private val qualityBadgeBinding by lazy {
    runCatching {
        val descriptor = MethodType.methodType(
            Painter::class.java, HostCatalog.track, Composer::class.java, Int::class.javaPrimitiveType
        ).descriptorString()
        val row = HostCatalog.tracedMethods("TrackItemContent (TrackItem.kt:").single()
        val painter = HostCatalog.calls(row).filter {
            it.opcode() == Opcode.INVOKESTATIC && it.type().stringValue() == descriptor
        }.map(HostCatalog::resolve).distinct().single { method ->
            HostCatalog.code(method).filterIsInstance<LoadConstantInstruction>().any {
                (it.constantEntry() as? StringEntry)?.stringValue()?.contains("audioQualityPainter") == true
            }
        }
        val config = HostCatalog.type("com.xuncorp.voxzen.util.AppConfig")
        val enabled = config.getMethod("getShowAudioQualityBadge")
        check(enabled.returnType == Boolean::class.javaPrimitiveType && painter.trySetAccessible())
        QualityBadgeBinding(config.getField("INSTANCE").get(null), enabled, painter)
    }.getOrNull()
}

@Composable
internal fun NativeSongQualityBadge(source: Any) {
    val badge = qualityBadgeBinding ?: return
    if (badge.enabled.invoke(badge.config) != true) return

    val composer = currentComposer
    val painter = try {
        badge.painter.invoke(null, source, composer, 0) as? Painter
    } catch (error: InvocationTargetException) {
        throw error.targetException
    } ?: return
    Image(painter, contentDescription = null, modifier = Modifier.height(10.dp))
    Spacer(Modifier.width(6.dp))
}
